package com.arflix.tv.ui.screens.player.subtitles.bilingual

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.arflix.tv.ui.i18n.M3Text as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.ui.screens.player.subtitles.SubtitleTranslationService
import com.arflix.tv.ui.screens.player.subtitles.ai.AiPresets
import com.arflix.tv.ui.screens.player.subtitles.ai.AiProtocol
import com.arflix.tv.ui.screens.player.subtitles.ai.CustomAiConfigStore
import kotlinx.coroutines.launch

private val CInk = Color(0xFFF2F2F2)
private val CInkDim = Color(0xFFA8A8A8)
private val CCard = Color(0x22FFFFFF)
private val CAccent = Color(0xFFF4D979)
private val COk = Color(0xFF8BE28B)
private val CErr = Color(0xFFFF8A80)

/**
 * "Custom AI model" settings: any OpenAI-compatible Chat Completions, OpenAI Responses or
 * Anthropic Messages endpoint, with its own URL, key and model. When on, it replaces Gemini/Groq
 * for subtitle translation everywhere (normal AI translation and bilingual mode).
 */
@Composable
fun CustomAiSection(targetLanguageName: String) {
    val context = LocalContext.current
    val store = remember { CustomAiConfigStore.from(context) }
    val config by store.config.collectAsState()
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(config.enabled) }
    var showKey by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(bt("Custom AI model").uppercase(), color = CInkDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        Row(
            modifier = Modifier.fillMaxWidth().clickable { store.update { it.copy(enabled = !it.enabled) }; expanded = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(bt("Use a custom model for translation"), color = CInk, fontSize = 13.sp)
                Text(
                    if (config.isActive) "${config.model} · ${protocolName(config.protocol)}"
                    else bt("OpenAI Chat / Responses / Anthropic compatible APIs"),
                    color = if (config.isActive) CAccent else CInkDim, fontSize = 11.sp
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { on -> store.update { it.copy(enabled = on) }; if (on) expanded = true },
                colors = SwitchDefaults.colors(checkedTrackColor = CAccent, checkedThumbColor = Color.Black)
            )
        }
        if (config.enabled && !config.isComplete) {
            Text(bt("Fill in the API URL, model and key — until then Gemini/Groq is used."), color = CErr, fontSize = 11.sp)
        }
        if (!expanded) {
            Text(
                bt("Edit custom model"),
                color = CAccent, fontSize = 12.5.sp,
                modifier = Modifier.clickable { expanded = true }.padding(vertical = 4.dp)
            )
            return@Column
        }

        Text(bt("Preset"), color = CInk, fontSize = 13.sp)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiPresets.all.forEach { preset ->
                Chip(text = preset.name, selected = false) {
                    store.update { it.copy(protocol = preset.protocol, baseUrl = preset.baseUrl, model = preset.model) }
                    testState = null
                }
            }
        }

        Text(bt("Protocol"), color = CInk, fontSize = 13.sp)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProtocol.entries.forEach { p ->
                Chip(text = protocolName(p), selected = config.protocol == p) {
                    store.update { it.copy(protocol = p) }; testState = null
                }
            }
        }

        Field(bt("API URL"), config.baseUrl, placeholder = "https://api.openai.com/v1", keyboard = KeyboardType.Uri) { v ->
            store.update { it.copy(baseUrl = v.trim()) }; testState = null
        }
        if (config.baseUrl.isNotBlank()) {
            Text(bt("Requests go to: %s").format(config.endpointUrl()), color = CInkDim, fontSize = 10.5.sp)
        }
        Field(
            bt("API key"), config.apiKey, placeholder = "sk-…", keyboard = KeyboardType.Password,
            visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = { Text(if (showKey) bt("Hide") else bt("Show"), color = CAccent, fontSize = 12.sp, modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)) }
        ) { v -> store.update { it.copy(apiKey = v.trim()) }; testState = null }
        Field(bt("Model"), config.model, placeholder = "gpt-4.1-mini / deepseek-chat / claude-…") { v ->
            store.update { it.copy(model = v.trim()) }; testState = null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                Field(bt("Temperature (blank = default)"), config.temperature, placeholder = "0.2", keyboard = KeyboardType.Decimal) { v ->
                    store.update { it.copy(temperature = v.trim()) }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                Field(bt("Max output tokens"), config.maxTokens.toString(), placeholder = "4096", keyboard = KeyboardType.Number) { v ->
                    v.trim().toIntOrNull()?.let { n -> store.update { it.copy(maxTokens = n.coerceIn(256, 65536)) } }
                }
            }
        }
        Field(bt("Extra headers (optional, one \"Name: value\" per line)"), config.extraHeaders, placeholder = "HTTP-Referer: https://example.com", singleLine = false) { v ->
            store.update { it.copy(extraHeaders = v) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CAccent.copy(alpha = if (config.isComplete && !testing) 0.25f else 0.08f))
                    .clickable(enabled = config.isComplete && !testing) {
                        testing = true
                        testState = null
                        scope.launch {
                            val service = SubtitleTranslationService(apiKeyProvider = { "" })
                            val result = service.testCustom(store.current, targetLanguageName.ifBlank { "Simplified Chinese" })
                            testing = false
                            testState = if (result.success) true to (result.lines.firstOrNull() ?: "")
                            else false to friendlyError(result.errorMessage)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(if (testing) bt("Testing…") else bt("Test connection"), color = CAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                bt("Collapse"), color = CInkDim, fontSize = 12.5.sp,
                modifier = Modifier.clickable { expanded = false }.padding(6.dp)
            )
        }
        testState?.let { (ok, msg) ->
            Text(
                if (ok) bt("Works: \"Winter is coming.\" → %s").format(msg) else bt("Failed: %s").format(msg),
                color = if (ok) COk else CErr, fontSize = 12.sp
            )
        }
        Text(
            bt("The key is stored only on this device. Translation and subtitle matching use this model while it is on; \"Sync by hearing\" still needs a Gemini key."),
            color = CInkDim, fontSize = 11.sp
        )
    }
}

private fun protocolName(p: AiProtocol): String = when (p) {
    AiProtocol.OPENAI_CHAT -> "OpenAI Chat Completions"
    AiProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    AiProtocol.ANTHROPIC -> "Anthropic Messages"
}

private fun friendlyError(raw: String?): String = when {
    raw == null -> bt("Unknown error")
    raw == "RATE_LIMITED" -> bt("Rate limited (HTTP 429) — wait a moment or check your quota")
    raw.startsWith("HTTP 401") || raw.startsWith("HTTP 403") -> bt("Key rejected (%s)").format(raw.take(120))
    raw.startsWith("HTTP 404") -> bt("Not found — check the API URL and model name (%s)").format(raw.take(120))
    else -> raw.take(200)
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) CAccent else CCard)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (selected) Color.Black else CInk, fontSize = 12.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, fontSize = 12.sp, color = CInkDim) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrect = false),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = CInk,
            unfocusedTextColor = CInk,
            focusedBorderColor = CAccent,
            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            focusedLabelColor = CAccent,
            unfocusedLabelColor = CInkDim,
            cursorColor = CAccent,
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
