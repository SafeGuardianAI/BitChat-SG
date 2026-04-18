# Session 2 Changes — SafeGuardian

## 1. NEXA_TOKEN & NPU Environment Setup

### `local.properties`
- Added `NEXA_TOKEN` key for Nexa SDK S3 authentication (gitignored — never committed).

### `app/build.gradle.kts`
- Added `import java.util.Properties` at file top.
- Added `buildConfig = true` to `buildFeatures` block (required to generate `BuildConfig`).
- Added `buildConfigField("String", "NEXA_TOKEN", ...)` in `defaultConfig` — reads the token from `local.properties` at build time and bakes it into `BuildConfig.NEXA_TOKEN`. The token is never hardcoded in source.

### `BitchatApplication.kt`
- Added `import android.system.Os`.
- Before `NexaSdk.init()`, now sets three environment variables via `Os.setenv()`:
  - `NEXA_TOKEN` — authenticates NPU model downloads from Nexa S3.
  - `NEXA_PLUGIN_PATH` — tells the Nexa native plugin loader where the JNI `.so` files are (`applicationInfo.nativeLibraryDir`).
  - `LD_LIBRARY_PATH` — extended with `nativeLibDir` so the dynamic linker resolves Qualcomm QNN/HTP delegate `.so` files at runtime.

### `AIService.kt`
- Added `import com.nexa.sdk.bean.DeviceIdValue`.
- Fixed `loadAIModel()`: changed `device_id = null` → `device_id = if (isNpu) DeviceIdValue.NPU.value else null`.  
  Previously NPU models always passed `null`, causing the Qualcomm HTP backend to reject the load. Now CPU/GPU models get `null`; `.nexa` models get the correct `DeviceIdValue.NPU.value`.

---

## 2. Audio Scribe — Offline Voice Note Transcription

Adds a **⊙ scribe** button to every voice note bubble in the chat. Tapping it transcribes the M4A recording with Sherpa-ONNX Whisper Tiny (~40 MB, fully offline) and optionally runs the transcript through Gemma-4-E2B to produce a structured field report.

### Architecture

```
AudioMessageItem  (tap ⊙ scribe)
        ↓
AIChatService.processAudioNote(path)
        ↓  checks model present
M4ADecoder.decodeToFloat()          ← new
  MediaExtractor + MediaCodec
  44.1kHz AAC → 16kHz mono FloatArray
        ↓
ASRService.transcribeFile()         ← rewritten
  OfflineRecognizer (Sherpa-ONNX)
  acceptWaveform → decode → getText
        ↓  raw transcript
Gemma-4-E2B via TaskConfig.AUDIO_SCRIBE  ← new task
        ↓
Structured report: TRANSCRIPT / KEY FACTS / NEXT STEPS
```

### New Files

#### `features/voice/M4ADecoder.kt`
- `suspend fun decodeToFloat(path, targetSampleRate = 16000): FloatArray?`
- Uses `MediaExtractor` + `MediaCodec` to decode any MediaExtractor-compatible audio (M4A, WAV, etc.).
- Downmixes multi-channel audio to mono by averaging channels.
- Resamples from native sample rate (44,100 Hz from `VoiceRecorder`) to 16,000 Hz via linear interpolation.
- Returns PCM samples normalized to `[-1, 1]` as required by `OfflineStream.acceptWaveform(float[], int)`.

### Modified Files

#### `ai/ASRService.kt` — complete rewrite
Previous state: fully stubbed, `OfflineRecognizer` never constructed, all methods returned placeholders.

New implementation:
- Uses `com.k2fsa.sherpa.onnx.OfflineRecognizer` with `OfflineWhisperModelConfig` (Whisper Tiny EN).
- Model expected at `filesDir/models/sherpa-onnx-whisper-tiny.en/` with files:
  - `tiny.en-encoder.int8.onnx`
  - `tiny.en-decoder.int8.onnx`
  - `tiny.en-tokens.txt`
- `companion object` provides `isModelDownloaded(context)` and `modelDir(context)` for UI checks.
- `initialize()` — lazy, no-arg. Constructs `OfflineModelConfig` via `.apply {}` and passes it to `OfflineRecognizer(context.assets, config)`.
- `transcribeFile(File)` — decodes via `M4ADecoder`, calls `acceptWaveform()` at 16kHz, `decode()`, `getResult().text`.
- `transcribeFloat(FloatArray)` — for already-decoded PCM.
- `release()` — calls `recognizer.release()`.

#### `ai/TaskConfig.kt`
Added `AUDIO_SCRIBE` task:
- `maxTokens = 400`, `temperature = 0.3f`
- System prompt structures output into three sections: `TRANSCRIPT`, `KEY FACTS`, `NEXT STEPS`.
- Tuned for noisy/fragmented field speech; marks unclear words as `[?]`.

#### `ai/AIChatService.kt`
Added `processAudioNote(audioPath: String): String`:
1. Checks `ASRService.isModelDownloaded()` — returns download nudge string if missing.
2. Decodes + transcribes via `ASRService.transcribeFile()`.
3. If LLM not loaded, returns raw transcript only.
4. Otherwise passes transcript to `processMessage()` with `TaskConfig.AUDIO_SCRIBE`.

Fixed stale `asrService.getCurrentModelInfo()` call in `getASRStatus()` (method removed in rewrite).

#### `ai/SpeechRecognitionService.kt`
Updated `recognizeOffline()` call from `offlineAsrService.initialize(modelId, language)` to `offlineAsrService.initialize()` to match the new no-arg signature.

#### `ui/media/AudioMessageItem.kt`
Added Audio Scribe UI to every voice note message bubble:

- `ScribeState` sealed interface: `Idle`, `Transcribing`, `Done(report)`.
- **`⊙ scribe`** text button (10sp, muted) visible on all non-in-flight audio messages.
  - Shows `⊙` only (no label) when ASR model is not yet downloaded.
- While transcribing: `CircularProgressIndicator` (20dp).
- On completion: `✦` icon (tap to reset) + animated result panel below the player.
- **Result panel**: `Surface` with `RoundedCornerShape(8dp)`, monospace text, `expandVertically + fadeIn` animation. Shows the full structured report. `✕` button collapses it.
- `VoiceNotePlayer` wrapped in `Box(Modifier.weight(1f))` to keep layout stable when scribe controls appear.

#### `features/voice/M4ADecoder.kt` (new — listed above)

---

## Dependency Notes

- No new Gradle dependencies added.
- Sherpa-ONNX (`com.bihe0832.android:lib-sherpa-onnx:6.25.21`) was already present; this session wires it up for the first time.
- ASR model must be downloaded separately via the existing `ModelManager` flow:
  - Model ID: `sherpa-onnx-small-en`
  - Catalog entry: `AIModelCatalog.SHERPA_ONNX_SMALL_EN`
  - Download URL: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2`
  - Size: ~40 MB

---

## Build Status

All changes compile cleanly (`./gradlew :app:compileDebugKotlin`). Only pre-existing deprecation warnings remain (unrelated to this session's changes).
