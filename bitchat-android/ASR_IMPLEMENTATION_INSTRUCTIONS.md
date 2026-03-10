# ASR Implementation Instructions (Nexa SDK)

This guide documents the microphone ASR implementation in `bitchat-android` using the Nexa SDK.

## 1. Repository + Branch Setup

1. From repo root:
   ```bash
   cd bitchat-android
   git fetch --all
   git checkout gossip-routing-2
   git pull origin gossip-routing-2
   ```
2. Ensure clean tree:
   ```bash
   git status --short
   ```
   Must be empty before editing.

## 2. Nexa SDK Dependency

- **Version**: `ai.nexa:core:0.0.22` (in `app/build.gradle.kts`)
- Kotlin compiler config uses `compilerOptions` DSL (modern).

## 3. Nexa SDK Initialization

- **File**: `app/src/main/java/com/bitchat/android/BitchatApplication.kt`
- `NexaSdk.init(applicationContext)` is called at app startup.
- Wrapped in try/catch; failures are logged and do not crash the app.

## 4. ASR Module Files

`app/src/main/java/com/bitchat/android/asr/`:

1. **AsrAudioRecorder.kt**
   - Start/stop microphone recording to a temp WAV file.
   - Handles permission and recorder lifecycle safely.

2. **NexaAsrEngine.kt**
   - Load model via Nexa.
   - Exposes `transcribeFile(file: File): String`.

3. **AsrModelManager.kt**
   - Resolve model path in assets/files.
   - Check model presence and provide user-readable status.

## 5. Model Assets

- Place model under: `app/src/main/assets/nexa_models/parakeet-tdt-0.6b-v3-npu-mobile`
- Do not rename folder unless code is updated to match.

## 6. Permission + Onboarding Integration

- **PermissionManager.kt**: `Manifest.permission.RECORD_AUDIO` added as optional.
- **OnboardingCoordinator.kt**: Microphone permission display text added.

## 7. Chat/Command Integration

- **ChatViewModel**: `startAsrRecording()` and `stopAsrRecordingAndTranscribe()`.
- **CommandProcessor**: `/asrstart` and `/asrstop` commands route to those methods.

## 8. Build Verification

```bash
./gradlew clean :app:assembleDebug
```

Validate:
- App starts without crash.
- `/asrstart` begins recording.
- `/asrstop` returns transcript.
- Denied mic permission yields clear error.

## 9. Commit Strategy

Use small commits:

1. `feat(asr): add nexa sdk dependency and init`
2. `feat(asr): add recorder and asr engine`
3. `feat(asr): wire mic permission and commands`
4. `chore(asr): add model assets and docs`

## 10. Known Failure Pattern

If build shows unresolved refs from files like `ai/AIChatService.kt` or `telemetry/Telemeter.kt`, working tree may be mixed/stale. Hard-sync to branch tip, then reapply ASR changes cleanly.
