package com.arflix.tv.util

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ARVIO Bilingual: runtime translation of UI text that upstream hard-codes in English.
 *
 * Upstream localizes only part of its UI through string resources; many labels, badges, section
 * headers, toasts and menu entries are English literals in Kotlin. Every app [Text] goes through
 * [com.arflix.tv.ui.i18n] wrappers which call [t]: an exact (or pattern) lookup in a reviewed
 * dictionary shipped in `assets/i18n/<lang>.json`. Text that is not in the dictionary — titles,
 * names, user data — is returned unchanged, so nothing outside the curated UI phrases is touched.
 */
object UiI18n {
    @Volatile private var appContext: Context? = null
    @Volatile private var loadedFor: String? = null
    @Volatile private var exact: Map<String, String> = emptyMap()
    @Volatile private var exactFolded: Map<String, String> = emptyMap()
    @Volatile private var patterns: List<Pattern> = emptyList()
    private val memo = ConcurrentHashMap<String, String>()

    private class Pattern(val regex: Regex, val template: String, val groups: Int)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Dictionary file for the current UI language, or null for English / unsupported. */
    internal fun dictionaryFor(locale: Locale): String? {
        if (locale.language != "zh") return null
        val traditional = locale.script.equals("Hant", true) ||
            (locale.script.isBlank() && locale.country.uppercase() in setOf("TW", "HK", "MO"))
        return if (traditional) "zh-TW" else "zh-CN"
    }

    /** The app language tag ("zh-CN", "en-US"…) last seen by the Compose tree; null = use the default locale. */
    @Volatile var currentTag: String? = null

    private fun ensureLoaded(): Boolean {
        val want = dictionaryFor(currentTag?.let { appLocale(it) } ?: Locale.getDefault())
        if (want == loadedFor) return want != null
        synchronized(this) {
            if (want == loadedFor) return want != null
            memo.clear()
            if (want == null) {
                exact = emptyMap(); exactFolded = emptyMap(); patterns = emptyList()
            } else {
                load(want)
            }
            loadedFor = want
        }
        return want != null
    }

    private fun load(lang: String) {
        val ctx = appContext ?: return
        val json = runCatching {
            ctx.assets.open("i18n/$lang.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return
        parse(json)
    }

    /** Unit tests: install a dictionary for [tag] without Android assets. */
    internal fun loadForTest(tag: String, json: String) {
        synchronized(this) {
            memo.clear()
            currentTag = tag
            parse(json)
            loadedFor = dictionaryFor(appLocale(tag))
        }
    }

    private fun parse(json: String) {
        val root = JSONObject(json)
        val ex = HashMap<String, String>()
        root.optJSONObject("exact")?.let { o -> o.keys().forEach { k -> ex[k] = o.getString(k) } }
        exact = ex
        exactFolded = ex.entries.associate { fold(it.key) to it.value }
        val pats = ArrayList<Pattern>()
        root.optJSONArray("patterns")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                compile(pair.getString(0), pair.getString(1))?.let(pats::add)
            }
        }
        // Longest (most specific) patterns first.
        patterns = pats.sortedByDescending { it.regex.pattern.length }
    }

    /** "Subtitles: {0}" → ^Subtitles: (.+?)$ */
    private fun compile(source: String, template: String): Pattern? {
        val parts = Regex("\\{(\\d)}").split(source)
        if (parts.joinToString("").count { it.isLetter() } < 2) return null
        val sb = StringBuilder("^")
        val holders = Regex("\\{(\\d)}").findAll(source).toList()
        parts.forEachIndexed { i, part ->
            sb.append(Regex.escape(part))
            if (i < holders.size) sb.append("(.+?)")
        }
        sb.append("$")
        return runCatching { Pattern(Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL), template, holders.size) }.getOrNull()
    }

    private fun fold(s: String): String = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** Translate a UI string if the dictionary knows it; otherwise return it unchanged. */
    fun t(text: String, languageTag: String?): String {
        if (languageTag != null && languageTag != currentTag) currentTag = languageTag
        return t(text)
    }

    fun t(text: String): String {
        if (text.isEmpty() || !ensureLoaded()) return text
        if (text.length > 400) return text
        memo[text]?.let { return it }
        val result = lookup(text) ?: text
        if (memo.size > 5000) memo.clear()
        memo[text] = result
        return result
    }

    private fun lookup(text: String): String? {
        exact[text]?.let { return it }
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isLetter() }) return null
        // No CJK text needs translating again.
        if (trimmed.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return null
        val lead = text.substring(0, text.indexOf(trimmed))
        val trail = text.substring(text.indexOf(trimmed) + trimmed.length)
        (exact[trimmed] ?: exactFolded[fold(trimmed)])?.let { return lead + it + trail }
        // "Label:" / "Label…" / "Label..." variants of a known label.
        for (suffix in listOf(":", "：", "…", "...")) {
            if (trimmed.endsWith(suffix) && trimmed.length > suffix.length) {
                val base = trimmed.dropLast(suffix.length).trim()
                (exact[base] ?: exactFolded[fold(base)])?.let {
                    return lead + it + (if (suffix == ":") "：" else suffix) + trail
                }
            }
        }
        for (p in patterns) {
            val m = p.regex.matchEntire(trimmed) ?: continue
            var out = p.template
            for (g in 1..p.groups) {
                val captured = m.groupValues[g]
                // Inner words that are themselves UI phrases ("Season", "Movies") are translated too.
                val inner = exact[captured] ?: exactFolded[fold(captured)] ?: captured
                out = out.replace("{${g - 1}}", inner)
            }
            return lead + out + trail
        }
        return null
    }
}
