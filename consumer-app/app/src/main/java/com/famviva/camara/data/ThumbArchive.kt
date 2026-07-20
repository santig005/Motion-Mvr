package com.famviva.camara.data

import android.content.Context
import com.famviva.camara.media.ClipActions
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local archive of clip thumbnails (the NVR's `mt_*.jpg` previews), so the metadata history stays
 * *visual* even after Drive purges the jpg along with the video. App-private storage (cleared on
 * uninstall, no permission needed); ~106 KB each, ~10 MB/day — the cheap tier. Written atomically
 * (`.part` + rename) like [OfflineStore], so a half-finished download is never mistaken for a good one.
 *
 * Video (Phase B) is the heavy tier and owns retention; thumbnails here are just archived, not yet
 * pruned — a long-horizon prune is a Phase B / settings concern.
 */
class ThumbArchive(context: Context) {
    private val dir = File(context.filesDir, "thumb_archive").apply { mkdirs() }

    private fun fileFor(baseName: String) = File(dir, "$baseName.jpg")

    fun isArchived(baseName: String): Boolean = fileFor(baseName).let { it.exists() && it.length() > 0L }

    /** Absolute path of the archived jpg, or null if it isn't archived yet. */
    fun localPathOrNull(baseName: String): String? =
        fileFor(baseName).takeIf { it.exists() && it.length() > 0L }?.absolutePath

    fun totalSizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Downloads the clip's Drive jpg ([thumbFileId]) into the archive. No-op (true) if already there.
     * Best-effort — returns false on any failure (the history just falls back to the Drive preview
     * while the clip is still up there).
     */
    suspend fun archive(baseName: String, thumbFileId: String, token: String): Boolean {
        if (isArchived(baseName)) return true
        return withContext(Dispatchers.IO) {
            val part = File(dir, "$baseName.jpg.part")
            val url = "https://www.googleapis.com/drive/v3/files/$thumbFileId?alt=media"
            val streamed = runCatching { FileOutputStream(part).use { ClipActions.streamTo(url, token, it) } }
                .getOrDefault(false)
            val done = streamed && part.renameTo(fileFor(baseName))
            if (!done) part.delete()
            done
        }
    }
}
