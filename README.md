# SaeSTT-Android

A native Android offline English speech-to-text tester using sherpa-onnx.

## Features
- Automatically downloads the ASR model on first launch.
- Stores model files under `Android/data/com.sae.stt/files/models/`.
- 16 kHz mono microphone capture with streaming partial and final transcripts.
- Fully offline recognition after the initial model download.
- Copy and clear transcript controls.

## Model
`sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, using the INT8 encoder, decoder and joiner. English-only; approximately 44 MB; Apache-2.0 model license.

Runtime: sherpa-onnx v2.0.1. Gradle downloads the official Android AAR and verifies its SHA-256 before compiling.

## Build
1. Open this directory in Android Studio.
2. Use JDK 17 and Android SDK 35.
3. Allow Gradle to download the sherpa-onnx AAR on the first build.
4. Build and install the debug APK.

Target: Android 8.0+ (API 26+), arm64-v8a.

## Storage
The app uses the app-specific external files directory, normally:
`/storage/emulated/0/Android/data/com.sae.stt/files/models/`

No broad storage permission is requested. Android file managers may require explicit access to Android/data. Uninstalling the app removes this app-specific directory.

The first model download requires internet. Subsequent recognition is offline. The runtime is packaged in the APK; the model is downloaded by the app.
