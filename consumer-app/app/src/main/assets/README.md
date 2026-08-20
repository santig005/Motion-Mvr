# assets/

The on-device object-detection model for Phase-1 people detection lives here at runtime:

    efficientdet_lite0.tflite   (EfficientDet-Lite0, COCO, ~4.4 MB — gitignored)

It is **not** committed (a binary the build never needs). Fetch it once with:

    bash consumer-app/download-model.sh

`ClipClassifier` fails open when the file is absent, so the app builds and runs without it —
people-detection just stays dormant until the model is here.
