# ASR Implementation Instructions (Nexa SDK)

This guide is for another coding model to implement microphone ASR in `bitchat-android` step-by-step with minimal merge risk.

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

## 2. Add Nexa SDK Dependency

1. Edit `app/build.gradle.kts`.
2. Add dependency under `dependencies`:
   ```kotlin
   implementation("ai.nexa:core:0.0.22")
   ```
3. Keep Kotlin compiler config modern (`compilerOptions` DSL, not deprecated `kotlinOptions`).

## 3. Initialize Nexa SDK at App Startup

1. Edit `app/src/main/java/com/bitchat/android/BitchatApplication.kt`.
2. In app init flow, initialize Nexa:
   ```kotlin
   NexaSdk.getInstance().init(applicationContext)
   ```
3. Wrap with `try/catch` and log failures; do not crash app startup.

## 4. Add ASR Module Files

Create `app/src/main/java/com/bitchat/android/asr/`:

1. `AsrAudioRecorder.kt`
   - Start/stop microphone recording to a temp WAV/PCM file.
   - Handle permission and recorder lifecycle safely.
2. `NexaAsrEngine.kt`
   - Load model via Nexa.
   - Expose `transcribeFile(file: File): String`.
3. `AsrModelManager.kt`
   - Resolve model path in assets/files.
   - Check model presence and provide user-readable status.

## 5. Add Model Assets

1. Place model under:
   `app/src/main/assets/nexa_models/parakeet-tdt-0.6b-v3-npu-mobile`
2. Do not rename folder unless code is updated to match.

## 6. Permission + Onboarding Integration

1. Edit `onboarding/PermissionManager.kt`:
   - Add `Manifest.permission.RECORD_AUDIO` as optional/runtime permission.
2. Edit `onboarding/OnboardingCoordinator.kt`:
   - Add microphone display text/permission mapping.

## 7. Chat/Command Integration

1. Edit `ui/ChatViewModel.kt`:
   - Add `startAsrRecording()` and `stopAsrRecordingAndTranscribe()`.
   - Add mic permission checks before recording.
2. Edit `ui/CommandProcessor.kt`:
   - Add `/asrstart` and `/asrstop` commands.
   - Route to `ChatViewModel` methods.

## 8. Safety for Rescue Voice Monitor (Optional but Recommended)

1. Edit `disaster/DisasterVoiceMonitor.kt`.
2. Check mic permission before starting recognizer.
3. Add duplicate-trigger cooldown for partial/final transcript replay.

## 9. Build + Verify

1. Build:
   ```bash
   ./gradlew clean :app:assembleDebug
   ```
2. Validate:
   - App starts without crash.
   - `/asrstart` begins recording.
   - `/asrstop` returns transcript.
   - Denied mic permission yields clear error.

## 10. Commit Strategy

Use small commits:

1. `feat(asr): add nexa sdk dependency and init`
2. `feat(asr): add recorder and asr engine`
3. `feat(asr): wire mic permission and commands`
4. `chore(asr): add model assets and docs`

## 11. Known Failure Pattern

If build shows unresolved refs from files like `ai/AIChatService.kt` or `telemetry/Telemeter.kt`, working tree is mixed/stale. Hard-sync to branch tip, then reapply ASR changes cleanly.
