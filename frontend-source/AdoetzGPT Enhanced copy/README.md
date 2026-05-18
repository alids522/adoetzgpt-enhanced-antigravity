# AdoetzGPT Enhanced

Capacitor Android client for an OpenWebUI backend.

The app is intentionally a native Android wrapper around the existing OpenWebUI web frontend. It starts with a local server picker, stores the selected OpenWebUI server URL, then opens that server inside the Capacitor WebView.

## Current architecture

- Capacitor Android shell
- Existing OpenWebUI frontend loaded from a user-provided server URL
- Native Android bridge exposed as `AdoetzNative`
- Android foreground service scaffold for live/background voice
- GitHub Actions workflow for debug APK, release APK, and release AAB builds

## First launch workflow

1. Open the app.
2. Enter the OpenWebUI server URL.
3. Tap **Connect**.
4. Log in through the existing OpenWebUI frontend.

The server URL is stored in both local web storage and native Android shared preferences.

## Native bridge

The local web shell and future OpenWebUI frontend hooks can call:

```js
window.Capacitor.Plugins.AdoetzNative.setServerUrl({ url });
window.Capacitor.Plugins.AdoetzNative.getServerUrl();
window.Capacitor.Plugins.AdoetzNative.clearServerUrl();
window.Capacitor.Plugins.AdoetzNative.startLiveVoice({ serverUrl });
window.Capacitor.Plugins.AdoetzNative.stopLiveVoice();
window.Capacitor.Plugins.AdoetzNative.isLiveVoiceRunning();
```

## Background voice

`LiveVoiceForegroundService` is a native Android foreground service with microphone capture, notification, and wake lock support.

The service currently owns the Android-safe background audio capture loop. The final network transport should be wired to the Android-facing OpenWebUI/Gemini Live endpoint after that backend contract is finalized.

## Permissions

The Android app declares:

- `INTERNET`
- `RECORD_AUDIO`
- `MODIFY_AUDIO_SETTINGS`
- `WAKE_LOCK`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `POST_NOTIFICATIONS`

## Build locally

```bash
npm ci
npm run sync
cd android
./gradlew assembleDebug
```

## Build on GitHub

The workflow at `.github/workflows/android.yml` builds:

- debug APK
- unsigned release APK
- unsigned release AAB

Artifacts are uploaded from every workflow run.

## Release signing

The current workflow produces unsigned release artifacts. Add keystore secrets later for signed production releases:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
