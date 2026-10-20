package com.arflix.tv.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arflix.tv.ui.components.MobileSettingsCategory
import com.arflix.tv.ui.components.MobileSettingsRow
import com.arflix.tv.ui.screens.player.subtitles.bilingual.bt
import com.arflix.tv.ui.i18n.M3Text as Text

/** Settings → Audio & Subtitles: the offline downloads list (play, pause, retry, delete). */
@Composable
fun OfflineDownloadsEntry() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { OfflineLibrary.load(context) }
    val items by OfflineLibrary.items.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }

    MobileSettingsCategory(title = bt("OFFLINE DOWNLOADS")) {
        MobileSettingsRow(
            icon = Icons.Default.Download,
            title = bt("Offline downloads"),
            subtitle = bt("Videos downloaded from the player, with AI bilingual subtitles"),
            value = items.size.toString(),
            isToggle = false,
            showDivider = false,
            onClick = { open = true }
        )
    }

    if (!open) return
    Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111316))
                .safeDrawingPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(bt("Offline downloads"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, contentDescription = bt("Close"), tint = Color.White,
                        modifier = Modifier.clickable { open = false }.padding(6.dp))
                }
                if (items.isEmpty()) {
                    Text(
                        bt("Nothing downloaded yet. While playing, open More Settings (⋯) → Download for offline."),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        OfflineItemCard(item, onPlay = {
                            open = false
                            OfflineLibrary.requestPlay(item)
                        })
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun OfflineItemCard(item: OfflineItem, onPlay: () -> Unit) {
    val context = LocalContext.current
    val active = item.status in setOf(OfflineItem.STATUS_QUEUED, OfflineItem.STATUS_DOWNLOADING, OfflineItem.STATUS_SUBTITLES, OfflineItem.STATUS_TRANSLATING)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(12.dp)
    ) {
        Text(item.displayTitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.sourceName.isNotBlank()) {
            Text(item.sourceName.lines().first(), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        val statusText = when (item.status) {
            OfflineItem.STATUS_QUEUED -> bt("Queued")
            OfflineItem.STATUS_DOWNLOADING -> bt("Downloading") + "  " + sizeText(item.downloadedBytes, item.totalBytes)
            OfflineItem.STATUS_SUBTITLES -> bt("Preparing subtitles")
            OfflineItem.STATUS_TRANSLATING -> bt("AI translating subtitles") + "  ${item.translatedLines}/${item.totalLines}"
            OfflineItem.STATUS_DONE -> bt("Ready offline") + "  " + sizeText(item.totalBytes, -1) +
                if (item.bilingualSrt != null) "  · " + bt("Bilingual subtitles") else ""
            OfflineItem.STATUS_PAUSED -> bt("Paused") + "  " + sizeText(item.downloadedBytes, item.totalBytes)
            else -> bt("Failed")
        }
        Text(statusText, color = Color.White.copy(alpha = 0.8f), fontSize = 12.5.sp)
        if (item.message.isNotBlank()) {
            Text(bt(item.message), color = Color(0xFFFFB300), fontSize = 11.5.sp)
        }
        val progress = when (item.status) {
            OfflineItem.STATUS_DOWNLOADING, OfflineItem.STATUS_PAUSED ->
                if (item.totalBytes > 0) item.downloadedBytes.toFloat() / item.totalBytes else null
            OfflineItem.STATUS_TRANSLATING -> if (item.totalLines > 0) item.translatedLines.toFloat() / item.totalLines else null
            else -> null
        }
        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.status == OfflineItem.STATUS_DONE) ActionChip(bt("Play"), onPlay)
            if (active) ActionChip(bt("Pause")) { OfflineLibrary.pause(context, item.id) }
            if (item.status == OfflineItem.STATUS_PAUSED || item.status == OfflineItem.STATUS_FAILED ||
                (item.status == OfflineItem.STATUS_DONE && item.totalLines > 0 && item.translatedLines < item.totalLines)
            ) ActionChip(bt("Resume")) { OfflineLibrary.retry(context, item.id) }
            ActionChip(bt("Delete")) { OfflineLibrary.delete(context, item.id) }
        }
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

private fun sizeText(done: Long, total: Long): String {
    fun f(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> "%.0f MB".format(b / (1024.0 * 1024))
        else -> "${b / 1024} KB"
    }
    return if (total > 0) "${f(done)} / ${f(total)}" else if (done > 0) f(done) else ""
}
