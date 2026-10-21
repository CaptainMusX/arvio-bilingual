package com.arflix.tv.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.ui.screens.player.subtitles.SubtitleAiModel
import com.arflix.tv.ui.screens.player.subtitles.SubtitleSyncMatcher
import com.arflix.tv.ui.screens.player.subtitles.SubtitleTranslationService
import com.arflix.tv.ui.screens.player.subtitles.bilingual.SubtitleSampleDecoder
import com.arflix.tv.util.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Downloads one [OfflineItem]: the video (resumable HTTP range download), then prepares its
 * subtitles — the chosen addon subtitle, or the chosen embedded text track read straight out of
 * the downloaded file — and translates every line with the user's AI service, writing original,
 * translated and bilingual SRT files next to the video.
 */
class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val ctx = applicationContext
        val item = OfflineLibrary.get(ctx, id) ?: return Result.failure()
        runCatching { setForeground(foregroundInfo(item.displayTitle, -1)) }
            .onFailure { Log.w(TAG, "foreground refused: ${it.message}") }
        // Keep the CPU and Wi-Fi awake while the screen is off or another app is in front.
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wake = runCatching {
            power?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "arvio:offline-download")?.apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
        }.getOrNull()
        val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val wifi = runCatching {
            wifiManager?.createWifiLock(
                if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF else android.net.wifi.WifiManager.WIFI_MODE_FULL,
                "arvio:offline-download"
            )?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        return try {
            runDownload(id, item)
        } finally {
            runCatching { if (wake?.isHeld == true) wake.release() }
            runCatching { if (wifi?.isHeld == true) wifi.release() }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(KEY_ID)
        val title = id?.let { OfflineLibrary.get(applicationContext, it)?.displayTitle } ?: "ARVIO"
        return foregroundInfo(title, -1)
    }

    private suspend fun runDownload(id: String, item: OfflineItem): Result {
        val ctx = applicationContext
        return try {
            val afterVideo = if (item.status == OfflineItem.STATUS_DONE || isVideoComplete(item)) item else downloadVideo(item)
            prepareSubtitles(afterVideo)
            OfflineLibrary.update(ctx, id) { it.copy(status = OfflineItem.STATUS_DONE, message = it.message) }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A user pause already marked it paused; a system stop (Doze, memory) is retried by
            // WorkManager on its own, so it shows as queued rather than paused.
            OfflineLibrary.update(ctx, id) {
                when (it.status) {
                    OfflineItem.STATUS_DONE, OfflineItem.STATUS_PAUSED -> it
                    else -> it.copy(status = OfflineItem.STATUS_QUEUED, message = "Waiting to resume…")
                }
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download $id failed", e)
            OfflineLibrary.update(ctx, id) { it.copy(status = OfflineItem.STATUS_FAILED, message = e.message ?: e::class.java.simpleName) }
            Result.failure()
        }
    }

    private fun isVideoComplete(item: OfflineItem): Boolean {
        val f = OfflineLibrary.videoFile(applicationContext, item)
        return item.totalBytes > 0 && f.isFile && f.length() >= item.totalBytes
    }

    // ── Video ────────────────────────────────────────────────────────────────────

    private suspend fun downloadVideo(item: OfflineItem): OfflineItem = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val file = OfflineLibrary.videoFile(ctx, item)
        var current = item.copy(status = OfflineItem.STATUS_DOWNLOADING)
        OfflineLibrary.upsert(ctx, current)
        var attempt = 0
        while (true) {
            val existing = if (file.isFile) file.length() else 0L
            val builder = Request.Builder().url(item.url)
            item.headers.forEach { (k, v) -> if (!k.equals("Range", true)) builder.header(k, v) }
            if (existing > 0) builder.header("Range", "bytes=$existing-")
            try {
                OkHttpProvider.playbackClient.newCall(builder.build()).execute().use { response ->
                    if (response.code == 416 && existing > 0) {
                        // Already complete.
                        current = current.copy(totalBytes = existing, downloadedBytes = existing)
                        OfflineLibrary.upsert(ctx, current)
                        return@withContext current
                    }
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    if (type.contains("mpegurl") || type.contains("dash+xml") || type.startsWith("text/html")) {
                        throw IllegalStateException("This source is a stream playlist ($type) and cannot be downloaded")
                    }
                    val resumed = response.code == 206 && existing > 0
                    val body = response.body ?: throw IllegalStateException("empty response")
                    val length = body.contentLength()
                    val total = if (length > 0) (if (resumed) existing + length else length) else -1L
                    RandomAccessFile(file, "rw").use { raf ->
                        if (resumed) raf.seek(existing) else { raf.setLength(0); raf.seek(0) }
                        var written = if (resumed) existing else 0L
                        current = current.copy(totalBytes = total, downloadedBytes = written)
                        OfflineLibrary.upsert(ctx, current)
                        val buf = ByteArray(256 * 1024)
                        val input = body.byteStream()
                        var lastReport = 0L
                        while (true) {
                            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 1_000L) {
                                lastReport = now
                                val w = written
                                OfflineLibrary.update(ctx, item.id, persist = false) { it.copy(downloadedBytes = w, totalBytes = total) }
                                val pct = if (total > 0) ((w * 100) / total).toInt() else -1
                                runCatching { setForeground(foregroundInfo(item.displayTitle, pct)) }
                            }
                        }
                        if (total > 0 && written < total) throw IllegalStateException("connection closed at $written/$total")
                        current = current.copy(totalBytes = if (total > 0) total else written, downloadedBytes = written)
                        OfflineLibrary.upsert(ctx, current)
                        return@withContext current
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val playlist = e.message?.contains("playlist") == true
                if (playlist || attempt >= MAX_ATTEMPTS) throw e
                Log.w(TAG, "download interrupted (${e.message}), retry $attempt")
                delay(3_000L * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        current
    }

    // ── Subtitles ────────────────────────────────────────────────────────────────

    private suspend fun prepareSubtitles(item: OfflineItem) {
        val ctx = applicationContext
        val dir = OfflineLibrary.dirFor(ctx, item.id)
        if (item.bilingualSrt != null && File(dir, item.bilingualSrt).isFile && item.translatedLines >= item.totalLines && item.totalLines > 0) return
        OfflineLibrary.update(ctx, item.id) { it.copy(status = OfflineItem.STATUS_SUBTITLES) }
        val cues: List<OfflineSubtitles.Cue> = when {
            !item.subtitleUrl.isNullOrBlank() -> {
                val raw = SubtitleSyncMatcher.loadRaw(item.subtitleUrl, item.subtitleLang)
                val addon = raw?.let { SubtitleSyncMatcher.parseCues(it) }
                    ?.map { OfflineSubtitles.Cue(it.startMs, it.endMs, it.text) }
                    .orEmpty()
                alignToEmbedded(OfflineLibrary.videoFile(ctx, item), addon)
            }
            else -> extractEmbedded(OfflineLibrary.videoFile(ctx, item), item.embeddedSubtitleLang)
        }
        if (cues.isEmpty()) {
            OfflineLibrary.update(ctx, item.id) { it.copy(message = "No text subtitle could be prepared; the video plays with its own tracks") }
            return
        }
        val originalName = "original.srt"
        File(dir, originalName).writeText(OfflineSubtitles.toSrt(cues))
        OfflineLibrary.update(ctx, item.id) { it.copy(originalSrt = originalName, totalLines = cues.size) }

        if (item.targetLanguageName.isBlank()) return
        val service = buildTranslationService() ?: run {
            OfflineLibrary.update(ctx, item.id) { it.copy(message = "AI translation is not configured; original subtitle saved") }
            return
        }
        OfflineLibrary.update(ctx, item.id) { it.copy(status = OfflineItem.STATUS_TRANSLATING) }
        val translations = arrayOfNulls<String>(cues.size)
        val texts = cues.map { it.text }
        var i = 0
        var failures = 0
        var contextPairs: List<Pair<String, String>> = emptyList()
        while (i < texts.size) {
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
            val end = minOf(texts.size, i + CHUNK)
            val batch = texts.subList(i, end)
            val result = runCatching {
                service.translateBatch(batch, item.targetLanguageName, context = contextPairs, title = item.title)
            }.getOrNull()
            if (result != null && result.success && result.lines.size == batch.size) {
                result.lines.forEachIndexed { k, line -> translations[i + k] = line }
                contextPairs = batch.zip(result.lines).takeLast(CONTEXT)
                i = end
                failures = 0
                val done = i
                OfflineLibrary.update(ctx, item.id, persist = false) { it.copy(translatedLines = done) }
                runCatching { setForeground(foregroundInfo(item.displayTitle + " · AI", (done * 100) / texts.size)) }
            } else {
                failures++
                val rateLimited = result?.errorMessage?.contains("RATE", ignoreCase = true) == true
                if (failures >= MAX_ATTEMPTS) {
                    OfflineLibrary.update(ctx, item.id) {
                        it.copy(message = "AI translation stopped at line $i/${texts.size}: ${result?.errorMessage ?: "error"} — retry to continue")
                    }
                    break
                }
                delay(if (rateLimited) 20_000L * failures else 4_000L * failures)
            }
        }
        val list = translations.toList()
        val translatedName = "translated.srt"
        val bilingualName = "bilingual.srt"
        File(dir, translatedName).writeText(OfflineSubtitles.toSrt(OfflineSubtitles.translatedOnly(cues, list)))
        File(dir, bilingualName).writeText(OfflineSubtitles.toSrt(OfflineSubtitles.bilingual(cues, list)))
        val count = list.count { !it.isNullOrBlank() }
        OfflineLibrary.update(ctx, item.id) {
            it.copy(translatedSrt = translatedName, bilingualSrt = bilingualName, translatedLines = count)
        }
        if (count < cues.size) throw IllegalStateException("AI translation incomplete ($count/${cues.size}) — retry to continue")
    }

    private suspend fun buildTranslationService(): SubtitleTranslationService? {
        val ctx = applicationContext
        val prefs = ctx.settingsDataStore.data.first()
        val key = prefs[stringPreferencesKey("subtitle_ai_api_key")].orEmpty()
        val model = runCatching {
            SubtitleAiModel.valueOf(prefs[stringPreferencesKey("subtitle_ai_model")] ?: SubtitleAiModel.GROQ_LLAMA_70B.name)
        }.getOrDefault(SubtitleAiModel.GROQ_LLAMA_70B)
        val custom = com.arflix.tv.ui.screens.player.subtitles.ai.CustomAiConfigStore.from(ctx)
        val service = SubtitleTranslationService(
            apiKeyProvider = { key },
            modelProvider = { model },
            customConfigProvider = { custom.current },
        )
        return service.takeIf { it.isConfigured() }
    }

    /**
     * The downloaded file's own text subtitle track (any language) is authored against this exact
     * cut: line the addon subtitle up with its timing before translating, so the offline copy
     * needs no correction later.
     */
    private suspend fun alignToEmbedded(file: File, cues: List<OfflineSubtitles.Cue>): List<OfflineSubtitles.Cue> {
        if (cues.size < 10) return cues
        val reference = extractEmbedded(file, null)
        if (reference.size < 10) return cues
        val r = com.arflix.tv.ui.screens.player.subtitles.bilingual.EmbeddedTimingAligner.align(
            reference.map { com.arflix.tv.ui.screens.player.subtitles.bilingual.EmbeddedTimingAligner.Interval(it.startMs, it.endMs) },
            cues.map { com.arflix.tv.ui.screens.player.subtitles.bilingual.EmbeddedTimingAligner.Interval(it.startMs, it.endMs) },
        )
        if (r == null || !r.confident) {
            Log.i(TAG, "embedded alignment: not confident (${r?.matched}/${r?.considered})")
            return cues
        }
        Log.i(TAG, "embedded alignment: x${r.scale} ${r.shiftMs}ms (${r.matched}/${r.considered})")
        if (r.isPlainShift && kotlin.math.abs(r.shiftMs) < 80L) return cues
        return cues.map {
            it.copy(
                startMs = (Math.round(it.startMs * r.scale) + r.shiftMs).coerceAtLeast(0L),
                endMs = (Math.round(it.endMs * r.scale) + r.shiftMs).coerceAtLeast(1L),
            )
        }
    }

    /** Reads every cue of an embedded text track out of the downloaded file. */
    @OptIn(UnstableApi::class)
    private suspend fun extractEmbedded(file: File, wantedLang: String?): List<OfflineSubtitles.Cue> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        val extractor = MediaExtractorCompat(DefaultExtractorsFactory(), DefaultDataSource.Factory(applicationContext))
        try {
            extractor.setDataSource(Uri.fromFile(file), 0L)
            val wanted = wantedLang?.lowercase()?.substringBefore('-')
            data class Cand(val index: Int, val decoder: SubtitleSampleDecoder, val lang: String?)
            val cands = (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val d = SubtitleSampleDecoder.forFormat(format.getString(MediaFormat.KEY_MIME), null) ?: return@mapNotNull null
                val lang = runCatching { format.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull()?.lowercase()?.substringBefore('-')
                Cand(index, d, lang)
            }
            val pick = cands.firstOrNull { sameLang(it.lang, wanted) } ?: cands.firstOrNull() ?: return@withContext emptyList()
            val chosen = pick.index
            val dec = pick.decoder
            extractor.selectTrack(chosen)
            val buffer = ByteBuffer.allocate(1 shl 20)
            val raw = ArrayList<Triple<Long, Long?, String>>()
            while (coroutineContext.isActive) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes, 0, size)
                runCatching { dec.decode(timeUs, bytes, 0, size) }.getOrNull()?.let { cue ->
                    if (cue.text.isNotBlank()) raw.add(Triple(cue.startMs, cue.endMs, cue.text))
                }
                if (!extractor.advance()) break
            }
            OfflineSubtitles.normalise(raw)
        } catch (e: Exception) {
            Log.w(TAG, "embedded subtitle extraction failed: ${e.message}")
            emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun sameLang(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        return runCatching { java.util.Locale(a).isO3Language == b || java.util.Locale(b).isO3Language == a }.getOrDefault(false)
    }

    // ── Notification ─────────────────────────────────────────────────────────────

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Offline downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .build()
        val nid = NOTIFICATION_BASE + (inputData.getString(KEY_ID)?.hashCode() ?: 0).and(0xFFFF)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(nid, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(nid, notification)
        }
    }

    companion object {
        const val KEY_ID = "offline_id"
        private const val TAG = "OfflineDownload"
        private const val CHANNEL = "arvio_offline_downloads"
        private const val NOTIFICATION_BASE = 0x4F000
        private const val MAX_ATTEMPTS = 5
        private const val CHUNK = 40
        private const val CONTEXT = 8
    }
}
