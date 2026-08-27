package com.famviva.camara.data

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.Closeable

/**
 * On-device object detection over a clip's thumbnail — the whole "intelligence" layer of Phase 1.
 *
 * WHY POST-HOC OVER A THUMBNAIL IS ENOUGH (and why this isn't Frigate): the app already downloads
 * every clip's thumbnail, so the classifier's input is on the phone for free, and the pipeline is
 * already ~114 s from content-end to visible-on-Drive — adding ~30 ms of inference is not observable.
 * This NEVER decides what gets recorded (the NVR keeps every clip regardless); it only decides what
 * gets *announced*. A false negative costs an alert, never footage.
 *
 * FAIL-OPEN BY CONSTRUCTION: if the model asset is missing (it is fetched separately — see
 * `consumer-app/download-model.sh`, kept out of git and out of the build so a missing model degrades
 * to "no gating", never a broken build) or inference throws, [classify] returns null and the gate
 * lets the clip through. The model is a filter over noise, not a gatekeeper over intrusions.
 *
 * TFLite (COCO) rather than ML Kit's base model on purpose: only a COCO detector natively separates
 * `person` / `car` / `dog`, which is the point. See [ClipLabel.ofCoco].
 */
class ClipClassifier(context: Context) : Closeable {

    // Built once; null if the model asset isn't on the device or the runtime can't load it. Every
    // caller treats null as "classifier unavailable" and fails open.
    private val detector: ObjectDetector? = runCatching {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(MIN_SCORE)
            .build()
        ObjectDetector.createFromFileAndOptions(context, MODEL_ASSET, options)
    }.getOrNull()

    /** True when a model is actually loaded — lets callers skip the per-clip thumbnail fetches that
     *  only exist to feed a classifier that isn't there. */
    val available: Boolean get() = detector != null

    /**
     * Raw COCO class names (score >= [MIN_SCORE]) the detector finds in one frame, or null if the
     * classifier is unavailable or the frame errored. The shared building block behind both [classify]
     * (a single thumbnail) and the multi-frame clip path in [ClipFrames], which unions these names
     * across several frames before summarizing — so a person seen in ANY frame labels the whole clip.
     */
    fun detectNames(bitmap: Bitmap): List<String>? {
        val d = detector ?: return null
        return runCatching {
            // TensorImage needs ARGB_8888; thumbnails decode to it already, but convert defensively
            // rather than let a stray config throw inside the native detector.
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            d.detect(TensorImage.fromBitmap(argb))
                .flatMap { it.categories }
                .filter { it.score >= MIN_SCORE }
                .map { it.label }
        }.getOrNull()
    }

    /**
     * Classify one thumbnail into a [ClipLabel], or null if the classifier is unavailable or errored.
     * null is fail-open input to [passesLabelGate]; an empty detection set is a positive [ClipLabel.NONE].
     */
    fun classify(bitmap: Bitmap): ClipLabel? = detectNames(bitmap)?.let { ClipLabel.summarize(it) }

    override fun close() {
        runCatching { detector?.close() }
    }

    private companion object {
        // Bundled EfficientDet-Lite0 (COCO, metadata-embedded). Fetched by download-model.sh into
        // app/src/main/assets/; deliberately NOT in git (a ~4.4 MB binary the build never needs).
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        const val MAX_RESULTS = 10
        const val MIN_SCORE = 0.40f
    }
}
