package com.arflix.tv.ui.screens.player.subtitles.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAiProviderTest {

    private val chat = CustomAiConfig(true, AiProtocol.OPENAI_CHAT, "https://api.deepseek.com/v1/", "sk-x", "deepseek-chat")

    @Test fun `endpoint is appended to a base url and kept when already complete`() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", chat.endpointUrl())
        assertEquals("https://api.openai.com/v1/responses",
            CustomAiConfig.resolveEndpoint("https://api.openai.com/v1", AiProtocol.OPENAI_RESPONSES))
        assertEquals("https://api.anthropic.com/v1/messages",
            CustomAiConfig.resolveEndpoint("https://api.anthropic.com/v1", AiProtocol.ANTHROPIC))
        assertEquals("https://x.example/openai/chat/completions",
            CustomAiConfig.resolveEndpoint("https://x.example/openai/chat/completions", AiProtocol.ANTHROPIC))
    }

    @Test fun `completeness - key optional only for local servers`() {
        assertTrue(chat.isActive)
        assertFalse(chat.copy(apiKey = "").isComplete)
        assertTrue(chat.copy(apiKey = "", baseUrl = "http://192.168.1.5:11434/v1").isComplete)
        assertFalse(chat.copy(model = "").isComplete)
        assertFalse(chat.copy(enabled = false).isActive)
    }

    @Test fun `request bodies per protocol`() {
        val c = CustomAiWire.buildBody(chat, "SYS", "USER", true)
        assertEquals("deepseek-chat", c.getString("model"))
        assertEquals("system", c.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("USER", c.getJSONArray("messages").getJSONObject(1).getString("content"))

        val r = CustomAiWire.buildBody(chat.copy(protocol = AiProtocol.OPENAI_RESPONSES), "SYS", "USER", true)
        assertEquals("SYS", r.getString("instructions"))
        assertEquals("USER", r.getString("input"))

        val a = CustomAiWire.buildBody(chat.copy(protocol = AiProtocol.ANTHROPIC, temperature = ""), "SYS", "USER", true)
        assertEquals("SYS", a.getString("system"))
        assertTrue(a.getInt("max_tokens") > 0)
        assertFalse(a.has("temperature"))
    }

    @Test fun `auth headers per protocol, extra headers override`() {
        assertTrue(CustomAiWire.headers(chat).contains("Authorization" to "Bearer sk-x"))
        val anth = CustomAiWire.headers(chat.copy(protocol = AiProtocol.ANTHROPIC, extraHeaders = "anthropic-version: 2024-01-01\nX-Test: 1"))
        assertTrue(anth.contains("x-api-key" to "sk-x"))
        assertTrue(anth.contains("anthropic-version" to "2024-01-01"))
        assertEquals(1, anth.count { it.first.equals("anthropic-version", true) })
        assertTrue(anth.contains("X-Test" to "1"))
    }

    @Test fun `response text extraction for the three APIs`() {
        assertEquals("[\"0: 你好\"]", CustomAiWire.extractText(AiProtocol.OPENAI_CHAT,
            """{"choices":[{"message":{"role":"assistant","content":"<think>x</think>[\"0: 你好\"]"}}]}"""))
        assertEquals("ok", CustomAiWire.extractText(AiProtocol.OPENAI_RESPONSES, """{"output_text":"ok"}"""))
        assertEquals("ok2", CustomAiWire.extractText(AiProtocol.OPENAI_RESPONSES,
            """{"output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"ok2"}]}]}"""))
        assertEquals("hi", CustomAiWire.extractText(AiProtocol.ANTHROPIC,
            """{"content":[{"type":"thinking","thinking":"..."},{"type":"text","text":"hi"}]}"""))
        assertNull(CustomAiWire.extractText(AiProtocol.ANTHROPIC, """{"content":[]}"""))
        assertEquals("bad key", CustomAiWire.errorMessage("""{"error":{"message":"bad key","type":"auth"}}"""))
    }

    @Test fun `config round-trips through storage encoding`() {
        val c = chat.copy(temperature = "", extraHeaders = "A: b", maxTokens = 2048, protocol = AiProtocol.ANTHROPIC)
        assertEquals(c, CustomAiConfigStore.decode(CustomAiConfigStore.encode(c)))
        assertEquals(CustomAiConfig(), CustomAiConfigStore.decode("not json"))
    }
}
