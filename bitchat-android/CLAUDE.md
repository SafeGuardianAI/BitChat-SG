# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SafeGuardian is an offline-first, privacy-focused Android mesh messaging app built on bitchat-android. It combines secure BLE mesh networking with on-device AI inference via Nexa SDK. All processing happens locally — zero cloud dependencies.

## Build Commands

```bash
# Build debug APK
cd bitchat-android && ./gradlew assembleDebug

# Build release APK
cd bitchat-android && ./gradlew assembleRelease

# Clean build
cd bitchat-android && ./gradlew clean assembleDebug

# Run lint
cd bitchat-android && ./gradlew lint
```

The app module is at `bitchat-android/app/`. The root `safeguardian/` directory contains git submodules (`bitchat-android`, `nexa-sdk-examples`, `cui-llama.rn`) — all source modifications go in `bitchat-android/`.

## Architecture

### Core Packages (`com.bitchat.android`)

- **`ai/`** — AI services: LLM inference (`AIService`), model management (`ModelManager`), chat integration (`AIChatService`), TTS/ASR, preferences. Central coordinator: `AIManager`.
- **`ai/rag/`** — Disaster RAG: `DisasterKnowledgeBase` (embedded safety data), `SimpleTextSearch` (TF-IDF retrieval), `DisasterRAGService` (prompt augmentation).
- **`ai/functions/`** — On-device function calls: `FunctionRegistry` (tool definitions), `FunctionExecutor` (JSON parsing + dispatch), `DisasterFunctions` (emergency actions).
- **`mesh/`** — BLE mesh networking: `BluetoothMeshService` (coordinator), `PeerManager`, `FragmentManager`, `SecurityManager`, `MessageHandler`, `PacketProcessor`, `BluetoothConnectionManager`.
- **`crypto/`** — E2E encryption: Noise protocol, Ed25519 signing, X25519 key exchange.
- **`nostr/`** — Nostr relay integration for geohash location channels and fallback messaging.
- **`services/`** — `MessageRouter` (smart routing between mesh/Nostr), `MeshGraphService`.
- **`ui/`** — Jetpack Compose UI: `ChatScreen` (main), `ChatViewModel` (coordinator), `ChatState` (observable state).
- **`model/`** — Data models: `BitchatMessage` (binary-compatible with iOS), `BitchatPacket`.

### Key Patterns

- **ViewModel delegates to managers**: `ChatViewModel` uses `DataManager`, `MessageManager`, `ChannelManager`, `PrivateChatManager`, `CommandProcessor`, `GeohashViewModel`.
- **Message flow**: User → `ChatViewModel.sendMessage()` → `MessageRouter` → (BLE mesh or Nostr) → remote peer's `MessageHandler` → `BluetoothMeshDelegate.didReceiveMessage()`.
- **AI flow**: User prompt → `AIChatService` → `DisasterRAGService.augmentPrompt()` → `AIService.generateResponse()` (streaming) → `AIMessageSharing.shareWithPeers()` + TTS.
- **Encrypted preferences**: `AIPreferences` uses `EncryptedSharedPreferences` for all AI settings.
- **Binary protocol**: `BitchatMessage.toBinaryPayload()` / `fromBinaryPayload()` — iOS-compatible.

### AI/ML Stack

- **LLM**: Nexa SDK (`ai.nexa:core:0.0.22`) with GGUF models (Granite 4.0, Qwen3)
- **ASR**: Sherpa-ONNX (`com.bihe0832.android:lib-sherpa-onnx`) + Android `SpeechRecognizer`
- **TTS**: Android built-in `TextToSpeech`
- **Vector DB**: ObjectBox with HNSW indexing (for future embedding-based RAG)
- **Model catalog**: `ModelCatalog` / `AIModels.kt` defines available models with download URLs

### Mesh Protocol

- Messages use `BitchatPacket` with types: ANNOUNCE, MESSAGE, NOISE_ENCRYPTED, NOISE_HANDSHAKE, LEAVE, FRAGMENT
- AI hosting protocol: `[AI_REQ]<requestId>:<prompt>` / `[AI_RES]<requestId>:<response>` over MESSAGE type
- AI sharing: `[AI] Q: <query>\nA: <response>` prefix for broadcast AI messages
- Packets are signed with Ed25519 before broadcast

## Important Constraints

- **Min SDK 27** (Android 8.0+), Kotlin 1.9.23+, Compose enabled
- **JNI conflict**: Both Nexa SDK and Sherpa-ONNX ship `libonnxruntime.so` — build uses `pickFirst`
- **Battery**: All AI features have power modes (ULTRA_SAVER → PERFORMANCE), auto-unload after 5min idle
- **iOS compatibility**: `BitchatMessage` binary format and mesh packet format must stay byte-compatible with iOS
