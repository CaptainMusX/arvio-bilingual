package com.arflix.tv.offline

/** Pure SRT helpers for offline downloads (unit-tested). */
object OfflineSubtitles {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    fun timestamp(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1_000) % 60
        val milli = t % 1_000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(timestamp(c.startMs)).append(" --> ").append(timestamp(c.endMs)).append('\n')
            append(c.text.trim()).append("\n\n")
        }
    }

    /**
     * Normalises extracted cues: sorted, blank lines dropped, a missing / non-positive end filled
     * from the next cue (capped at [maxDurationMs]), exact duplicates merged.
     */
    fun normalise(raw: List<Triple<Long, Long?, String>>, maxDurationMs: Long = 6_000L): List<Cue> {
        val sorted = raw.filter { it.third.isNotBlank() }
            .distinctBy { it.first to it.third.trim() }
            .sortedBy { it.first }
        val out = ArrayList<Cue>(sorted.size)
        for ((i, r) in sorted.withIndex()) {
            val next = sorted.drop(i + 1).firstOrNull { it.first > r.first }?.first
            var end = r.second?.takeIf { it > r.first } ?: (next ?: (r.first + 2_000L))
            end = end.coerceAtMost(r.first + maxDurationMs)
            if (next != null && r.second == null) end = end.coerceAtMost(next)
            if (end <= r.first) end = r.first + 1_000L
            val cue = Cue(r.first, end, r.third.trim())
            val last = out.lastOrNull()
            if (last != null && last.startMs == cue.startMs && last.text == cue.text) continue
            out.add(cue)
        }
        return out
    }

    /** Bilingual SRT: each cue shows the original line(s), then the translation. */
    fun bilingual(cues: List<Cue>, translations: List<String?>): List<Cue> = cues.mapIndexed { i, c ->
        val tr = translations.getOrNull(i)?.trim().orEmpty()
        if (tr.isBlank() || tr == c.text.trim()) c else c.copy(text = c.text.trim() + "\n" + tr)
    }

    fun translatedOnly(cues: List<Cue>, translations: List<String?>): List<Cue> = cues.mapIndexed { i, c ->
        c.copy(text = translations.getOrNull(i)?.trim()?.takeIf { it.isNotBlank() } ?: c.text)
    }

    /** A safe file name for a title. */
    fun safeName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().take(80).ifBlank { "video" }
}
