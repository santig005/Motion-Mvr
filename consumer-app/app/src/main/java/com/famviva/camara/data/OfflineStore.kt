package com.famviva.camara.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.famviva.camara.media.ClipActions
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local on-device cache of clip files for offline playback. Lives in app-private storage (cleared
 * on uninstall, no storage permission needed) — a deliberate cache, distinct from
 * [ClipActions.saveToGallery], which is a user-triggered export to the public gallery.
 */
class OfflineStore(private val context: Context) {
    private val dir = File(context.filesDir, "offline_clips").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("offline_store", Context.MODE_PRIVATE)

    var autoDownloadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    fun localFile(clip: Clip): File = File(dir, clip.name)

    fun isDownloaded(clip: Clip): Boolean = localFile(clip).let { it.exists() && it.length() > 0L }

    fun totalSizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(clip: Clip) { localFile(clip).delete() }

    /** Frees all offline copies (device only — the Drive originals are untouched). */
    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }

    /** True on an unmetered network (Wi-Fi) — auto-download only runs on it, so the feature can't
     *  silently burn the user's mobile data. */
    fun isOnUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Streams [clip] to local storage, atomically (.part + rename on success) so a half-finished
     *  download is never mistaken for a complete one. No-op (true) if already downloaded. */
    suspend fun download(clip: Clip, token: String): Boolean = withContext(Dispatchers.IO) {
        if (isDownloaded(clip)) return@withContext true
        val part = File(dir, "${clip.name}.part")
        val ok = runCatching { FileOutputStream(part).use { ClipActions.streamTo(clip.streamUrl, token, it) } }
            .getOrDefault(false)
        if (ok) part.renameTo(localFile(clip)) else part.delete()
        ok
    }

    private companion object {
        const val KEY_AUTO = "auto_download_enabled"
    }
}
