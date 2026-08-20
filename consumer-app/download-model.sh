#!/usr/bin/env bash
# Fetch the on-device object-detection model for Phase-1 people detection.
#
# WHY THIS IS A SCRIPT AND NOT A COMMITTED ASSET
# The model is a ~4.4 MB binary. Committing it would bloat the repo and needs LFS; fetching it at
# build time would break the `--offline` build. So it lives outside git: this script drops it into
# app/src/main/assets/, where the app loads it at runtime. The build never needs it (ClipClassifier
# fails open when it's absent — people-detection simply stays dormant until the file is here), so a
# missing model is a dormant feature, never a broken build.
#
# Run once, from anywhere: bash consumer-app/download-model.sh
set -euo pipefail

# EfficientDet-Lite0, COCO, WITH embedded metadata + label map — exactly what TF Lite Task Vision's
# ObjectDetector expects (it reads the class names from the model's metadata). This is the same file
# the official TFLite object-detection Android sample ships.
URL="https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="$HERE/app/src/main/assets"
DEST="$DEST_DIR/efficientdet_lite0.tflite"     # must match ClipClassifier.MODEL_ASSET

mkdir -p "$DEST_DIR"
if [ -f "$DEST" ]; then
  echo "✓ model already present: $DEST ($(du -h "$DEST" | cut -f1))"
  exit 0
fi

echo "↓ fetching EfficientDet-Lite0 (COCO) → $DEST"
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 -o "$DEST.tmp" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -O "$DEST.tmp" "$URL"
else
  echo "need curl or wget" >&2; exit 1
fi
mv -f "$DEST.tmp" "$DEST"

# A truncated download is worse than a missing one (it would load and then misbehave), so sanity-check
# the size: the real file is ~4.4 MB.
bytes=$(wc -c < "$DEST")
if [ "$bytes" -lt 1000000 ]; then
  echo "✗ downloaded file is only ${bytes} bytes — looks truncated; removing" >&2
  rm -f "$DEST"; exit 1
fi
echo "✓ done: $DEST ($(du -h "$DEST" | cut -f1))"
