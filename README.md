## TranslateOverlay (local-only)

TranslateOverlay is an on-screen caption overlay for Android that transcribes device audio and optionally translates it — all on-device. It shows live captions in a draggable floating window above any app.

### What it does
- Captures device audio (media playback) using Android's MediaProjection + Audio Playback Capture (Android 10+)
- Performs on-device speech-to-text with Sherpa-ONNX
- Optionally translates text on-device using Google ML Kit Translate + Language ID
- Displays captions in a clean floating overlay with a compact control bar

### What it does NOT do
- No cloud processing and no OpenAI usage (removed). All processing happens locally on the device. Translation models are downloaded by ML Kit when needed.

---

## Architecture overview

- `AudioCaptureService` (Foreground Service)
  - Requests MediaProjection token (via `MainActivity`) and captures device audio frames.
  - Feeds PCM16 audio to `SherpaSttEngine` for streaming ASR.
  - Sends final sentences to the UI overlay.
  - If source and target languages differ, hands text to `TranslatorService` for ML Kit translation before displaying.

- `TranslatorService` (Background Service)
  - Uses ML Kit Language ID to detect language when source is set to Auto Detect.
  - Uses ML Kit Translate to translate to the selected target language.
  - Lazily downloads and caches the required translation models (Wi‑Fi required by default).

- `FloatingOverlay` + `CaptionOverlay`
  - A `WindowManager`-backed floating view that displays the latest captions.
  - Tap overlay to toggle the control bar (language selector + close button).
  - Drag to move; snaps to screen edges when you release.
  - Static width ~85% of screen; automatically re-applies on rotation.
  - Long‑press the overlay for quick settings (text size, background opacity, reset position).

---

## Current UI flow

1. Open the app and grant the "Draw over other apps" permission when prompted.
2. In the app, pick Source and Target languages:
   - Source defaults to Auto Detect.
   - Target defaults to English and is stored in shared preferences.
3. Tap "Show Overlay" to display the floating captions window.
4. Tap "Start Transcription" to begin capturing and transcribing device audio.
5. In the overlay:
   - Tap to show/hide the bottom control bar.
   - Use the language spinner to change the Source language on the fly.
   - Tap the close button to stop services and remove the overlay.
   - Long‑press the overlay to tweak font size and background opacity or reset position.

---

## Requirements

- Android 10 (API 29) or newer (required for Audio Playback Capture)
- Internet connection may be needed once to download ML Kit translation models

### App permissions (Manifest)
- `android.permission.SYSTEM_ALERT_WINDOW` — draw over other apps
- `android.permission.RECORD_AUDIO` — record device audio (captured via MediaProjection)
- `android.permission.FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` — run persistent capture service
- `android.permission.INTERNET` — ML Kit may download translation models
- `android.permission.ACCESS_NETWORK_STATE` — optional; used for connectivity checks (safe to remove if unused)

---

## Build and run

From the project root:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Launch the app, grant overlay permission, then show the overlay and start transcription.

---

## Key implementation files

- `app/src/main/java/me/connor/translateoverlay/AudioCaptureService.kt`
- `app/src/main/java/me/connor/translateoverlay/TranslatorService.kt`
- `app/src/main/java/me/connor/translateoverlay/FloatingOverlay.kt`
- `app/src/main/java/me/connor/translateoverlay/CaptionOverlay.kt`
- `app/src/main/java/me/connor/translateoverlay/stt/SherpaSttEngine.kt`

Removed (legacy cloud integration):
- OpenAI-related files and UI have been deleted.
- Accessibility overlay feature has been removed.

---

## Overlay behavior

- Width: ~85% of screen, static across rotations (auto-adjusted on configuration change)
- Dragging: free movement; snaps to edges on release
- Control bar: shown/hidden by tapping the overlay; stays visible while using the spinner
- Styling: Material colors, rounded corners, compact spinner and crisp vector close icon
- Long‑press: quick settings (font size, opacity, reset position)

---

## Privacy

- Audio processing (ASR) happens locally using Sherpa-ONNX
- Text translation is on-device using ML Kit; requires model downloads from Google if not cached
- No audio or text is sent to remote servers by this app

---

## Troubleshooting

- No captions appear:
  - Ensure Android 10+ and grant overlay permission
  - Start transcription after showing the overlay
  - Increase media volume so playback capture has signal

- Translation not working:
  - Verify target language in the app UI
  - Connect to Wi‑Fi once so ML Kit can download required models

- Overlay not visible after rotation:
  - Tap "Show Overlay" again, or long‑press and use Reset Position in quick settings

---
