package com.arflix.tv.ui.screens.player.subtitles.bilingual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.ui.screens.player.SubtitleFontOption
import kotlin.math.roundToInt

private val Ink = Color(0xFFF2F2F2)
private val InkDim = Color(0xFFA8A8A8)
private val CardBg = Color(0x22FFFFFF)
private val Accent = Color(0xFFF4D979)
private val Warn = Color(0xFFFFB36B)

/**
 * "Bilingual AI Subtitles" settings. Shared by the in-player subtitle sheet and
 * Settings → Audio & Subtitles. Every change is applied live (the overlay listens to the same
 * store) and persisted to the profile's DataStore.
 *
 * @param aiAvailable null when unknown (global settings screen); false shows the
 *   "no embedded text subtitle" hint inside the player.
 */
@Composable
fun BilingualSubtitleSettingsPanel(
    modifier: Modifier = Modifier,
    aiAvailable: Boolean? = null,
    aiTranslating: Boolean = false,
    onEnableAiTranslation: (() -> Unit)? = null,
    showPreview: Boolean = true,
) {
    val context = LocalContext.current
    val store = remember { BilingualSubtitleSettingsStore.from(context) }
    val settings by store.settings.collectAsState()
    val keyConfigured by store.aiKeyConfigured.collectAsState(initial = true)
    var advanced by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!keyConfigured) {
            Hint("No Gemini/Groq API key is set. Add it in Settings → AI Subtitles; until then only the original subtitle can be shown.")
        } else if (aiAvailable == false) {
            Hint("This video has no embedded text subtitle that AI can translate (image subtitles such as PGS can't be read). Pick another source or a normal subtitle track.")
        }
        if (onEnableAiTranslation != null && aiAvailable == true && !aiTranslating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent.copy(alpha = 0.18f))
                    .clickable { onEnableAiTranslation() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Turn on Bilingual AI Translation for this video", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (showPreview) Preview(settings)

        SectionTitle("Display mode")
        ChipRow(
            options = listOf(
                BilingualDisplayMode.BILINGUAL to "Bilingual",
                BilingualDisplayMode.ORIGINAL_ONLY to "Original only",
                BilingualDisplayMode.TRANSLATION_ONLY to "Translation only",
                BilingualDisplayMode.OFF to "Off",
            ),
            selected = settings.mode,
            onSelect = { m -> store.update { it.copy(mode = m) } }
        )

        SectionTitle("Basic")
        ChipRow(
            options = listOf(
                BilingualOrder.ORIGINAL_FIRST to "English on top",
                BilingualOrder.TRANSLATION_FIRST to "Chinese on top",
            ),
            selected = settings.order,
            onSelect = { o -> store.update { it.copy(order = o) } }
        )
        LabeledSlider(
            label = "Gap between languages",
            valueText = "${settings.gapDp} dp",
            value = settings.gapDp.toFloat(),
            range = 0f..BilingualSubtitleSettings.MAX_GAP_DP.toFloat(),
            onChange = { v -> store.update { it.copy(gapDp = v.roundToInt()) } }
        )
        LabeledSlider(
            label = "Group position (from bottom)",
            valueText = "${settings.bottomPercent}%",
            value = settings.bottomPercent.toFloat(),
            range = 0f..BilingualSubtitleSettings.MAX_BOTTOM_PERCENT.toFloat(),
            onChange = { v -> store.update { it.copy(bottomPercent = v.roundToInt()) } }
        )
        LabeledSlider(
            label = "English size",
            valueText = "${settings.original.sizeSp} sp",
            value = settings.original.sizeSp.toFloat(),
            range = BilingualLineStyle.MIN_SIZE_SP.toFloat()..BilingualLineStyle.MAX_SIZE_SP.toFloat(),
            onChange = { v -> store.update { it.copy(original = it.original.copy(sizeSp = v.roundToInt())) } }
        )
        LabeledSlider(
            label = "Chinese size",
            valueText = "${settings.translation.sizeSp} sp",
            value = settings.translation.sizeSp.toFloat(),
            range = BilingualLineStyle.MIN_SIZE_SP.toFloat()..BilingualLineStyle.MAX_SIZE_SP.toFloat(),
            onChange = { v -> store.update { it.copy(translation = it.translation.copy(sizeSp = v.roundToInt())) } }
        )
        ColorPicker(
            label = "English color",
            hex = settings.original.colorHex,
            onPick = { h -> store.update { it.copy(original = it.original.copy(colorHex = h)) } }
        )
        ColorPicker(
            label = "Chinese color",
            hex = settings.translation.colorHex,
            onPick = { h -> store.update { it.copy(translation = it.translation.copy(colorHex = h)) } }
        )

        ToggleLine(
            label = "Advanced settings",
            checked = advanced,
            onToggle = { advanced = it }
        )
        if (advanced) {
            LanguageSection(
                title = "English (original)",
                style = settings.original,
                onChange = { s -> store.update { it.copy(original = s) } }
            )
            LanguageSection(
                title = "Chinese (translation)",
                style = settings.translation,
                onChange = { s -> store.update { it.copy(translation = s) } }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardBg)
                .clickable { store.resetToDefaults() }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("Reset bilingual style to defaults", color = InkDim, fontSize = 13.sp)
        }
        Text(
            "Applies only while AI Translation is on. Normal subtitle tracks keep ARVIO's own subtitle style. " +
                "Secondary Subtitle Language is a different, unrelated setting.",
            color = InkDim,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Preview(settings: BilingualSubtitleSettings) {
    val frame = remember {
        BilingualFrame(
            generation = 1,
            sourceKey = "preview",
            original = "Winter is coming, and we are not ready.",
            translation = "凛冬将至，而我们还没有准备好。",
        )
    }
    val blocks = BilingualComposer.compose(frame, settings)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A4A5C), Color(0xFF1B1F24))))
    ) {
        Text("Preview", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.padding(8.dp))
        if (blocks.isEmpty()) {
            Text(
                "Subtitles off",
                color = InkDim,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            // Positions in the preview are scaled to its small height, sizes are not.
            BilingualSubtitleGroup(
                blocks = blocks,
                settings = settings,
                scale = 0.75f,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
        }
    }
}

@Composable
private fun LanguageSection(title: String, style: BilingualLineStyle, onChange: (BilingualLineStyle) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        ToggleLine("Show", style.visible) { onChange(style.copy(visible = it)) }
        LabeledSlider(
            label = "Extra vertical offset",
            valueText = "${style.offsetDp} dp",
            value = style.offsetDp.toFloat(),
            range = -BilingualLineStyle.MAX_OFFSET_DP.toFloat()..BilingualLineStyle.MAX_OFFSET_DP.toFloat(),
            onChange = { onChange(style.copy(offsetDp = it.roundToInt())) }
        )
        ToggleLine("Bold", style.bold) { onChange(style.copy(bold = it)) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    val all = SubtitleFontOption.entries
                    val idx = all.indexOf(SubtitleFontOption.fromPreference(style.font))
                    onChange(style.copy(font = all[(idx + 1) % all.size].preferenceValue))
                }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Font (tap to change)", color = Ink, fontSize = 13.sp)
            Text(SubtitleFontOption.fromPreference(style.font).preferenceValue, color = Accent, fontSize = 13.sp)
        }
        Text("Outline", color = Ink, fontSize = 13.sp)
        ChipRow(
            options = SubtitleOutline.entries.map { it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            selected = style.outline,
            onSelect = { onChange(style.copy(outline = it)) }
        )
        ColorPicker("Outline color", style.outlineColorHex) { onChange(style.copy(outlineColorHex = it)) }
        ToggleLine("Shadow", style.shadow) { onChange(style.copy(shadow = it)) }
        ToggleLine("Background box", style.background) { onChange(style.copy(background = it)) }
    }
}

@Composable
private fun ColorPicker(label: String, hex: String, onPick: (String) -> Unit) {
    var text by remember(hex) { mutableStateOf(hex) }
    val valid = BilingualColors.normalizeHex(text) != null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Ink, fontSize = 13.sp)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BilingualColors.PRESETS.forEach { (_, preset) ->
                val selected = preset.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(BilingualColors.parseArgb(preset)))
                        .border(if (selected) 3.dp else 1.dp, if (selected) Accent else Color.White.copy(alpha = 0.4f), CircleShape)
                        .clickable { onPick(preset) }
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v.take(9)
                    BilingualColors.normalizeHex(text)?.let(onPick)
                },
                singleLine = true,
                isError = !valid,
                label = { Text("HEX", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = InkDim,
                    focusedLabelColor = Accent,
                    unfocusedLabelColor = InkDim,
                    cursorColor = Accent,
                ),
                modifier = Modifier.width(120.dp)
            )
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSel) Accent else CardBg)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(label, color = if (isSel) Color.Black else Ink, fontSize = 12.5.sp, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Ink, fontSize = 13.sp)
            Text(valueText, color = Accent, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
            modifier = Modifier.height(32.dp)
        )
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Ink, fontSize = 13.sp)
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.Black)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

@Composable
private fun Hint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Warn.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text, color = Warn, fontSize = 12.5.sp)
    }
}
