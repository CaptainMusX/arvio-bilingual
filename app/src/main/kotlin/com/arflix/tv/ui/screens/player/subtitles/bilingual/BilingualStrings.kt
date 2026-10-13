package com.arflix.tv.ui.screens.player.subtitles.bilingual

import java.util.Locale

/**
 * Interface strings of the bilingual feature in the app's UI language. ARVIO sets
 * [Locale.setDefault] to the chosen app language, so lookups follow it without a Context.
 * Generated from tools/bl_dict.py (zh-Hant via OpenCC s2twp); other languages show English.
 */
object BilingualStrings {
    fun isTraditional(l: Locale = Locale.getDefault()): Boolean =
        l.script.equals("Hant", true) || l.country.uppercase() in setOf("TW", "HK", "MO")

    fun get(en: String, locale: Locale = Locale.getDefault()): String {
        if (locale.language != "zh") return en
        val p = T[en] ?: return com.arflix.tv.util.UiI18n.t(en)
        return if (isTraditional(locale)) p.second else p.first
    }

    private val T: Map<String, Pair<String, String>> = mapOf(
        "AI Translate" to Pair("AI 翻译", "AI 翻譯"),
        "AI Translate: always translate the primary track with Gemini/Groq, even if the other language exists. On API errors the primary stays on screen." to Pair("AI 翻译：无论另一种语言字幕是否存在，都用 AI（Gemini/Groq 或自定义模型） 翻译主字幕轨；API 出错时保留主字幕。", "AI 翻譯：無論另一種語言字幕是否存在，都用 AI（Gemini/Groq 或自定义模型） 翻譯主字幕軌；API 出錯時保留主字幕。"),
        "Advanced settings" to Pair("高级设置", "高階設定"),
        "Applies to the bilingual layer (Smart Bilingual or AI Translation). Normal subtitle tracks keep ARVIO's own subtitle style. ARVIO's own \"Secondary subtitle language\" (subtitle search filter) is a different setting." to Pair("仅作用于双语字幕层。普通字幕轨仍使用 ARVIO 自带的字幕样式。ARVIO 自带的“次要字幕语言”（字幕搜索筛选）是另一个设置。", "僅作用於雙語字幕層。普通字幕軌仍使用 ARVIO 自帶的字幕樣式。ARVIO 自帶的“次要字幕語言”（字幕搜尋篩選）是另一個設定。"),
        "Auto" to Pair("自动", "自動"),
        "Auto: merge two existing text subtitles when they line up (no AI); otherwise translate the missing language with Gemini/Groq." to Pair("自动：两条现有文本字幕对齐可靠时直接合并（不调用 AI）；否则用 AI（Gemini/Groq 或自定义模型） 翻译补全缺少的语言。", "自動：兩條現有文字字幕對齊可靠時直接合並（不呼叫 AI）；否則用 AI（Gemini/Groq 或自定义模型） 翻譯補全缺少的語言。"),
        "Automatic" to Pair("自动", "自動"),
        "BILINGUAL SUBTITLES" to Pair("双语字幕", "雙語字幕"),
        "Background box" to Pair("背景框", "背景框"),
        "Basic" to Pair("基础", "基礎"),
        "Bilingual Subtitles" to Pair("双语字幕", "雙語字幕"),
        "Bilingual subtitles on" to Pair("开启双语字幕", "開啟雙語字幕"),
        "Bilingual subtitles" to Pair("双语字幕", "雙語字幕"),
        "Bilingual" to Pair("双语", "雙語"),
        "Bold" to Pair("粗体", "粗體"),
        "Close" to Pair("关闭", "關閉"),
        "Display mode" to Pair("显示模式", "顯示模式"),
        "Extra vertical offset" to Pair("额外垂直偏移", "額外垂直偏移"),
        "Font (tap to change)" to Pair("字体（点按切换）", "字型（點按切換）"),
        "Gap between languages" to Pair("两种语言的间距", "兩種語言的間距"),
        "Generation mode (Auto / Merge / AI Translate), languages, display mode, styles" to Pair("生成模式（自动 / 合并 / AI 翻译）、语言、显示模式、样式", "生成模式（自動 / 合併 / AI 翻譯）、語言、顯示模式、樣式"),
        "Generation mode" to Pair("生成模式", "生成模式"),
        "Group position (from bottom)" to Pair("整体位置（距底部）", "整體位置（距底部）"),
        "Merge" to Pair("合并", "合併"),
        "Merge: only combine existing subtitle tracks. AI is never called — if a language is missing you'll get a notice and can pick tracks below." to Pair("合并：只合并现有字幕轨，绝不调用 AI；缺少某种语言时会提示，并可在下方手动选择字幕轨。", "合併：只合並現有字幕軌，絕不呼叫 AI；缺少某種語言時會提示，並可在下方手動選擇字幕軌。"),
        "No Gemini/Groq API key is set. Add it in Settings → AI Subtitles; until then only the original subtitle can be shown." to Pair("尚未设置 Gemini/Groq API 密钥。请在 设置 → AI 字幕 中添加；在此之前只能显示原文字幕。", "尚未設定 Gemini/Groq API 金鑰。請在 設定 → AI 字幕 中新增；在此之前只能顯示原文字幕。"),
        "Off" to Pair("关", "關"),
        "Outline color" to Pair("描边颜色", "描邊顏色"),
        "Outline" to Pair("描边", "描邊"),
        "Preview" to Pair("预览", "預覽"),
        "Primary and secondary are the same language — only one line would be shown. Pick a different secondary language." to Pair("主语言和第二语言相同，只会显示一行。请选择不同的第二语言。", "主語言和第二語言相同，只會顯示一行。請選擇不同的第二語言。"),
        "Primary subtitle language" to Pair("主字幕语言", "主字幕語言"),
        "Primary track (this video)" to Pair("主字幕轨（本视频）", "主字幕軌（本影片）"),
        "Primary" to Pair("主语言", "主語言"),
        "Reset bilingual style to defaults" to Pair("将双语样式恢复默认", "將雙語樣式恢復預設"),
        "Secondary subtitle language" to Pair("第二字幕语言", "第二字幕語言"),
        "Secondary track to merge (this video)" to Pair("要合并的第二字幕轨（本视频）", "要合併的第二字幕軌（本影片）"),
        "Shadow" to Pair("阴影", "陰影"),
        "Show" to Pair("显示", "顯示"),
        "Shows two languages at once using the Generation mode below. Off = ARVIO's normal subtitles (a subtitle you pick from the normal list is always respected). Display mode further down is separate: it only decides which of the two lines are shown." to Pair("按下方的生成模式同时显示两种语言。关闭时使用 ARVIO 普通字幕（你在普通字幕列表中手动选的字幕始终优先）。下面的显示模式是独立设置，只决定显示两行中的哪几行。", "按下方的生成模式同時顯示兩種語言。關閉時使用 ARVIO 普通字幕（你在普通字幕列表中手動選的字幕始終優先）。下面的顯示模式是獨立設定，只決定顯示兩行中的哪幾行。"),
        "Subtitles off" to Pair("字幕已关闭", "字幕已關閉"),
        "This video has no embedded text subtitle that AI can translate (image subtitles such as PGS can't be read). Pick another source or a normal subtitle track." to Pair("此视频没有可供 AI 翻译的内嵌文本字幕（PGS 等图片字幕无法读取）。请换一个片源或选择普通字幕轨。", "此影片沒有可供 AI 翻譯的內嵌文字字幕（PGS 等圖片字幕無法讀取）。請換一個片源或選擇普通字幕軌。"),
        "Turn on Bilingual AI Translation for this video" to Pair("为本视频开启双语 AI 翻译", "為本影片開啟雙語 AI 翻譯"),
        "Turn on bilingual for this video" to Pair("为本视频开启双语字幕", "為本影片開啟雙語字幕"),
        "%s only" to Pair("仅%s", "僅%s"),
        "%s on top" to Pair("%s在上", "%s在上"),
        "%s size" to Pair("%s字号", "%s字號"),
        "%s color" to Pair("%s颜色", "%s顏色"),
        " (addon)" to Pair("（插件）", "（外掛）"),
        "Bilingual: merging embedded %s subtitles (no AI)" to Pair("双语：正在合并内嵌%s字幕（不调用 AI）", "雙語：正在合併內嵌%s字幕（不呼叫 AI）"),
        "Bilingual: merging %s — the track doesn't say Simplified or Traditional" to Pair("双语：正在合并%s，该字幕轨未标明简体或繁体", "雙語：正在合併%s，該字幕軌未標明簡體或繁體"),
        "Bilingual: no embedded %s track — translating with AI" to Pair("双语：没有内嵌%s字幕轨，改用 AI 翻译", "雙語：沒有內嵌%s字幕軌，改用 AI 翻譯"),
        "Bilingual: embedded %s track doesn't line up — switched to AI translation" to Pair("双语：内嵌%s字幕轨时间对不上，已切换为 AI 翻译", "雙語：內嵌%s字幕軌時間對不上，已切換為 AI 翻譯"),
        "Bilingual: embedded %s track is an image subtitle — translating with AI" to Pair("双语：内嵌%s字幕是图片字幕，改用 AI 翻译", "雙語：內嵌%s字幕是圖片字幕，改用 AI 翻譯"),
        "Bilingual: no embedded %s track and no AI key — showing one language. Add a Gemini/Groq key to translate." to Pair("双语：没有内嵌%s字幕轨，也没有 AI 密钥，只显示一种语言。添加 Gemini/Groq 密钥即可翻译。", "雙語：沒有內嵌%s字幕軌，也沒有 AI 金鑰，只顯示一種語言。新增 Gemini/Groq 金鑰即可翻譯。"),
        "Bilingual: embedded %s track doesn't line up and no AI key is set — showing one language" to Pair("双语：内嵌%s字幕轨时间对不上，且未设置 AI 密钥，只显示一种语言", "雙語：內嵌%s字幕軌時間對不上，且未設定 AI 金鑰，只顯示一種語言"),
        "Bilingual: primary and secondary are both %s — choose a different language in Bilingual settings" to Pair("双语：主语言和第二语言都是%s，请在双语设置中选择不同的语言", "雙語：主語言和第二語言都是%s，請在雙語設定中選擇不同的語言"),
        "Bilingual: this video has no readable text subtitle (image subtitles can't be used)" to Pair("双语：此视频没有可读取的文本字幕（图片字幕无法使用）", "雙語：此影片沒有可讀取的文字字幕（圖片字幕無法使用）"),
        "Bilingual: keeping the subtitle you picked" to Pair("双语：保留你手动选择的字幕", "雙語：保留你手動選擇的字幕"),
        "Merge mode: no embedded %s text track to merge (AI is off in this mode). Pick the tracks in Subtitles → Bilingual Subtitles." to Pair("合并模式：没有可合并的内嵌%s文本字幕（此模式不调用 AI）。请在 字幕 → 双语字幕 中手动选择字幕轨。", "合併模式：沒有可合併的內嵌%s文字字幕（此模式不呼叫 AI）。請在 字幕 → 雙語字幕 中手動選擇字幕軌。"),
        "Merge mode: the %s track doesn't line up with the primary (AI is off in this mode). Pick other tracks in Subtitles → Bilingual Subtitles." to Pair("合并模式：%s字幕轨与主字幕时间对不上（此模式不调用 AI）。请在 字幕 → 双语字幕 中选择其他字幕轨。", "合併模式：%s字幕軌與主字幕時間對不上（此模式不呼叫 AI）。請在 字幕 → 雙語字幕 中選擇其他字幕軌。"),
        "AI Translate mode: translating into %s" to Pair("AI 翻译模式：正在翻译为%s", "AI 翻譯模式：正在翻譯為%s"),
        "AI Translate mode needs a Gemini/Groq key (Settings → AI Subtitles) — showing one language" to Pair("AI 翻译模式需要 Gemini/Groq 密钥（设置 → AI 字幕），当前只显示一种语言", "AI 翻譯模式需要 Gemini/Groq 金鑰（設定 → AI 字幕），當前只顯示一種語言"),
        "%s · merging %s + %s (no AI)" to Pair("%s · 合并 %s + %s（不调用 AI）", "%s · 合併 %s + %s（不呼叫 AI）"),
        "%s · %s + AI %s" to Pair("%s · %s + AI %s", "%s · %s + AI %s"),
        "%s · %s only — %s" to Pair("%s · 仅%s — %s", "%s · 僅%s — %s"),
        "Bilingual subtitles: %s + %s" to Pair("双语字幕：%s + %s", "雙語字幕：%s + %s"),
    )
}

/** Localized bilingual-UI string (see [BilingualStrings]). */
fun bt(en: String): String = BilingualStrings.get(en)
