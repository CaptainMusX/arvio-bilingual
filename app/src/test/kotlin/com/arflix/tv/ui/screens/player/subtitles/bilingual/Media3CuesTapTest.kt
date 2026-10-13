package com.arflix.tv.ui.screens.player.subtitles.bilingual

import android.app.Application
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CueEncoder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Since Media3 1.4 the extractor parses subtitles itself and emits application/x-media3-cues
 * samples (serialised with Bundles, hence Robolectric). The tap must read those too — that is the
 * shape ARVIO's default extractor factory produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3CuesTapTest {
    @Test fun `media3-cues samples decode with timing and text`() {
        val bytes = CueEncoder().encode(
            listOf(Cue.Builder().setText("第一行").build(), Cue.Builder().setText("Second").build()),
            1_500_000L
        )
        val decoder = SubtitleSampleDecoder.forFormat("application/x-media3-cues", "application/x-subrip")!!
        val cue = decoder.decode(42_000_000L, bytes, 0, bytes.size)!!
        assertEquals(DecodedCue(42_000L, 43_500L, "第一行\nSecond"), cue)
    }
}
