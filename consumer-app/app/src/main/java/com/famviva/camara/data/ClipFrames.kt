package com.famviva.camara.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.famviva.camara.media.ClipActions
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-frame people/vehicle/animal detection over a clip — the accuracy upgrade over classifying a
 * single image.
 *
 * WHY: Drive's thumbnail (and the NVR's preview jpg) is ONE frame; anyone who only appears for part of
 * a clip is invisible to a one-frame classifier, which is the dominant false negative ("a video with
 * someone in it that never got the 👤"). Sampling several frames evenly across the clip and unioning
 * the detections catches them — a PERSON in ANY sampled frame labels the whole clip PERSON.
 *
 * WHERE: this runs on the consumer phone (a capable, usually-charging Pixel), never on the old
 * battery-constrained NVR phone. The clips are tiny (a few hundred KB of motion-only H.264), so
 * fetching the whole mp4 to read frames is cheap; an already-downloaded offline copy is reused for
 * free. Inference is a few dozen ms per frame — even 16 frames is well under a second.
 *
 * FAIL-OPEN: every failure path (no model, no local video, unreadable/corrupt clip, every frame
 * failing to decode) returns null so the caller falls open — to the single thumbnail, and ultimately
 * to letting the clip through the alert gate. The classifier is a filter over noise, never a
 * gatekeeper over intrusions. See [ClipClassifier] and [passesLabelGate].
 */
object ClipFrames {

    /**
     * Classify a whole clip by sampling [count] frames evenly across it. Returns the most important
     * bucket seen in any frame, or null (fail-open) when there's no model, no local video to read, or
     * every frame failed.
     *
     * Source: an already-downloaded offline copy is used as-is (zero network). Otherwise, when
     * [allowDownload], the clip is streamed to a cache temp and deleted afterward. Backfill passes
     * allowDownload=false so a purely-local retro pass never turns into hundreds of downloads — it
     * only upgrades clips whose mp4 is already on the phone and falls back to the thumbnail for the rest.
     */
    suspend fun classifyClip(
        context: Context,
        clip: Clip,
        token: String,
        offline: OfflineStore,
        classifier: ClipClassifier,
        count: Int,
        allowDownload: Boolean,
    ): ClipLabel? = withContext(Dispatchers.IO) {
        if (!classifier.available) return@withContext null

        val existing = offline.localFile(clip).takeIf { it.exists() && it.length() > 0L }
        val file: File
        val isTemp: Boolean
        when {
            existing != null -> { file = existing; isTemp = false }
            allowDownload -> {
                val tmpDir = File(context.cacheDir, "detect").apply { mkdirs() }
                val tmp = File(tmpDir, clip.name)
                val ok = runCatching {
                    FileOutputStream(tmp).use { ClipActions.streamTo(clip.streamUrl, token, it) }
                }.getOrDefault(false)
                if (!ok || tmp.length() == 0L) { tmp.delete(); return@withContext null }
                file = tmp; isTemp = true
            }
            else -> return@withContext null
        }

        try {
            classifyFile(file, classifier, count)
        } finally {
            if (isTemp) file.delete()
        }
    }

    /**
     * Classify a clip already on disk by sampling [count] frames — the network-free core reused by
     * both [classifyClip] and the backfill's offline-copy path. null when there's no model or no frame
     * could be read/detected.
     */
    fun classifyFile(file: File, classifier: ClipClassifier, count: Int): ClipLabel? {
        if (!classifier.available) return null
        val names = ArrayList<String>()
        var anyOk = false
        forEachFrame(file, count) { bmp ->
            classifier.detectNames(bmp)?.let { names += it; anyOk = true }
        }
        return if (anyOk) ClipLabel.summarize(names) else null
    }

    /**
     * Decode up to [count] frames evenly spaced across the clip and hand each to [onFrame], recycling
     * it right after (callers must not retain the bitmap — only one is held in memory at a time, so a
     * 2K frame's ~14 MB never multiplies by [count]).
     *
     * Uses OPTION_CLOSEST rather than OPTION_CLOSEST_SYNC on purpose: these clips have a long GOP
     * (~12 s keyframe spacing), so snapping each sample to the nearest keyframe would collapse most of
     * them onto the SAME frame — defeating the whole point. OPTION_CLOSEST returns the actual frame at
     * each timestamp (a cheap decode from the prior keyframe on clips this short). On API < 27, where
     * OPTION_CLOSEST doesn't exist, it degrades to the nearest keyframe, which still beats one frame.
     */
    private fun forEachFrame(file: File, count: Int, onFrame: (Bitmap) -> Unit) {
        val n = count.coerceAtLeast(1)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durMs == null || durMs <= 0L) return
            val option = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                MediaMetadataRetriever.OPTION_CLOSEST
            } else {
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            }
            for (i in 0 until n) {
                // Centre each sample in its 1/n slice so we never land exactly on the 0/end edges
                // (an empty first frame or a black tail frame would waste an inference).
                val frac = (i + 0.5) / n
                val timeUs = (durMs * 1000.0 * frac).toLong()
                val bmp = runCatching { mmr.getFrameAtTime(timeUs, option) }.getOrNull() ?: continue
                try { onFrame(bmp) } finally { bmp.recycle() }
            }
        } catch (_: Exception) {
            // Unreadable / corrupt clip -> no frames; the caller falls open to the thumbnail.
        } finally {
            runCatching { mmr.release() }
        }
    }
}
