## TranslateOverlay

TranslateOverlay is an on-screen caption overlay for Android that transcribes device audio and optionally translates it — all on-device. It shows live captions in a draggable floating window above any app.

### What it does
- Captures device audio (media playback) using Android's MediaProjection + Audio Playback Capture (Android 10+)
- Performs on-device speech-to-text with Sherpa-ONNX
- Optionally translates text on-device using Google ML Kit Translate + Language ID
- Displays captions in a clean floating overlay with a compact control bar

### What it does NOT do
- No cloud processing. All processing happens locally on the device. Translation models are downloaded by ML Kit when needed.

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
