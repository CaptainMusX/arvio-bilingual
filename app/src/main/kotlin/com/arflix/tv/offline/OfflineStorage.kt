package com.arflix.tv.offline

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * ARVIO Bilingual — where offline downloads are written.
 *
 * Files are written with plain file I/O (resumable range downloads, sidecar SRTs the player
 * opens by path), so a location is only accepted after a probe shows the app can create both a
 * video-like file and a subtitle file there.
 */
object OfflineStorage {
    private const val PREFS = "arvio_offline_storage"
    private const val KEY_ROOT = "root_path"
    const val PUBLIC_FOLDER_NAME = "ARVIO Bilingual"

    data class Location(val label: String, val path: String?, val detail: String)

    fun customRoot(context: Context): File? =
        prefs(context).getString(KEY_ROOT, null)?.takeIf { it.isNotBlank() }?.let { File(it) }

    /** null = app storage (default). */
    fun setCustomRoot(context: Context, path: String?) {
        prefs(context).edit().apply {
            if (path.isNullOrBlank()) remove(KEY_ROOT) else putString(KEY_ROOT, path)
        }.apply()
    }

    /** Suggested locations: app storage, each removable volume's app folder, the public Download folder. */
    fun suggestions(context: Context): List<Location> {
        val app = context.applicationContext
        val out = mutableListOf<Location>()
        out += Location("App storage (default)", null, OfflineLibrary.root(app).absolutePath)
        app.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, dir ->
            val f = File(dir, "offline")
            out += Location(if (i == 0) "SD card / USB storage" else "SD card / USB storage ${i + 1}", f.absolutePath, f.absolutePath)
        }
        @Suppress("DEPRECATION")
        val dl = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_FOLDER_NAME)
        out += Location("Download folder", dl.absolutePath, dl.absolutePath)
        return out
    }

    /** True when the app can create, write and delete files (video and subtitle names) in [dir]. */
    fun isWritable(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        if (!dir.isDirectory) return false
        listOf(".arvio_probe", ".arvio_probe.srt").all { name ->
            val f = File(dir, name)
            f.writeText("ok")
            val ok = f.isFile && f.length() == 2L
            f.delete()
            ok
        }
    }.getOrDefault(false)

    /**
     * Turns a folder picked with the system folder picker (ACTION_OPEN_DOCUMENT_TREE) into a file
     * path, e.g. `primary:Movies/Shows` → `/storage/emulated/0/Movies/Shows`. Null for providers
     * that are not local storage (cloud drives etc.).
     */
    fun pathFromTreeUri(uri: Uri): String? = runCatching {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val volume = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        if (rel.isBlank()) base else "$base/$rel"
    }.getOrNull()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
