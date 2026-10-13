package com.arflix.tv.ui.screens.player.subtitles.bilingual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartBilingualPlannerTest {

    private val en = SubtitleCandidate("3", "eng", "English", isEmbedded = true, isBitmap = false, isForced = false, tapTrackId = 3)
    private val enSdh = SubtitleCandidate("4", "eng", "English SDH", isEmbedded = true, isBitmap = false, isForced = false, tapTrackId = 4)
    private val zhHans = SubtitleCandidate("5", "chi", "简体", isEmbedded = true, isBitmap = false, isForced = false, tapTrackId = 5)
    private val zhHant = SubtitleCandidate("6", "zh-TW", "繁體", isEmbedded = true, isBitmap = false, isForced = false, tapTrackId = 6)
    private val zhPlain = SubtitleCandidate("7", "chi", "Chinese", isEmbedded = true, isBitmap = false, isForced = false, tapTrackId = 7)
    private val zhPgs = SubtitleCandidate("8", "chi", "简体 PGS", isEmbedded = true, isBitmap = true, isForced = false, tapTrackId = null)
    private val ja = SubtitleCandidate("9", "jpn", "日本語", isEmbedded = true, isBitmap = false, isForced = false, tapTrackId = 9)
    private val enForced = SubtitleCandidate("10", "eng", "English Forced", isEmbedded = true, isBitmap = false, isForced = true, tapTrackId = 10)
    private val addonZh = SubtitleCandidate("addon-1", "zh-CN", "zh addon", isEmbedded = false, isBitmap = false, isForced = false)

    private fun input(
        vararg c: SubtitleCandidate,
        primary: String = "auto",
        secondary: SubtitleLanguage = SubtitleLanguage.SIMPLIFIED_CHINESE,
        audio: String? = "en",
        selected: String? = null,
        pinned: Boolean = false,
        ai: Boolean = true,
        unreliable: Set<String> = emptySet(),
    ) = SmartBilingualPlanner.Input(c.toList(), primary, secondary, audio, selected, pinned, ai, unreliable)

    @Test fun `English and Simplified Chinese embedded - merge without AI`() {
        val plan = SmartBilingualPlanner.plan(input(en, zhHans, ai = true))
        plan as SmartPlan.MergeEmbedded
        assertEquals("3", plan.primary.id)
        assertEquals("5", plan.secondary.id)
        assertEquals(false, plan.ambiguousScript)
    }

    @Test fun `both languages present still merge when no AI key is configured`() {
        assertTrue(SmartBilingualPlanner.plan(input(en, zhHans, ai = false)) is SmartPlan.MergeEmbedded)
    }

    @Test fun `only English - AI translation into the secondary language`() {
        val plan = SmartBilingualPlanner.plan(input(en, ai = true))
        plan as SmartPlan.AiTranslate
        assertEquals("3", plan.primary.id)
        assertEquals("zh-Hans", plan.target.tag)
        assertEquals(BilingualNotice.Kind.AI_NO_SECONDARY_TRACK, plan.why)
    }

    @Test fun `only English and no AI - primary only with a clear reason`() {
        val plan = SmartBilingualPlanner.plan(input(en, ai = false))
        plan as SmartPlan.PrimaryOnly
        assertEquals(BilingualNotice.Kind.PRIMARY_ONLY_NO_AI, plan.why)
    }

    @Test fun `Traditional track is not used for Simplified - AI fills in instead`() {
        val plan = SmartBilingualPlanner.plan(input(en, zhHant))
        assertTrue(plan is SmartPlan.AiTranslate)
        // ...but it is exactly right when the user asks for Traditional.
        val t = SmartBilingualPlanner.plan(input(en, zhHant, secondary = SubtitleLanguage.TRADITIONAL_CHINESE))
        assertEquals("6", (t as SmartPlan.MergeEmbedded).secondary.id)
    }

    @Test fun `exact script beats a track that only says Chinese, which is a flagged last resort`() {
        val plan = SmartBilingualPlanner.plan(input(en, zhPlain, zhHans)) as SmartPlan.MergeEmbedded
        assertEquals("5", plan.secondary.id)
        val amb = SmartBilingualPlanner.plan(input(en, zhPlain)) as SmartPlan.MergeEmbedded
        assertEquals("7", amb.secondary.id)
        assertTrue(amb.ambiguousScript)
    }

    @Test fun `image subtitle in the secondary language cannot be merged`() {
        val plan = SmartBilingualPlanner.plan(input(en, zhPgs)) as SmartPlan.AiTranslate
        assertEquals(BilingualNotice.Kind.AI_SECONDARY_IMAGE_ONLY, plan.why)
    }

    @Test fun `unreliable secondary falls back to AI, or primary only without AI`() {
        val ai = SmartBilingualPlanner.plan(input(en, zhHans, unreliable = setOf("5"))) as SmartPlan.AiTranslate
        assertEquals(BilingualNotice.Kind.AI_SECONDARY_UNRELIABLE, ai.why)
        val noAi = SmartBilingualPlanner.plan(input(en, zhHans, ai = false, unreliable = setOf("5"))) as SmartPlan.PrimaryOnly
        assertEquals(BilingualNotice.Kind.PRIMARY_ONLY_UNRELIABLE_NO_AI, noAi.why)
    }

    @Test fun `auto primary prefers plain over SDH and never a forced track`() {
        val plan = SmartBilingualPlanner.plan(input(enForced, enSdh, en, zhHans)) as SmartPlan.MergeEmbedded
        assertEquals("3", plan.primary.id)
    }

    @Test fun `auto primary follows the audio language`() {
        val plan = SmartBilingualPlanner.plan(input(en, ja, zhHans, audio = "ja")) as SmartPlan.MergeEmbedded
        assertEquals("9", plan.primary.id)
    }

    @Test fun `explicit languages - Japanese plus Simplified, English plus Japanese`() {
        val p1 = SmartBilingualPlanner.plan(input(en, ja, zhHans, primary = "ja")) as SmartPlan.MergeEmbedded
        assertEquals("9" to "5", p1.primary.id to p1.secondary.id)
        val p2 = SmartBilingualPlanner.plan(input(en, ja, zhHans, primary = "en", secondary = SubtitleLanguage.parse("ja")!!)) as SmartPlan.MergeEmbedded
        assertEquals("3" to "9", p2.primary.id to p2.secondary.id)
    }

    @Test fun `same primary and secondary language is refused with a notice`() {
        val plan = SmartBilingualPlanner.plan(input(en, zhHans, primary = "zh-Hans"))
        plan as SmartPlan.PrimaryOnly
        assertEquals(BilingualNotice.Kind.SAME_LANGUAGE, plan.why)
    }

    @Test fun `a subtitle the user picked stays the primary`() {
        val plan = SmartBilingualPlanner.plan(input(en, enSdh, zhHans, selected = "4", pinned = true)) as SmartPlan.MergeEmbedded
        assertEquals("4", plan.primary.id)
    }

    @Test fun `addon subtitles are not used as the merged secondary`() {
        assertTrue(SmartBilingualPlanner.plan(input(en, addonZh)) is SmartPlan.AiTranslate)
    }

    @Test fun `no text subtitle at all`() {
        val plan = SmartBilingualPlanner.plan(input(zhPgs, ai = true))
        assertEquals(BilingualNotice.Kind.NO_TEXT_SUBTITLE, (plan as SmartPlan.Unavailable).why)
    }
}
