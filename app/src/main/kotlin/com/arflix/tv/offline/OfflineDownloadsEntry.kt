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
import androidx.compose.material.icons.filled.Folder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
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

/** Settings → Downloads: download location + the offline downloads list (play, pause, retry, delete). */
@Composable
fun OfflineDownloadsPage() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { OfflineLibrary.load(context) }
    val items by OfflineLibrary.items.collectAsState()
    var currentRoot by remember { mutableStateOf(OfflineStorage.customRoot(context)?.absolutePath) }
    var showPicker by remember { mutableStateOf(false) }
    var pendingPath by remember { mutableStateOf<String?>(null) }

    fun apply(path: String?) {
        if (path == null) {
            OfflineStorage.setCustomRoot(context, null)
            currentRoot = null
            return
        }
        val dir = java.io.File(path)
        if (OfflineStorage.isWritable(dir)) {
            OfflineStorage.setCustomRoot(context, dir.absolutePath)
            currentRoot = dir.absolutePath
            android.widget.Toast.makeText(context, bt("Download location updated"), android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(
                context,
                bt("This folder isn't writable. Try a folder inside Download or Documents."),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingPath
        pendingPath = null
        if (granted && p != null) apply(p)
        else if (p != null) android.widget.Toast.makeText(context, bt("Storage permission denied"), android.widget.Toast.LENGTH_SHORT).show()
    }

    fun choose(path: String?) {
        showPicker = false
        val needsPermission = path != null &&
            android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q &&
            !path.contains("/Android/data/${context.packageName}") &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingPath = path
            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            apply(path)
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = OfflineStorage.pathFromTreeUri(uri)
        if (path == null) {
            android.widget.Toast.makeText(context, bt("Please choose a folder on this device's storage"), android.widget.Toast.LENGTH_LONG).show()
        } else {
            choose(path)
        }
    }

    MobileSettingsCategory(title = bt("DOWNLOAD LOCATION")) {
        MobileSettingsRow(
            icon = Icons.Default.Folder,
            title = bt("Download location"),
            subtitle = currentRoot ?: OfflineLibrary.root(context).absolutePath,
            value = if (currentRoot == null) bt("App storage (default)") else bt("Custom"),
            isToggle = false,
            showDivider = false,
            onClick = { showPicker = true }
        )
    }
    Text(
        bt("New downloads go to this folder; existing downloads stay where they are. App storage is removed when the app is uninstalled."),
        color = Color.White.copy(alpha = 0.5f), fontSize = 11.5.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )

    PlaybackCacheSection()

    MobileSettingsCategory(title = bt("OFFLINE DOWNLOADS")) {
        if (items.isEmpty()) {
            Text(
                bt("Nothing downloaded yet. While playing, open More Settings (⋯) → Download for offline."),
                color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items.forEach { item ->
                    androidx.compose.runtime.key(item.id) {
                        OfflineItemCard(item, onPlay = { OfflineLibrary.requestPlay(item) })
                    }
                }
            }
        }
    }

    if (showPicker) {
        val options = remember { OfflineStorage.suggestions(context) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = Color(0xFF161A26),
            title = { Text(bt("Download location"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { opt ->
                        val selected = opt.path == currentRoot
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = if (selected) 0.16f else 0.06f))
                                .clickable { choose(opt.path) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(bt(opt.label), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(opt.detail, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (currentRoot != null && options.none { it.path == currentRoot }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.16f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(bt("Custom"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(currentRoot ?: "", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable {
                                showPicker = false
                                runCatching { treeLauncher.launch(null) }.onFailure {
                                    android.widget.Toast.makeText(context, bt("No folder picker on this device"), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text(bt("Choose folder…"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showPicker = false }) {
                    Text(bt("Cancel"), color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
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


/** Settings → Downloads → Playback cache: how much already-watched video is kept for instant rewinds. */
@Composable
private fun PlaybackCacheSection() {
    val context = LocalContext.current
    val cache = com.arflix.tv.player.cache.PlaybackCache
    var limitGb by remember { mutableStateOf(cache.limitGb(context)) }
    var usedBytes by remember { mutableStateOf(0L) }
    var maxBytes by remember { mutableStateOf(0L) }
    var showDialog by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cache.get(context)
            usedBytes = cache.usedBytes()
            maxBytes = cache.maxAllowedBytes(context)
        }
    }
    val effective = minOf(limitGb.toLong() shl 30, maxBytes.takeIf { it > 0 } ?: (limitGb.toLong() shl 30))
    MobileSettingsCategory(title = bt("PLAYBACK CACHE")) {
        MobileSettingsRow(
            icon = Icons.Default.Download,
            title = bt("Playback cache size"),
            subtitle = bt("Used") + " " + sizeText(usedBytes, -1).ifBlank { "0 MB" } +
                if (effective < (limitGb.toLong() shl 30)) "  · " + bt("limited by free space to") + " " + sizeText(effective, -1) else "",
            value = "$limitGb GB",
            isToggle = false,
            showDivider = false,
            onClick = { refresh++; showDialog = true }
        )
    }
    Text(
        bt("Everything you watch is kept here, so rewinding to any part you've already seen plays from the device instead of downloading again. It never grows beyond the free space."),
        color = Color.White.copy(alpha = 0.5f), fontSize = 11.5.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    if (showDialog) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Color(0xFF161A26),
            title = { Text(bt("Playback cache size"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        bt("Available for the cache") + ": " + sizeText(maxBytes, -1),
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                    )
                    com.arflix.tv.player.cache.PlaybackCache.CHOICES_GB.forEach { gb ->
                        val fits = maxBytes <= 0 || (gb.toLong() shl 30) <= maxBytes
                        val selected = gb == limitGb
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = if (selected) 0.16f else 0.06f))
                                .clickable(enabled = fits) {
                                    limitGb = gb
                                    showDialog = false
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        cache.setLimitGb(context, gb)
                                        usedBytes = cache.usedBytes()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "$gb GB" + if (!fits) "  (" + bt("not enough free space") + ")" else "",
                                color = Color.White.copy(alpha = if (fits) 1f else 0.35f),
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable {
                                showDialog = false
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    cache.clear(context)
                                    usedBytes = cache.usedBytes()
                                }
                                android.widget.Toast.makeText(context, bt("Playback cache cleared"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text(bt("Clear playback cache"), color = Color(0xFFFF8A80), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showDialog = false }) {
                    Text(bt("Cancel"), color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}
