package com.arflix.tv.ui.screens.player.subtitles.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Wire protocol of a user-configured translation model. */
enum class AiProtocol(val id: String) {
    /** OpenAI-compatible `POST …/chat/completions` (OpenAI, DeepSeek, Qwen/DashScope, SiliconFlow, OpenRouter, Groq, Ollama, LM Studio…). */
    OPENAI_CHAT("chat"),
    /** OpenAI Responses API `POST …/responses`. */
    OPENAI_RESPONSES("responses"),
    /** Anthropic Messages API `POST …/messages`. */
    ANTHROPIC("anthropic");

    val endpointSuffix: String
        get() = when (this) {
            OPENAI_CHAT -> "/chat/completions"
            OPENAI_RESPONSES -> "/responses"
            ANTHROPIC -> "/messages"
        }

    companion object {
        fun of(id: String?): AiProtocol = entries.firstOrNull { it.id == id || it.name == id } ?: OPENAI_CHAT
    }
}

/**
 * A custom translation model: any endpoint speaking one of the three protocols. When [enabled]
 * and complete, it replaces the built-in Gemini/Groq models for subtitle translation and line
 * matching. (Gemini "sync by hearing" still needs a Gemini key — it streams audio.)
 */
data class CustomAiConfig(
    val enabled: Boolean = false,
    val protocol: AiProtocol = AiProtocol.OPENAI_CHAT,
    /** e.g. https://api.openai.com/v1 — or a full endpoint URL ending in the protocol path. */
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** Blank = not sent (some reasoning models reject temperature). */
    val temperature: String = "0.2",
    /** Optional extra request headers, one `Name: value` per line. */
    val extraHeaders: String = "",
    /** Max output tokens (Anthropic requires one; others only when > 0). */
    val maxTokens: Int = 4096,
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() &&
            (apiKey.isNotBlank() || isLocalUrl(baseUrl))

    val isActive: Boolean get() = enabled && isComplete

    /** The URL actually called. */
    fun endpointUrl(): String = resolveEndpoint(baseUrl, protocol)

    fun headerPairs(): List<Pair<String, String>> = extraHeaders.lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim().takeIf { it.isNotEmpty() }?.let { it to line.substring(i + 1).trim() }
    }

    fun temperatureOrNull(): Double? = temperature.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    companion object {
        fun resolveEndpoint(baseUrl: String, protocol: AiProtocol): String {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isEmpty()) return ""
            val known = listOf("/chat/completions", "/responses", "/messages")
            if (known.any { base.endsWith(it) }) return base
            return base + protocol.endpointSuffix
        }

        fun isLocalUrl(url: String): Boolean {
            val u = url.lowercase()
            return u.contains("://localhost") || u.contains("://127.") || u.contains("://10.") ||
                u.contains("://192.168.") || Regex("://172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(u)
        }
    }
}

/** Ready-made starting points; the user can edit every field afterwards. */
data class AiPreset(val name: String, val protocol: AiProtocol, val baseUrl: String, val model: String)

object AiPresets {
    val all = listOf(
        AiPreset("OpenAI (Chat)", AiProtocol.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-4.1-mini"),
        AiPreset("OpenAI (Responses)", AiProtocol.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-4.1-mini"),
        AiPreset("Anthropic Claude", AiProtocol.ANTHROPIC, "https://api.anthropic.com/v1", "claude-3-5-haiku-latest"),
        AiPreset("DeepSeek", AiProtocol.OPENAI_CHAT, "https://api.deepseek.com/v1", "deepseek-chat"),
        AiPreset("Qwen (DashScope)", AiProtocol.OPENAI_CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        AiPreset("SiliconFlow", AiProtocol.OPENAI_CHAT, "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-72B-Instruct"),
        AiPreset("Moonshot Kimi", AiProtocol.OPENAI_CHAT, "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        AiPreset("Zhipu GLM", AiProtocol.OPENAI_CHAT, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        AiPreset("OpenRouter", AiProtocol.OPENAI_CHAT, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        AiPreset("Groq", AiProtocol.OPENAI_CHAT, "https://api.groq.com/openai/v1", "llama-3.1-8b-instant"),
        AiPreset("Gemini (OpenAI compatible)", AiProtocol.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        AiPreset("Ollama (local)", AiProtocol.OPENAI_CHAT, "http://192.168.1.2:11434/v1", "qwen2.5:7b"),
    )
}

/** Device-local storage of the custom model (not cloud-synced: it holds an API key). */
class CustomAiConfigStore private constructor(private val prefs: SharedPreferences) {
    private val _config = MutableStateFlow(read())
    val config: StateFlow<CustomAiConfig> = _config.asStateFlow()

    val current: CustomAiConfig get() = _config.value

    fun update(transform: (CustomAiConfig) -> CustomAiConfig) {
        val next = transform(_config.value)
        prefs.edit().putString(KEY, encode(next)).apply()
        _config.value = next
    }

    private fun read(): CustomAiConfig = decode(prefs.getString(KEY, null))

    companion object {
        private const val PREFS = "arvio_bilingual_custom_ai"
        private const val KEY = "config_v1"
        @Volatile private var instance: CustomAiConfigStore? = null

        fun from(context: Context): CustomAiConfigStore = instance ?: synchronized(this) {
            instance ?: CustomAiConfigStore(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ).also { instance = it }
        }

        fun encode(c: CustomAiConfig): String = JSONObject().apply {
            put("enabled", c.enabled)
            put("protocol", c.protocol.id)
            put("baseUrl", c.baseUrl)
            put("apiKey", c.apiKey)
            put("model", c.model)
            put("temperature", c.temperature)
            put("extraHeaders", c.extraHeaders)
            put("maxTokens", c.maxTokens)
        }.toString()

        fun decode(raw: String?): CustomAiConfig {
            if (raw.isNullOrBlank()) return CustomAiConfig()
            return runCatching {
                val o = JSONObject(raw)
                CustomAiConfig(
                    enabled = o.optBoolean("enabled", false),
                    protocol = AiProtocol.of(o.optString("protocol")),
                    baseUrl = o.optString("baseUrl"),
                    apiKey = o.optString("apiKey"),
                    model = o.optString("model"),
                    temperature = o.optString("temperature", "0.2"),
                    extraHeaders = o.optString("extraHeaders"),
                    maxTokens = o.optInt("maxTokens", 4096).coerceIn(256, 65536),
                )
            }.getOrDefault(CustomAiConfig())
        }
    }
}

/** Request bodies and response parsing for the three protocols (pure, unit-tested). */
object CustomAiWire {
    fun buildBody(c: CustomAiConfig, system: String, user: String, jsonMode: Boolean): JSONObject = when (c.protocol) {
        AiProtocol.OPENAI_CHAT -> JSONObject().apply {
            put("model", c.model)
            c.temperatureOrNull()?.let { put("temperature", it) }
            if (c.maxTokens > 0 && c.maxTokens != 4096) put("max_tokens", c.maxTokens)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }
        AiProtocol.OPENAI_RESPONSES -> JSONObject().apply {
            put("model", c.model)
            put("instructions", system)
            put("input", user)
            c.temperatureOrNull()?.let { put("temperature", it) }
            if (c.maxTokens > 0) put("max_output_tokens", c.maxTokens)
            put("store", false)
        }
        AiProtocol.ANTHROPIC -> JSONObject().apply {
            put("model", c.model)
            put("max_tokens", if (c.maxTokens > 0) c.maxTokens else 4096)
            c.temperatureOrNull()?.let { put("temperature", it.coerceIn(0.0, 1.0)) }
            put("system", system)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", user))
            })
        }
    }

    fun headers(c: CustomAiConfig): List<Pair<String, String>> {
        val base = when (c.protocol) {
            AiProtocol.ANTHROPIC -> listOfNotNull(
                c.apiKey.takeIf { it.isNotBlank() }?.let { "x-api-key" to it },
                "anthropic-version" to "2023-06-01",
            )
            else -> listOfNotNull(c.apiKey.takeIf { it.isNotBlank() }?.let { "Authorization" to "Bearer $it" })
        }
        val extra = c.headerPairs()
        return base.filter { b -> extra.none { it.first.equals(b.first, ignoreCase = true) } } + extra
    }

    /** The model's text, or null when the response carries none. */
    fun extractText(protocol: AiProtocol, body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when (protocol) {
            AiProtocol.OPENAI_CHAT -> {
                val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
                when (val content = msg.opt("content")) {
                    is String -> content
                    is JSONArray -> buildString {
                        for (i in 0 until content.length()) {
                            val part = content.optJSONObject(i) ?: continue
                            if (part.optString("type") in setOf("text", "output_text", "")) append(part.optString("text"))
                        }
                    }
                    else -> null
                }?.let(::stripThinking)?.takeIf { it.isNotBlank() }
            }
            AiProtocol.OPENAI_RESPONSES -> {
                o.optString("output_text").takeIf { it.isNotBlank() }?.let { return stripThinking(it) }
                val out = o.optJSONArray("output") ?: return null
                buildString {
                    for (i in 0 until out.length()) {
                        val item = out.optJSONObject(i) ?: continue
                        if (item.optString("type") != "message") continue
                        val parts = item.optJSONArray("content") ?: continue
                        for (j in 0 until parts.length()) {
                            val p = parts.optJSONObject(j) ?: continue
                            if (p.optString("type") == "output_text" || p.optString("type") == "text") append(p.optString("text"))
                        }
                    }
                }.let(::stripThinking).takeIf { it.isNotBlank() }
            }
            AiProtocol.ANTHROPIC -> {
                val parts = o.optJSONArray("content") ?: return null
                buildString {
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (p.optString("type") == "text") append(p.optString("text"))
                    }
                }.let(::stripThinking).takeIf { it.isNotBlank() }
            }
        }
    }

    /** A short reason from an error body (`{"error":{"message":…}}` in all three APIs). */
    fun errorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(160)
        val err = o.opt("error")
        return when (err) {
            is JSONObject -> err.optString("message").ifBlank { err.toString() }
            is String -> err
            else -> o.optString("message").ifBlank { body.take(160) }
        }.take(200)
    }

    /** Reasoning models served through chat endpoints sometimes inline `<think>…</think>`. */
    fun stripThinking(text: String): String =
        text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
}
