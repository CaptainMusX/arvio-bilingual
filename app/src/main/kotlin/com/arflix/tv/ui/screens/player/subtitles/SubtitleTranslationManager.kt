package com.arflix.tv.ui.screens.player.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

class SubtitleTranslationManager(
    private var service: SubtitleTranslationService,
    targetLanguage: String,
    private val scope: CoroutineScope
) {
    /**
     * Language the AI translates into (a name, e.g. "Simplified Chinese"). Cached translations
     * are keyed by it, so switching languages never shows a line translated for the old one, and
     * the on-screen frame is invalidated so an in-flight result for the old language is dropped.
     */
    @Volatile
    internal var targetLanguage: String = targetLanguage
        set(value) {
            if (field != value) {
                field = value
                bilingualBus.clear()
            }
        }
    companion object {
        const val MOCK_MODE = false
        // Groq free tier: 30 RPM. Subtitles change every 2-4s naturally (~15-20 RPM).
        // No artificial rate limit needed — just don't fire concurrent requests.
        // If we get a 429, we back off 5s.
        private const val BATCH_WINDOW_MS = 150L  // wait up to 150ms for more items to batch
    }

    var isEnabled: Boolean = false
        set(value) {
            if (field != value) bilingualBus.clear()
            field = value
        }

    // ── ARVIO Bilingual ─────────────────────────────────────────────────────
    /**
     * On-screen AI subtitle frame (source text + translation) consumed by the Compose
     * BilingualSubtitleOverlay. While AI translation is active the renderer publishes here
     * instead of rewriting Media3 cue text.
     */
    val bilingualBus = com.arflix.tv.ui.screens.player.subtitles.bilingual.BilingualCueBus()

    /** What the AI subtitle layer shows. Synced from the persisted settings by PlayerScreen. */
    @Volatile
    var displayMode: com.arflix.tv.ui.screens.player.subtitles.bilingual.BilingualDisplayMode =
        com.arflix.tv.ui.screens.player.subtitles.bilingual.BilingualDisplayMode.BILINGUAL

    /**
     * Where the second language comes from while the bilingual layer is active:
     * AI translation of the primary (path B, ARVIO's existing Gemini/Groq flow), or a second
     * embedded subtitle track of the same file (path A — never calls the translation API).
     */
    enum class BilingualSource { AI_TRANSLATION, EMBEDDED_TRACK }

    @Volatile
    var bilingualSource: BilingualSource = BilingualSource.AI_TRANSLATION
        set(value) {
            if (field != value) bilingualBus.clear()
            field = value
        }

    /** Path A's secondary-line provider (null = primary only). */
    @Volatile
    var secondarySource: com.arflix.tv.ui.screens.player.subtitles.bilingual.DualTrackSecondarySource? = null
        set(value) {
            if (field !== value) bilingualBus.clear()
            field = value
        }

    /** Short user-facing notice about the smart bilingual mode (shown briefly by the overlay). */
    val bilingualNotice = kotlinx.coroutines.flow.MutableStateFlow<com.arflix.tv.ui.screens.player.subtitles.bilingual.BilingualNotice?>(null)

    /** Called by the cue publisher after every path-A lookup (drives the low-confidence fallback). */
    @Volatile
    var onSecondaryLookup: ((com.arflix.tv.ui.screens.player.subtitles.bilingual.DualTrackSecondarySource.Lookup) -> Unit)? = null

    /** True when the current display mode needs translations at all (saves API calls otherwise). */
    val translationNeeded: Boolean
        get() = isEnabled && displayMode.needsTranslation && bilingualSource == BilingualSource.AI_TRANSLATION

    /**
     * Translates [text] through the normal queue/cache and reports whether it really succeeded.
     * On failure the existing queue completes with the source text and does not cache it.
     */
    suspend fun translateDetailed(text: String): Pair<String, Boolean> {
        val lang = targetLanguage
        val result = translate(text, lang)
        val ok = cache.containsKey(cacheKey(lang, text))
        return result to ok
    }

    private fun cacheKey(lang: String, text: String): String = lang + '\u0001' + text
    var removeHearingImpaired: Boolean = true
    @Volatile var removeSubtitleHearingImpaired: Boolean = false

    var onTranslatingChanged: ((Boolean) -> Unit)? = null
    var onBatchResult: ((success: Boolean, error: String?) -> Unit)? = null
    /**
     * Fired when translation is active but the cues carry no extractable text — i.e. the chosen
     * AI source is an image track (PGS/VOBSUB). Metadata alone can't be trusted to catch this
     * (containers report null/generic MIME), so this is the runtime backstop that stops AI from
     * silently rendering the untranslated source language.
     */
    var onUntranslatableSource: (() -> Unit)? = null

    val translatedCount: Int get() = cache.size

    @Volatile var isTranslating: Boolean = false
        private set

    private val cache = ConcurrentHashMap<String, String>()
    // Tracks texts currently queued but not yet translated, to avoid double-queuing the same text
    // from both the real-time path and preTranslateWindow racing on the same cue.
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pendingCount = AtomicInteger(0)
    private var hideTranslatingJob: Job? = null

    private data class PendingItem(val text: String, val lang: String, val deferred: CompletableDeferred<String>)
    private val queue = Channel<PendingItem>(Channel.UNLIMITED)

    init {
        if (!MOCK_MODE) {
            scope.launch { processBatches() }
        }
    }

    fun updateService(apiKey: String, model: SubtitleAiModel) {
        service = SubtitleTranslationService(
            apiKeyProvider = { apiKey },
            modelProvider = { model }
        )
    }

    private suspend fun processBatches() {
        val batch = mutableListOf<PendingItem>()
        while (true) {
            // Block until the first item is available
            val first = queue.receive()
            batch.add(first)

            // Collect any additional items that arrive within BATCH_WINDOW_MS.
            // This handles burst cache misses (e.g., right after a seek) efficiently.
            val deadline = System.currentTimeMillis() + BATCH_WINDOW_MS
            while (batch.size < 40) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                val next = withTimeoutOrNull(remaining) { queue.receive() } ?: break
                batch.add(next)
            }

            // One API request per target language (a language switch mid-batch is rare).
            val lang = batch.first().lang
            val sameLang = batch.filter { it.lang == lang }
            batch.filter { it.lang != lang }.forEach { queue.trySend(it) }
            batch.retainAll(sameLang)
            val texts = batch.map { it.text }
            val result = try {
                service.translateBatch(texts, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationResult(
                    lines = emptyList(),
                    success = false,
                    errorMessage = e.message ?: "Translation error"
                )
            }
            if (!result.success) {
                onBatchResult?.invoke(false, result.errorMessage)
                // On error: complete deferreds with original text so the caller doesn't hang,
                // but do NOT cache — the next render will retry rather than permanently show English.
                batch.forEachIndexed { i, item ->
                    inFlight.remove(cacheKey(item.lang, item.text))
                    item.deferred.complete(item.text)
                }
                batch.clear()
                delay(5_000L)
                continue
            }
            onBatchResult?.invoke(true, null)
            batch.forEachIndexed { i, item ->
                val translated = result.lines.getOrElse(i) { item.text }
                cache[cacheKey(item.lang, item.text)] = translated
                inFlight.remove(cacheKey(item.lang, item.text))
                item.deferred.complete(translated)
            }
            batch.clear()
        }
    }

    /** Cached translation of [text] into the CURRENT target language. */
    fun getCached(text: String): String? = cache[cacheKey(targetLanguage, text)]

    fun isInFlight(text: String): Boolean = inFlight.containsKey(cacheKey(targetLanguage, text))

    suspend fun translate(text: String): String = translate(text, targetLanguage)

    private suspend fun translate(text: String, lang: String): String {
        val key = cacheKey(lang, text)
        cache[key]?.let { return it }
        // If the same text is already queued (e.g. preTranslateWindow raced us), join it.
        inFlight[key]?.let { return it.await() }

        val deferred = CompletableDeferred<String>()
        inFlight[key] = deferred
        val depth = pendingCount.getAndIncrement()
        if (depth == 0) {
            isTranslating = true
            onTranslatingChanged?.invoke(true)
        }
        queue.send(PendingItem(text, lang, deferred))
        return try {
            deferred.await()
        } finally {
            if (pendingCount.decrementAndGet() == 0) {
                hideTranslatingJob?.cancel()
                hideTranslatingJob = scope.launch {
                    delay(1500)
                    if (pendingCount.get() == 0) {
                        isTranslating = false
                        onTranslatingChanged?.invoke(false)
                    }
                }
            }
        }
    }

    fun reset() {
        bilingualBus.clear()
        cache.clear()
        inFlight.clear()
        if (pendingCount.get() == 0) {
            isTranslating = false
            onTranslatingChanged?.invoke(false)
        }
    }

    suspend fun preTranslateWindow(texts: List<String>) {
        // The lookahead triggers fire whenever a text track renders cues — including tracks
        // selected under the hood by "find best match" with AI translation off. Never spend
        // API requests unless translation is actually active.
        if (!isEnabled) return
        // ARVIO Bilingual: "Original only" / "Off" never need translations.
        if (!displayMode.needsTranslation) return
        // Path A (second embedded track) never translates.
        if (bilingualSource != BilingualSource.AI_TRANSLATION) return
        val lang = targetLanguage
        val uncached = texts.filter { !cache.containsKey(cacheKey(lang, it)) && !inFlight.containsKey(cacheKey(lang, it)) }
        if (uncached.isEmpty()) return
        uncached.chunked(40).forEach { chunk ->
            val result = service.translateBatch(chunk, lang)
            if (result.success) {
                onBatchResult?.invoke(true, null)
                chunk.forEachIndexed { i, text ->
                    cache[cacheKey(lang, text)] = result.lines.getOrElse(i) { text }
                }
            } else {
                onBatchResult?.invoke(false, result.errorMessage)
                delay(5_000L)
                return
            }
        }
    }
}
