package com.famviva.camara.data

/**
 * What a clip's thumbnail actually contains, distilled from an on-device object detector into the
 * three buckets a household cares about — plus [NONE] for "the detector ran and found nothing worth
 * naming" (a shadow, rain, moving vegetation: exactly the noise the ~73-clips-a-day gallery is mostly
 * made of).
 *
 * A `null` label is deliberately DIFFERENT from [NONE]: null means the classifier never got to run
 * (no model on the device, a decode failure, an error) and callers must fail *open* — never suppress
 * a real event just because the classifier was unavailable. [NONE] is a positive verdict; null is the
 * absence of one.
 *
 * [priority] orders the buckets by how much a person watching the camera cares: when a single frame
 * holds several things at once (a person next to a parked car), the clip is summarised as the most
 * important one. Lower number = more important.
 */
enum class ClipLabel(val priority: Int) {
    PERSON(0),
    VEHICLE(1),
    ANIMAL(2),
    NONE(3);

    companion object {
        /**
         * Map one raw COCO class name (as emitted by the EfficientDet/SSD detector) into our bucket.
         * COCO is the reason TFLite was chosen over ML Kit's base model: it natively distinguishes
         * `person` from `car` from `dog`, which is the entire point of the feature. Unlisted classes
         * (backpack, chair, potted plant, …) are noise for this use case → [NONE].
         */
        fun ofCoco(name: String): ClipLabel = when (name.lowercase().trim()) {
            "person" -> PERSON
            "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat" -> VEHICLE
            "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe" -> ANIMAL
            else -> NONE
        }

        /**
         * Reduce a frame's worth of detections into a single label: the most important bucket present.
         * An empty set of detections is an honest [NONE] (the detector looked and saw nothing we name),
         * not a null (which would mean it never looked).
         */
        fun summarize(cocoNames: Iterable<String>): ClipLabel =
            cocoNames.map { ofCoco(it) }.minByOrNull { it.priority } ?: NONE
    }
}

/**
 * The alert gate, as a pure decision so it can be tested without Android or a model. Given a clip's
 * [label] and whether the user turned on the "only people" filter, should this clip fire an alert?
 *
 * - filter off  → everything passes (unchanged behaviour).
 * - filter on   → only [ClipLabel.PERSON] passes… EXCEPT a null label (classifier unavailable) always
 *   passes. That fail-open mirrors the existing intensity gate: over-notifying is a nuisance, but
 *   silently swallowing a real intrusion because the model failed to load is a security failure.
 */
fun passesLabelGate(label: ClipLabel?, peopleOnly: Boolean): Boolean =
    !peopleOnly || label == null || label == ClipLabel.PERSON
