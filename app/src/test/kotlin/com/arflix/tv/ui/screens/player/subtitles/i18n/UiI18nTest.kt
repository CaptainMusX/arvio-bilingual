package com.arflix.tv.ui.screens.player.subtitles.i18n

import com.arflix.tv.util.UiI18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** (Lives under subtitles.* so the CI filter runs it.) */
class UiI18nTest {
    private val json = """
        {"exact":{"Sports":"体育","Genre":"类型","English":"英语","Anime":"动漫"},
         "patterns":[["Subtitles: {0}","字幕：{0}"],["{0} Subtitles","{0}字幕"],["Season {0}","第 {0} 季"]]}
    """.trimIndent()

    @After fun tearDown() { UiI18n.loadForTest("en-US", "{}") }

    @Test fun `exact, case-folded and suffix lookups`() {
        UiI18n.loadForTest("zh-CN", json)
        assertEquals("体育", UiI18n.t("Sports"))
        assertEquals("体育", UiI18n.t("SPORTS"))
        assertEquals(" 类型：", UiI18n.t(" Genre:"))
        assertEquals("Breaking Bad", UiI18n.t("Breaking Bad"))
        assertEquals("已经是中文", UiI18n.t("已经是中文"))
    }

    @Test fun `patterns translate known inner words`() {
        UiI18n.loadForTest("zh-CN", json)
        assertEquals("字幕：en", UiI18n.t("Subtitles: en"))
        assertEquals("英语字幕", UiI18n.t("English Subtitles"))
        assertEquals("第 3 季", UiI18n.t("Season 3"))
    }

    @Test fun `english UI is untouched`() {
        UiI18n.loadForTest("en-US", json)
        assertEquals("Sports", UiI18n.t("Sports"))
    }

    @Test fun `shipped dictionaries parse and cover the reported strings`() {
        val dir = listOf(File("src/main/assets/i18n"), File("app/src/main/assets/i18n")).first { it.exists() }
        UiI18n.loadForTest("zh-CN", File(dir, "zh-CN.json").readText())
        assertEquals("体育", UiI18n.t("Sports"))
        assertEquals("动漫", UiI18n.t("Anime"))
        assertEquals("排序", UiI18n.t("Sort"))
        assertEquals("内嵌", UiI18n.t("Embedded"))
        assertEquals("字幕：en", UiI18n.t("Subtitles: en"))
        UiI18n.loadForTest("zh-TW", File(dir, "zh-TW.json").readText())
        assertEquals("體育", UiI18n.t("Sports"))
    }

    /** Android (ICU) throws PatternSyntaxException on a bare '}' that the JVM accepts. */
    @Test
    fun `placeholder regex escapes both braces for Android ICU`() {
        assertEquals("\\{(\\d)\\}", UiI18n.PLACEHOLDER.pattern)
    }
}
