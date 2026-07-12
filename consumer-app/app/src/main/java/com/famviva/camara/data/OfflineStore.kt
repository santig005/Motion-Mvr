package com.famviva.camara.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.famviva.camara.media.ClipActions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local on-device cache of clip files for offline playback. Lives in app-private storage (cleared
 * on uninstall, no storage permission needed) — a deliberate cache, distinct from
 * [ClipActions.saveToGallery], which is a user-triggered export to the public gallery.
 */
/** How aggressively new clips are auto-downloaded for offline playback. */
enum class AutoDownloadMode {
    /** Never auto-download. */
    OFF,

    /** Only on an unmetered network (Wi-Fi) — can't silently burn mobile data. */
    WIFI_ONLY,

    /** On any connection, including mobile data (the user accepts the data cost). */
    WIFI_AND_DATA,
}

class OfflineStore(private val context: Context) {
    private val dir = File(context.filesDir, "offline_clips").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("offline_store", Context.MODE_PRIVATE)

    /** Auto-download policy. Migrates the old boolean flag (true -> Wi-Fi only) on first read. */
    var autoDownloadMode: AutoDownloadMode
        get() {
            prefs.getString(KEY_MODE, null)?.let { stored ->
                return runCatching { AutoDownloadMode.valueOf(stored) }.getOrDefault(AutoDownloadMode.OFF)
            }
            return if (prefs.getBoolean(KEY_AUTO_LEGACY, false)) AutoDownloadMode.WIFI_ONLY else AutoDownloadMode.OFF
        }
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    fun localFile(clip: Clip): File = File(dir, clip.name)

    fun isDownloaded(clip: Clip): Boolean = localFile(clip).let { it.exists() && it.length() > 0L }

    fun totalSizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(clip: Clip) { localFile(clip).delete() }

    /** Frees all offline copies (device only — the Drive originals are untouched). */
    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }

    private fun activeCaps(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.getNetworkCapabilities(cm.activeNetwork)
    }

    /** True on an unmetered network (Wi-Fi) — the WIFI_ONLY policy only runs on it, so it can't
     *  silently burn the user's mobile data. */
    fun isOnUnmeteredNetwork(): Boolean =
        activeCaps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    /** True on any network with internet (Wi-Fi or mobile data). */
    private fun isOnline(): Boolean =
        activeCaps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    /** Whether auto-download is allowed to run right now, given the mode and the current network. */
    fun shouldAutoDownloadNow(): Boolean = when (autoDownloadMode) {
        AutoDownloadMode.OFF -> false
        AutoDownloadMode.WIFI_ONLY -> isOnUnmeteredNetwork()
        AutoDownloadMode.WIFI_AND_DATA -> isOnline()
    }

    /** Streams [clip] to local storage, atomically (.part + rename on success) so a half-finished
     *  download is never mistaken for a complete one. No-op (true) if already downloaded.
     *  Per-clip-locked: the ViewModel's own auto-download and NewClipsWorker's background poll can
     *  both decide to fetch the same new clip around the same time, and two writers to the same
     *  ".part" path would otherwise interleave into a corrupt file. */
    suspend fun download(clip: Clip, token: String): Boolean {
        if (isDownloaded(clip)) return true
        return downloadLocks.getOrPut(clip.name) { Mutex() }.withLock {
            if (isDownloaded(clip)) return@withLock true   // finished by the other caller while we waited
            withContext(Dispatchers.IO) {
                val part = File(dir, "${clip.name}.part")
                val streamed = runCatching { FileOutputStream(part).use { ClipActions.streamTo(clip.streamUrl, token, it) } }
                    .getOrDefault(false)
                val done = streamed && part.renameTo(localFile(clip))
                if (!done) { part.delete() }
                done
            }
        }
    }

    private companion object {
        const val KEY_MODE = "auto_download_mode"
        const val KEY_AUTO_LEGACY = "auto_download_enabled"   // pre-3-state boolean, migrated on read
        // Process-wide (not per-OfflineStore-instance): NewClipsWorker and the app's own ViewModel
        // each construct their own OfflineStore, so the lock has to live above both.
        val downloadLocks = ConcurrentHashMap<String, Mutex>()
    }
}
