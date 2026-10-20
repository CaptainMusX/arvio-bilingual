package com.arflix.tv.offline

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Subtitle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * ARVIO Bilingual — offline downloads.
 *
 * One item = one episode / movie: the video file plus, when a subtitle could be prepared, the
 * original subtitle, the AI translation and a ready-made bilingual SRT (original line, then the
 * translation). Everything lives in the app's own external files dir (no storage permission,
 * removed with the app): `<files>/offline/<id>/`. The index is a small JSON file next to it.
 */
data class OfflineItem(
    val id: String,
    val mediaType: String,
    val mediaId: Int,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val sourceName: String = "",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String = "video",
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: String = STATUS_QUEUED,
    val message: String = "",
    /** The subtitle to prepare: an external (addon) subtitle URL, or an embedded track to extract. */
    val subtitleUrl: String? = null,
    val subtitleLang: String = "",
    val subtitleLabel: String = "",
    val embeddedSubtitleLang: String? = null,
    /** AI translation target (English name the translator understands) and its BCP 47 tag. */
    val targetLanguageName: String = "",
    val targetLanguageTag: String = "",
    val originalSrt: String? = null,
    val translatedSrt: String? = null,
    val bilingualSrt: String? = null,
    val translatedLines: Int = 0,
    val totalLines: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val mediaTypeEnum: MediaType get() = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE
    val displayTitle: String
        get() = if (season != null && episode != null) "$title S${season}E${episode}" else title

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_SUBTITLES = "subtitles"
        const val STATUS_TRANSLATING = "translating"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_PAUSED = "paused"
    }
}

object OfflineLibrary {
    private const val TAG = "OfflineLibrary"
    private val gson = Gson()
    private val listType = object : TypeToken<List<OfflineItem>>() {}.type
    private val _items = MutableStateFlow<List<OfflineItem>>(emptyList())
    val items: StateFlow<List<OfflineItem>> = _items.asStateFlow()
    @Volatile private var loaded = false

    /** Asks the app's navigation to play a finished download (see AppNavigation). */
    val playRequests = MutableSharedFlow<OfflineItem>(extraBufferCapacity = 1)

    fun root(context: Context): File =
        File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, "offline").apply { mkdirs() }

    fun dirFor(context: Context, id: String): File = File(root(context), id).apply { mkdirs() }

    private fun indexFile(context: Context) = File(root(context), "index.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _items.value = runCatching {
            val f = indexFile(context)
            if (f.isFile) gson.fromJson<List<OfflineItem>>(f.readText(), listType).orEmpty() else emptyList()
        }.getOrElse { Log.w(TAG, "index unreadable: ${it.message}"); emptyList() }
            // A download that was running when the app died resumes when retried.
            .map { if (it.status in ACTIVE) it.copy(status = OfflineItem.STATUS_PAUSED) else it }
    }

    private val ACTIVE = setOf(OfflineItem.STATUS_QUEUED, OfflineItem.STATUS_DOWNLOADING, OfflineItem.STATUS_SUBTITLES, OfflineItem.STATUS_TRANSLATING)

    fun get(context: Context, id: String): OfflineItem? {
        load(context)
        return _items.value.firstOrNull { it.id == id }
    }

    @Synchronized
    fun upsert(context: Context, item: OfflineItem) {
        load(context)
        _items.value = (_items.value.filterNot { it.id == item.id } + item).sortedByDescending { it.createdAtMs }
        save(context)
    }

    @Synchronized
    fun update(context: Context, id: String, persist: Boolean = true, change: (OfflineItem) -> OfflineItem) {
        load(context)
        _items.value = _items.value.map { if (it.id == id) change(it) else it }
        if (persist) save(context)
    }

    @Synchronized
    private fun save(context: Context) {
        runCatching {
            val f = indexFile(context)
            val tmp = File(f.parentFile, "index.json.tmp")
            tmp.writeText(gson.toJson(_items.value))
            tmp.renameTo(f)
        }.onFailure { Log.w(TAG, "index write failed: ${it.message}") }
    }

    fun idFor(mediaType: String, mediaId: Int, season: Int?, episode: Int?): String =
        "${mediaType}_${mediaId}" + (if (season != null && episode != null) "_s${season}e${episode}" else "")

    /** Queues (or re-queues) a download; a running one for the same episode is replaced. */
    fun enqueue(context: Context, item: OfflineItem) {
        upsert(context, item.copy(status = OfflineItem.STATUS_QUEUED, message = ""))
        startWorker(context, item.id)
    }

    fun retry(context: Context, id: String) {
        update(context, id) { it.copy(status = OfflineItem.STATUS_QUEUED, message = "") }
        startWorker(context, id)
    }

    private fun startWorker(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf(OfflineDownloadWorker.KEY_ID to id))
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    fun pause(context: Context, id: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(id))
        update(context, id) { if (it.status in ACTIVE) it.copy(status = OfflineItem.STATUS_PAUSED) else it }
    }

    fun delete(context: Context, id: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(id))
        runCatching { File(root(context), id).deleteRecursively() }
        synchronized(this) {
            load(context)
            _items.value = _items.value.filterNot { it.id == id }
            save(context)
        }
    }

    fun videoFile(context: Context, item: OfflineItem): File = File(dirFor(context, item.id), item.fileName)

    /** The finished download whose video file is [streamUrl], if any. */
    fun itemForUrl(context: Context, streamUrl: String?): OfflineItem? {
        if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("file:")) return null
        load(context)
        val path = runCatching { Uri.parse(streamUrl).path }.getOrNull() ?: return null
        return _items.value.firstOrNull { videoFile(context, it).absolutePath == path }
    }

    /**
     * Sidecar subtitles of a downloaded video, bilingual first: shown by the player like any
     * other external subtitle, with no network and no AI call.
     */
    fun sidecarsFor(context: Context, streamUrl: String?): List<Subtitle> {
        val item = itemForUrl(context, streamUrl) ?: return emptyList()
        val dir = dirFor(context, item.id)
        val lang = item.subtitleLang.ifBlank { "en" }
        fun sub(name: String?, label: String, suffix: String, subLang: String): Subtitle? {
            val f = name?.let { File(dir, it) }?.takeIf { it.isFile && it.length() > 0 } ?: return null
            return Subtitle(
                id = "offline:${item.id}:$suffix",
                url = Uri.fromFile(f).toString(),
                lang = subLang,
                label = label,
                provider = "Offline",
            )
        }
        val target = item.targetLanguageTag.ifBlank { "zh" }
        return listOfNotNull(
            sub(item.bilingualSrt, com.arflix.tv.ui.screens.player.subtitles.bilingual.bt("Bilingual (offline)"), "bilingual", lang),
            sub(item.originalSrt, com.arflix.tv.ui.screens.player.subtitles.bilingual.bt("Original (offline)"), "original", lang),
            sub(item.translatedSrt, com.arflix.tv.ui.screens.player.subtitles.bilingual.bt("Translation (offline)"), "translated", target),
        )
    }

    fun requestPlay(item: OfflineItem) {
        playRequests.tryEmit(item)
    }

    private fun workName(id: String) = "offline_download_$id"
    private const val WORK_TAG = "offline_download"
}
