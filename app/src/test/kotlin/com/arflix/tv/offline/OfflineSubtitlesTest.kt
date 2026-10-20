package com.arflix.tv.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSubtitlesTest {
    @Test fun timestampFormatsHoursMinutesSecondsMillis() {
        assertEquals("00:00:00,000", OfflineSubtitles.timestamp(0))
        assertEquals("01:02:03,045", OfflineSubtitles.timestamp(3_723_045))
    }

    @Test fun srtNumbersCuesFromOne() {
        val srt = OfflineSubtitles.toSrt(listOf(OfflineSubtitles.Cue(1_000, 2_500, "Hello"), OfflineSubtitles.Cue(3_000, 4_000, "World")))
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:02,500\nHello\n\n2\n"))
    }

    @Test fun normaliseFillsMissingEndsFromNextCueAndDropsBlanksAndDuplicates() {
        val cues = OfflineSubtitles.normalise(
            listOf(
                Triple(5_000L, null, "B"),
                Triple(1_000L, null, "A"),
                Triple(1_000L, null, "A"),
                Triple(7_000L, 6_000L, " "),
                Triple(20_000L, null, "C"),
            )
        )
        assertEquals(listOf("A", "B", "C"), cues.map { it.text })
        assertEquals(5_000L, cues[0].endMs)           // up to the next cue
        assertEquals(11_000L, cues[1].endMs)          // capped at 6 s
        assertTrue(cues.all { it.endMs > it.startMs })
    }

    @Test fun bilingualStacksTranslationUnderOriginalAndKeepsUntranslated() {
        val cues = listOf(OfflineSubtitles.Cue(0, 1, "Hi"), OfflineSubtitles.Cue(1, 2, "Bye"), OfflineSubtitles.Cue(2, 3, "OK"))
        val out = OfflineSubtitles.bilingual(cues, listOf("你好", null, "OK"))
        assertEquals("Hi\n你好", out[0].text)
        assertEquals("Bye", out[1].text)
        assertEquals("OK", out[2].text)
        assertEquals(listOf("你好", "Bye", "OK"), OfflineSubtitles.translatedOnly(cues, listOf("你好", null, "OK")).map { it.text })
    }

    @Test fun safeNameStripsPathCharacters() {
        val n = OfflineSubtitles.safeName("Game: of/Thrones? S2E8")
        assertFalse(n.contains('/') || n.contains(':') || n.contains('?'))
        assertEquals("video", OfflineSubtitles.safeName("   "))
    }
}
