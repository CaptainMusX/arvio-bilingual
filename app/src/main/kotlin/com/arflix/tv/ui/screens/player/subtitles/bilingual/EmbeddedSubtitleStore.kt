package com.arflix.tv.ui.screens.player.subtitles.bilingual

import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/** A text subtitle track seen inside the playing container (MKV/MP4/WebM), selected or not. */
data class EmbeddedTextTrack(
    /** Extractor track id (MKV TrackNumber / MP4 track id) — stable for one file. */
    val trackId: Int,
    val formatId: String?,
    val languageTag: String?,
    val label: String?,
    val mimeType: String?,
    /** Original subtitle MIME when the extractor transcoded to application/x-media3-cues. */
    val codecs: String?,
    val isForced: Boolean = false,
) {
    val language: SubtitleLanguage? get() = SubtitleLanguage.ofTrack(languageTag, label)

    /** Image subtitles (PGS, VobSub, DVB) carry no text and can never be merged or translated. */
    val isBitmap: Boolean
        get() = listOf(mimeType, codecs).any { m ->
            m != null && (m.contains("pgs", true) || m.contains("vobsub", true) || m.contains("dvbsubs", true))
        }
}

/**
 * Second-subtitle text store fed by [SubtitleTextTapExtractorsFactory].
 *
 * Media3 decodes only the one selected text track, but the extractor demuxes every track of the
 * container anyway (it has to walk past their blocks). The tap copies the few bytes of each
 * subtitle sample on that existing pass — no second player, no second video decoder, no extra
 * network request — and this store keeps the decoded cue text per track, so the bilingual layer
 * can look up the *unselected* track's lines, ahead of the playhead.
 *
 * Scoped to one source: [beginSource] with a different key (new stream, new episode) drops
 * everything. Thread-safe (written on the loader thread, read on main / playback threads).
 */
class EmbeddedSubtitleStore {

    private class TrackData(var info: EmbeddedTextTrack) {
        /** startMs → cues starting then (usually one). */
        val cues = TreeMap<Long, MutableList<TimedText>>()
        var count = 0
        /** Last cue whose end time was unknown (closed by the next cue). */
        var openCue: TimedText? = null
    }

    private val lock = Any()
    private var sourceKey: String? = null
    private val tracks = LinkedHashMap<Int, TrackData>()
    private val versionCounter = AtomicLong(0)

    /** Increments on every change; cheap staleness check for cached alignments. */
    val version: Long get() = versionCounter.get()

    fun currentSourceKey(): String? = synchronized(lock) { sourceKey }

    /** Starts (or keeps) the store for [key]. A different key clears all tracks and cues. */
    fun beginSource(key: String?): Unit = synchronized(lock) {
        if (key != sourceKey) {
            sourceKey = key
            tracks.clear()
            versionCounter.incrementAndGet()
        }
    }

    fun clear(): Unit = synchronized(lock) {
        tracks.clear()
        sourceKey = null
        versionCounter.incrementAndGet()
    }

    /** Ignored unless [forSource] is the current source (late writes from a previous stream). */
    fun onTrack(forSource: String?, info: EmbeddedTextTrack): Unit = synchronized(lock) {
        if (forSource != sourceKey) return
        val existing = tracks[info.trackId]
        if (existing == null) tracks[info.trackId] = TrackData(info) else existing.info = info
        versionCounter.incrementAndGet()
    }

    /**
     * Adds one decoded cue. [endMs] null = unknown (closed by the next cue of the track, or capped).
     * Duplicates (the same span re-demuxed after a seek) are dropped.
     */
    fun onCue(forSource: String?, trackId: Int, startMs: Long, endMs: Long?, text: String): Unit = synchronized(lock) {
        if (forSource != sourceKey) return
        val track = tracks[trackId] ?: return
        val clean = text.trim()
        // An empty cue in "replace" style formats only ends the previous one.
        track.openCue?.let { open ->
            if (startMs > open.startMs) {
                replace(track, open, open.copy(endMs = minOf(open.endMs, startMs)))
            }
            track.openCue = null
        }
        if (clean.isEmpty()) { versionCounter.incrementAndGet(); return }
        val end = endMs?.takeIf { it > startMs } ?: (startMs + UNKNOWN_END_CAP_MS)
        val cue = TimedText(startMs, end, clean)
        val bucket = track.cues.getOrPut(startMs) { mutableListOf() }
        if (bucket.any { it.text == clean }) return
        if (track.count >= MAX_CUES_PER_TRACK) {
            // Bound memory on very long files: drop the oldest cue.
            val first = track.cues.firstEntry()
            if (first != null) {
                first.value.removeAt(0)
                if (first.value.isEmpty()) track.cues.remove(first.key)
                track.count--
            }
        }
        bucket += cue
        track.count++
        if (endMs == null) track.openCue = cue
        versionCounter.incrementAndGet()
    }

    private fun replace(track: TrackData, old: TimedText, new: TimedText) {
        val bucket = track.cues[old.startMs] ?: return
        val i = bucket.indexOf(old)
        if (i >= 0) bucket[i] = new
    }

    fun tracks(): List<EmbeddedTextTrack> = synchronized(lock) { tracks.values.map { it.info } }

    fun track(trackId: Int): EmbeddedTextTrack? = synchronized(lock) { tracks[trackId]?.info }

    fun cueCount(trackId: Int): Int = synchronized(lock) { tracks[trackId]?.count ?: 0 }

    /** Cues of [trackId] that overlap [fromMs]..[toMs]. */
    fun cues(trackId: Int, fromMs: Long = Long.MIN_VALUE, toMs: Long = Long.MAX_VALUE): List<TimedText> =
        synchronized(lock) {
            val t = tracks[trackId] ?: return emptyList()
            // A cue that started up to MAX_CUE_LOOKBACK before the window may still overlap it.
            val from = if (fromMs == Long.MIN_VALUE) Long.MIN_VALUE else fromMs - MAX_CUE_LOOKBACK_MS
            t.cues.subMap(from, true, toMs, true).values.flatten().filter { it.endMs >= fromMs && it.startMs <= toMs }
        }

    companion object {
        const val UNKNOWN_END_CAP_MS = 4_000L
        private const val MAX_CUE_LOOKBACK_MS = 30_000L
        private const val MAX_CUES_PER_TRACK = 20_000
    }
}
