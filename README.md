# SafeGuardian

**A privacy-focused, offline-capable communication platform with integrated AI assistance**

SafeGuardian combines secure Bluetooth mesh networking with on-device AI capabilities, creating a censorship-resistant communication tool that works without servers or internet connectivity.

---

## Features

### 🔐 Secure Communication
- **Bluetooth LE Mesh Networking**: Multi-hop message relay, auto peer discovery
- **End-to-End Encryption**: X25519 key exchange + AES-256-GCM
- **Privacy First**: No accounts, no servers, no phone numbers
- **Tor Integration**: Optional Tor routing for internet-based geohash channels
- **Emergency Wipe**: Triple-tap logo to clear all data instantly

### 🤖 On-Device AI Assistant
- **Local LLM**: Qwen3 models (0.6B - 4B parameters)
- **Vision Models**: SmolVLM for image understanding
- **Streaming Inference**: Real-time AI responses
- **Context-Aware**: Maintains conversation history per channel
- **100% Private**: All AI processing happens on-device

### 🎙️ Voice Capabilities
- **Speech Recognition (ASR)**: VOSK offline speech-to-text
- **Text-to-Speech (TTS)**: Android built-in TTS
- **Voice Commands**: Hands-free AI interaction
- **Voice Mode**: Full voice input/output workflow

### 🔍 RAG (Retrieval-Augmented Generation)
- **Semantic Search**: Vector-based message search
- **Context Retrieval**: AI answers based on chat history
- **Document Indexing**: Index manuals, FAQs, knowledge bases
- **Local Vector DB**: ObjectBox with HNSW index
- **Reranking**: Optional BGE-reranker for precision

### 📱 Mesh Features
- **Channel-Based**: IRC-style group chats
- **Private Messaging**: Encrypted 1-on-1 chats
- **Store & Forward**: Offline message delivery
- **Password Protection**: Secure channel access
- **Geohash Channels**: Location-based discovery (requires internet)

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   SafeGuardian App                       │
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Mesh Network │  │  AI Service  │  │  RAG Service │  │
│  │  (Bitchat)   │  │   (Nexa SDK) │  │  (ObjectBox) │  │
│  │   Bluetooth  │  │     LLM      │  │   Vectors    │  │
│  │     + Tor    │  │  Embedding   │  │   HNSW       │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                 │                  │          │
│         └─────────────────┴──────────────────┘          │
│                           ↓                             │
│            ┌──────────────────────────┐                 │
│            │     ChatViewModel        │                 │
│            └──────────────────────────┘                 │
│                           ↓                             │
│       ┌───────────────────────────────────┐             │
│       │  TTS  │  ASR  │  Voice Commands  │             │
│       └───────────────────────────────────┘             │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## Tech Stack

### Core
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **Architecture**: MVVM with specialized managers
- **Platform**: Android 8.0+ (API 27+)

### Communication
- **Mesh**: Nordic BLE Library
- **Crypto**: BouncyCastle (X25519, Ed25519, AES-GCM)
- **Nostr**: OkHttp WebSocket client
- **Tor**: Arti mobile (Rust-based)

### AI/ML
- **LLM**: Nexa SDK (llama.cpp bindings)
- **ASR**: VOSK (offline speech recognition)
- **TTS**: Android TextToSpeech API
- **Vector DB**: ObjectBox 4.0
- **Embeddings**: nomic-embed-text-v1.5

---

## Project Structure

```
safeguardian/
├── bitchat-android/          # Base mesh messenger (integrated)
│   ├── app/src/main/java/com/bitchat/android/
│   │   ├── mesh/             # Bluetooth LE mesh networking
│   │   ├── crypto/           # Encryption services
│   │   ├── nostr/            # Geohash channel support
│   │   ├── ai/               # NEW: AI integration layer
│   │   │   ├── AIService.kt
│   │   │   ├── ModelManager.kt
│   │   │   ├── TTSService.kt
│   │   │   ├── ASRService.kt
│   │   │   └── rag/          # RAG pipeline
│   │   │       ├── RAGService.kt
│   │   │       └── RerankerService.kt
│   │   └── ui/               # Compose UI components
│   │       ├── ChatScreen.kt
│   │       └── ai/           # NEW: AI UI
│   │           ├── ModelManagerSheet.kt
│   │           └── VoiceInputButton.kt
│   └── build.gradle.kts      # Updated with AI dependencies
│
├── nexa-sdk-examples/        # Reference implementation
│   └── android/              # Nexa SDK usage examples
│
├── cui-llama.rn/             # Future: React Native LLM option
│
├── INTEGRATION_PLAN.md       # Detailed integration plan
├── TTS_ASR_RAG_ARCHITECTURE.md  # Voice & RAG architecture
└── README.md                 # This file
```

---

## AI Commands

### Basic AI
```
/ask [question]              - Ask AI with RAG context
/ask-raw [question]          - Ask AI without RAG
/summarize [n]               - Summarize last N messages
/translate [lang] [text]     - Translate text
```

### RAG & Search
```
/search [query]              - Semantic search messages
/rag on|off                  - Toggle RAG
/index-docs [path]           - Index documents
```

### Voice
```
/voice-mode                  - Enable voice I/O
/speak [text]                - TTS output
/listen                      - ASR input
/tts on|off                  - Toggle TTS
/asr on|off                  - Toggle ASR
```

### Model Management
```
/model load [name]           - Load AI model
/model unload                - Unload model
/model list                  - List models
/model download [name]       - Download model
```

---

## Models

### Recommended Models (~1.4GB total)

| Feature | Model | Size | Purpose |
|---------|-------|------|---------|
| LLM | Qwen3-0.6B-Q8_0 | 600MB | Text generation |
| Embedding | nomic-embed-text-v1.5 | 270MB | RAG embeddings |
| ASR | vosk-model-small-en | 40MB | Speech recognition |
| Reranker | bge-reranker-v2-m3 | 220MB | Context reranking (opt) |
| VLM | SmolVLM-256M | 260MB | Vision (Phase 3) |

---

## Installation

### Requirements
- Android Studio Hedgehog or newer
- Android device with Android 8.0+ (API 27+)
- 4GB+ RAM recommended
- 2GB+ free storage
- Bluetooth LE hardware

### Build Instructions

<<<<<<< HEAD
1. **Clone repository**
=======
1. Install Java 17 (Windows PowerShell)
   ```powershell
   # Download and install JDK 17 (e.g., from Adoptium or Oracle)
   # Then set environment variables for the current session
   $env:JAVA_HOME = "C:\\Program Files\\Java\\jdk-17"
   $env:PATH = "$env:JAVA_HOME\\bin;$env:PATH"
   java -version  # should show 17.x
   ```

2. **Clone repository**
>>>>>>> d49f50d (first commit)
   ```bash
   git clone <repo-url>
   cd safeguardian
   ```

<<<<<<< HEAD
2. **Open in Android Studio**
   - File → Open → Select `safeguardian/bitchat-android`

3. **Sync Gradle**
   - Android Studio will auto-sync dependencies
   - Wait for Nexa SDK, VOSK, ObjectBox to download

4. **Build**
=======
3. **Open in Android Studio**
   - File → Open → Select `safeguardian/bitchat-android`

4. **Sync Gradle**
   - Android Studio will auto-sync dependencies
   - Wait for Nexa SDK, VOSK, ObjectBox to download

5. **Build**
>>>>>>> d49f50d (first commit)
   ```bash
   cd bitchat-android
   ./gradlew assembleDebug
   ```

<<<<<<< HEAD
5. **Install**
=======
6. **Install**
>>>>>>> d49f50d (first commit)
   ```bash
   ./gradlew installDebug
   # or use Android Studio's Run button
   ```

### First Run Setup

1. **Grant Permissions**
   - Bluetooth (mesh networking)
   - Location (required for BLE scanning)
   - Microphone (voice input)
   - Notifications (message alerts)

2. **Download Models**
   - Open Settings → AI Models
   - Download Qwen3-0.6B (600MB)
   - Download VOSK model (40MB)
   - Optional: Download embedding model for RAG

3. **Start Chatting**
   - Set your nickname
   - Join a channel: `/j #general`
   - Try AI: `/ask What can you do?`

---

## Usage Examples

### Basic Chat
```
# Join a channel
/j #general

# Send a message
Hello everyone!

# Private message
/m @alice How are you?
```

### AI Assistant
```
# Ask a question
/ask What is mesh networking?

# Summarize conversation
/summarize 10

# Translate text
/translate es Hello, how are you?
```

### Voice Mode
```
# Enable voice mode
/voice-mode

# Now speak your questions
[Say: "Ask AI about encryption"]

# AI responds via TTS
```

### RAG Search
```
# Search messages semantically
/search encryption protocols

# Ask with context
/ask What did we discuss about security?
# (AI uses RAG to find relevant past messages)
```

---

## Development

### Phase 1: Foundation (Current)
- ✅ Integration plan completed
- ✅ TTS/ASR/RAG architecture designed
- 🔨 Adding Nexa SDK dependency
- 🔨 Creating AI service layer
- 🔨 Implementing model management

### Phase 2: Core AI
- Chat integration
- Streaming responses
- Context management
- Basic commands

### Phase 3: Advanced
- TTS/ASR integration
- RAG pipeline
- Voice commands
- VLM support

### Phase 4: Polish
- Performance optimization
- Battery management
- Testing
- Documentation

---

## Privacy & Security

### On-Device Processing
- ✅ All AI inference happens locally
- ✅ No data sent to cloud services
- ✅ Models downloaded once, used offline
- ✅ Vector embeddings stored locally

### Encrypted Communication
- ✅ X25519 key exchange
- ✅ AES-256-GCM encryption
- ✅ Ed25519 signatures
- ✅ Forward secrecy (new keys each session)

### Emergency Features
- ✅ Triple-tap logo to wipe all data
- ✅ Clear embeddings on demand
- ✅ Disable AI per channel
- ✅ No persistent logs

---

## Performance

### Memory Usage
- **LLM Model**: 600MB - 2GB (loaded)
- **Embedding Model**: 270MB
- **Vector DB**: ~1KB per message
- **ASR Model**: 40MB - 1.8GB
- **Peak Total**: ~1.5GB - 4GB

### Battery Impact
- **Mesh Networking**: Low (adaptive scanning)
- **TTS**: Minimal (system service)
- **ASR**: Low (optimized for mobile)
- **LLM Inference**: Medium-High (1-5 tokens/sec)
- **Mitigation**: Battery-aware power modes

### Latency
- **TTS**: <100ms
- **ASR**: Real-time streaming
- **Vector Search**: <50ms (10k embeddings)
- **LLM Generation**: 1-5 tokens/sec
- **Reranking**: ~500ms (10 docs)

---

## Testing

```bash
# Unit tests
./gradlew test

# Instrumentation tests
./gradlew connectedAndroidTest

# Specific test suites
./gradlew testDebugUnitTest --tests "AIServiceTest"
./gradlew testDebugUnitTest --tests "RAGServiceTest"
```

---

## Contributing

Contributions welcome! Key areas:

1. **Performance**: Battery/memory optimization
2. **UI/UX**: Accessibility, animations
3. **Security**: Code audits, crypto review
4. **Testing**: Unit, integration, UI tests
5. **Documentation**: API docs, tutorials

---

## Documentation

- [INTEGRATION_PLAN.md](INTEGRATION_PLAN.md) - Detailed integration roadmap
- [TTS_ASR_RAG_ARCHITECTURE.md](TTS_ASR_RAG_ARCHITECTURE.md) - Voice & RAG design
- [bitchat-android/README.md](bitchat-android/README.md) - Mesh networking docs
- [nexa-sdk-examples/android/README.md](nexa-sdk-examples/android/README.md) - AI SDK guide

---

## License

This project combines:
- **bitchat-android**: Public Domain (Unlicense)
- **SafeGuardian extensions**: Apache 2.0 (TBD)
- **Nexa SDK**: Apache 2.0
- **VOSK**: Apache 2.0
- **ObjectBox**: Apache 2.0

See individual component licenses for details.

---

## Credits

### Based On
- **bitchat** - iOS/Android mesh messenger by [@jackjackbits](https://github.com/jackjackbits/bitchat)
- **Nexa SDK** - On-device AI by [NexaAI](https://github.com/NexaAI/nexa-sdk)
- **llama.cpp** - LLM inference by [ggerganov](https://github.com/ggerganov/llama.cpp)
- **cui-llama.rn** - React Native bindings for [ChatterUI](https://github.com/Vali-98/ChatterUI)

### Technologies
- **VOSK** - Offline ASR by [Alpha Cephei](https://alphacephei.com/vosk/)
- **ObjectBox** - Vector database by [ObjectBox](https://objectbox.io/)
- **BouncyCastle** - Crypto library
- **Arti** - Tor in Rust by [Tor Project](https://gitlab.torproject.org/tpo/core/arti)

---

## Roadmap

### v1.0 (Current)
- Base mesh communication
- Channel & private messaging
- E2E encryption

### v1.1 (Phase 1-2)
- AI assistant integration
- Basic LLM commands
- Model management UI

### v1.2 (Phase 3)
- TTS/ASR voice features
- RAG semantic search
- Context-aware AI

### v1.3 (Phase 4)
- VLM vision support
- Performance optimization
- Production polish

### v2.0 (Future)
- Mesh AI sharing
- Collaborative knowledge
- Advanced features

---

## Support

- **Issues**: [GitHub Issues](../../issues)
- **Discussions**: [GitHub Discussions](../../discussions)
- **Security**: Report privately to security@(TBD)

---

## Acknowledgments

Special thanks to the open-source community for making privacy-focused, offline-capable AI communication possible.

**Built with privacy, security, and freedom in mind.**
