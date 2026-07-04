package com.famviva.camara.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.famviva.camara.R
import com.famviva.camara.data.Clip
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Download and sharing of clips from Drive (streaming with a Bearer header). */
object ClipActions {
    private val http = OkHttpClient()
    private const val GALLERY_DIR = "MotionNVR"

    /** Dumps the binary at [url] into [out] (does not close [out]). Internal: also used by
     *  OfflineStore to download clips for offline playback. */
    internal suspend fun streamTo(url: String, token: String, out: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) return@withContext false
                body.byteStream().use { it.copyTo(out) }
                true
            }
        }

    /** Downloads the clip to cache and shares it with other apps via FileProvider. */
    suspend fun shareClip(context: Context, clip: Clip, token: String): Boolean {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, clip.name)
        val ok = withContext(Dispatchers.IO) {
            FileOutputStream(file).use { streamTo(clip.streamUrl, token, it) }
        }
        if (!ok) return false
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.chooser_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return true
    }

    /** Saves the clip to the gallery (Movies/MotionNVR). Returns the visible path or null on failure. */
    suspend fun saveToGallery(context: Context, clip: Clip, token: String): String? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, clip.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + GALLERY_DIR)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                val ok = resolver.openOutputStream(uri)?.use { streamTo(clip.streamUrl, token, it) } ?: false
                if (!ok) { resolver.delete(uri, null, null); return@withContext null }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Movies/$GALLERY_DIR/${clip.name}"
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), GALLERY_DIR)
                    .apply { mkdirs() }
                val file = File(dir, clip.name)
                val ok = FileOutputStream(file).use { streamTo(clip.streamUrl, token, it) }
                if (ok) file.absolutePath else null
            }
        }
}
