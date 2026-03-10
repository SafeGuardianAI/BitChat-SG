# SafeGuardian (SAFE-NET Mobile Client)

> Offline-first disaster resilience app for Android. Mesh networking + on-device AI + emergency triage.

## Overview

SafeGuardian is the mobile client for the SAFE-NET (Sentient Assistance & Fortified Emergency Network) system — a pre-deployed, infrastructure-independent communication platform designed to operate when conventional telecommunications collapse during disaster events.

### Key Capabilities

| Feature | Description | Status |
|---------|-------------|--------|
| BLE Mesh Networking | Multi-hop encrypted mesh via Bluetooth LE | Production |
| On-Device LLM | Qwen3/Granite models via Nexa SDK | Production |
| RAG Knowledge Base | Hybrid TF-IDF + vector disaster knowledge retrieval | Production |
| ASR/TTS | Sherpa-ONNX speech recognition + Android TTS | Beta |
| VAD + Keywords | Voice activity detection + emergency keyword spotting | Beta |
| Offline Sync | Firebase + MongoDB with offline queue | Beta |
| Vital Data Scanner | Photo-based medical/ID data extraction | Beta |
| BLE Distributed Memory | Shard offloading when battery <10% | Beta |
| Data Standards | CAP v1.2, EDXL-TEP, HL7 FHIR R4 | Planned |
| Agentic AI | On-device tool-use agent for emergency automation | Planned |

## Architecture

### Three-Tier System (aligned with SAFE-NET)

```
Tier 3: Smartphone BLE Mesh (SafeGuardian app)
  ↕ BLE 5.x (< 10m through rubble)
Tier 2: Wi-Fi HaLow Aggregation Hubs (MM8108)
  ↕ IEEE 802.11ah (100-500m indoor)
Tier 1: Edge AI Command Hub (Qualcomm IQ-9075)
```

### On-Device Stack

```
┌─────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)             │
├─────────────────────────────────────────┤
│  AI Services                            │
│  ├── LLM (Nexa SDK + GGUF models)      │
│  ├── RAG (TF-IDF + Vector embeddings)  │
│  ├── ASR (Sherpa-ONNX)                  │
│  ├── TTS (Android TextToSpeech)         │
│  └── Agentic Tools (Device APIs)        │
├─────────────────────────────────────────┤
│  Communication Layer                    │
│  ├── BLE Mesh (Noise protocol E2E)      │
│  ├── Nostr (Relay fallback)             │
│  └── Tor (Anonymous routing)            │
├─────────────────────────────────────────┤
│  Data Layer                             │
│  ├── ObjectBox (Vector DB)              │
│  ├── Encrypted SharedPreferences        │
│  ├── Offline Sync Queue                 │
│  └── CAP/FHIR/EDXL-TEP Standards       │
└─────────────────────────────────────────┘
```

## Quick Start

```bash
# Clone with submodules
git clone --recursive https://github.com/SafeGuardianAI/BitChat-SG.git
cd BitChat-SG

# Build debug APK
cd bitchat-android && ./gradlew assembleDebug
```

### Requirements
- Android Studio Ladybug+
- JDK 11+
- Android SDK 35 (min SDK 27)

## Project Structure

```
safeguardian/
├── bitchat-android/          # Main Android app (Kotlin)
│   ├── app/src/main/java/com/bitchat/android/
│   │   ├── ai/              # AI services (LLM, ASR, TTS, RAG)
│   │   ├── audio/           # VAD, keyword recognition, audio pipeline
│   │   ├── mesh/            # BLE mesh networking
│   │   │   └── distributed/ # BLE distributed memory
│   │   ├── sync/            # Offline-first sync engine
│   │   ├── vitals/          # Photo-based vital data
│   │   ├── crypto/          # Noise protocol encryption
│   │   ├── nostr/           # Nostr relay integration
│   │   └── ui/              # Jetpack Compose UI
│   └── app/src/test/        # Unit tests
├── cui-llama.rn/             # React Native LLM module
├── nexa-sdk-examples/        # Nexa SDK reference
└── docs/                     # Technical documentation
```

## Feature Branches

| Branch | Feature |
|--------|---------|
| `feature/database-bridge-tests` | Unit tests for DB bridge components |
| `feature/vital-data-photo` | Photo vital data extraction |
| `feature/offline-sync` | Firebase/MongoDB offline sync |
| `feature/ble-distributed-memory` | BLE shard offloading |
| `feature/vad-keyword` | VAD + keyword recognition |
| `feature/asr-tts` | Production ASR/TTS |
| `feature/on-device-rag` | Enhanced RAG with embeddings |
| `feature/agentic-ai` | On-device AI agent |
| `feature/data-standards` | CAP/FHIR/EDXL-TEP |
| `feature/android-device-agent` | Android API integration |

## Data Standards (SAFE-NET Aligned)

SafeGuardian implements the three-layer data standardization model recommended by SAFE-NET:

1. **CAP v1.2** — Outbound geo-alerting (ITU-T X.1303, 200+ countries)
2. **HL7 FHIR R4** — Clinical handoff to hospitals (US-SAFR IG)
3. **SNETR** — SAFE-NET Emergency Triage Record bridging EDXL-TEP + FHIR

## Documentation

All technical documentation has been consolidated in the [`docs/`](docs/) directory. Key documents include:

- [Integration Plan](docs/INTEGRATION_PLAN.md)
- [TTS/ASR/RAG Architecture](docs/TTS_ASR_RAG_ARCHITECTURE.md)
- [RAG Reranker Setup Guide](docs/RAG_RERANKER_SETUP_GUIDE.md)
- [Nexa SDK Integration Guide](docs/NEXA_SDK_INTEGRATION_GUIDE.md)
- [Testing and Debugging Guide](docs/TESTING_AND_DEBUGGING_GUIDE.md)
- [Implementation Status](docs/IMPLEMENTATION_STATUS.md)

## License

See [LICENSE](bitchat-android/LICENSE.md)
