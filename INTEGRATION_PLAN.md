# SafeGuardian - Integration Plan

## Executive Summary

**SafeGuardian** is a unified privacy-focused communication and AI assistant app combining three existing projects:

1. **bitchat-android** - Secure mesh messaging (Bluetooth + Nostr/geohash channels)
2. **nexa-sdk-examples** - On-device AI inference (LLM/VLM models)
3. **cui-llama.rn** - Alternative React Native LLM framework (optional/future)

**Goal**: Create a censorship-resistant, offline-capable communication platform with integrated AI assistance that prioritizes privacy and works without servers or internet connectivity.

---

## Project Analysis

### 1. bitchat-android (Base Application)
- **Tech Stack**: Kotlin, Jetpack Compose, Material Design 3
- **Architecture**: MVVM with specialized managers (ChatViewModel, MessageManager, ChannelManager, etc.)
- **Core Features**:
  - Bluetooth LE mesh networking (multi-hop message relay)
  - E2E encryption (X25519 + AES-256-GCM)
  - Channel-based & private messaging
  - Nostr relay integration for geohash channels
  - Tor support via Arti
  - Store-and-forward messaging
  - Battery optimization & adaptive scanning
- **Dependencies**:
  - Nordic BLE library
  - BouncyCastle (crypto)
  - Arti-mobile-ex (Tor)
  - OkHttp (WebSocket)
  - Compose UI

### 2. nexa-sdk-examples (AI Integration Source)
- **Tech Stack**: Kotlin, Nexa AI SDK (ai.nexa:core:0.0.3)
- **Features**:
  - LLM (text generation) & VLM (vision-language models)
  - Model downloading & management
  - Streaming inference
  - Chat template conversion
  - Generation config (temperature, top-k, top-p, etc.)
- **Models Supported**: Qwen3, SmolVLM (lightweight for mobile)
- **Key Classes**:
  - `LlmWrapper` / `VlmWrapper` - Model interfaces
  - `ModelConfig` - Context size, threads, GPU layers
  - `GenerationConfig` - Sampling parameters

### 3. cui-llama.rn (Future Option)
- **Tech Stack**: React Native, llama.cpp native binding
- **Status**: More feature-rich but requires React Native integration
- **Decision**: Use Nexa SDK first (pure native Kotlin), migrate to cui-llama.rn later if needed

---

## Integration Architecture

### Unified App Structure

```
SafeGuardian/
├── app/ (bitchat-android as base)
│   ├── src/main/java/com/bitchat/android/
│   │   ├── MainActivity.kt
│   │   ├── BitchatApplication.kt
│   │   ├── mesh/ (Bluetooth mesh networking)
│   │   ├── crypto/ (Encryption services)
│   │   ├── nostr/ (Geohash channels)
│   │   ├── ui/
│   │   │   ├── ChatViewModel.kt (enhanced with AI)
│   │   │   ├── ChatScreen.kt (enhanced UI)
│   │   │   ├── ai/ (NEW - AI-specific UI)
│   │   │   │   ├── AIAssistantSheet.kt
│   │   │   │   ├── ModelManagerSheet.kt
│   │   │   │   └── AISettingsSheet.kt
│   │   ├── ai/ (NEW - AI integration layer)
│   │   │   ├── AIService.kt (Core AI coordinator)
│   │   │   ├── AIMessageHandler.kt (Process AI commands)
│   │   │   ├── ModelManager.kt (Download/load models)
│   │   │   ├── ConversationContext.kt (Maintain chat context)
│   │   │   ├── AIFeatures.kt (AI-powered features)
│   │   │   └── AIPreferences.kt (Settings storage)
│   │   └── util/
├── build.gradle.kts (merged dependencies)
└── AndroidManifest.xml (merged permissions)
```

---

## Key Integration Points

### 1. **ChatViewModel Enhancement**
- Add `AIService` as dependency
- Intercept AI commands (`/ask`, `/summarize`, `/translate`, etc.)
- Stream AI responses as special message type
- Maintain conversation context per channel/chat

### 2. **Message Type Extension**
```kotlin
enum class MessageType {
    TEXT,           // Existing
    PRIVATE,        // Existing
    AI_REQUEST,     // NEW - User asking AI
    AI_RESPONSE,    // NEW - AI responding
    AI_PROCESSING   // NEW - AI thinking indicator
}
```

### 3. **AI Command System**
```kotlin
// Example commands
/ask [question]              // Ask AI assistant
/summarize [n]              // Summarize last N messages
/translate [lang] [text]    // Translate text
/vision [describe image]    // VLM image analysis (future)
/model [load/unload/list]   // Manage AI models
```

### 4. **UI Integration**
- Add AI indicator in chat header (model loaded status)
- Show "AI is thinking..." animation during inference
- Differentiate AI messages visually (different color/icon)
- Add model manager in settings/sidebar
- Show token usage & inference stats

---

## Implementation Phases

### Phase 1: Foundation (Week 1-2)
**Goal**: Basic AI integration without breaking existing features

1. **Add Nexa SDK Dependency**
   - Update `build.gradle.kts` with Nexa SDK
   - Add JNI packaging options
   - Sync and test build

2. **Create AI Service Layer**
   ```kotlin
   class AIService(context: Context) {
       private var llmWrapper: LlmWrapper? = null
       private var vlmWrapper: VlmWrapper? = null
       private val modelManager = ModelManager(context)

       suspend fun initialize() { NexaSdk.init(context) }
       suspend fun loadModel(modelId: String): Result<Unit>
       suspend fun generateResponse(prompt: String): Flow<String>
       fun unloadModel()
   }
   ```

3. **Basic Command Processing**
   - Extend `CommandProcessor.kt` to detect `/ask` command
   - Route AI commands to `AIService`
   - Display responses as system messages

4. **Model Download UI**
   - Create `ModelManagerSheet.kt` (bottom sheet)
   - List available models (Qwen3-0.6B, Qwen3-1.8B)
   - Download progress indicator
   - Use OkDownload (already in nexa-sdk-examples)

### Phase 2: Core AI Features (Week 3-4)
**Goal**: AI assistant functionality with conversation context

1. **Conversation Context Management**
   ```kotlin
   class ConversationContext {
       private val chatHistory = mutableListOf<ChatMessage>()

       fun addUserMessage(text: String)
       fun addAIMessage(text: String)
       fun getContextForChannel(channelId: String): List<ChatMessage>
       fun clearContext(channelId: String)
   }
   ```

2. **AI Message Handler**
   - Maintain separate context per channel/private chat
   - Apply chat template before inference
   - Handle streaming responses
   - Error handling & fallbacks

3. **Enhanced Commands**
   - `/summarize [n]` - Summarize last N messages in channel
   - `/translate [lang]` - Translate using AI
   - `/help ai` - Show AI command help

4. **UI Improvements**
   - AI response streaming animation
   - Token counter & performance metrics
   - Model status indicator in header
   - Settings for AI behavior (temperature, max tokens)

### Phase 3: Advanced Features (Week 5-6)
**Goal**: Vision, smart routing, privacy enhancements, TTS/ASR/RAG

1. **Vision-Language Model (VLM)**
   - Integrate SmolVLM for image understanding
   - `/vision` command for image analysis
   - Image attachment support in mesh protocol
   - Privacy: Process images locally only

2. **Smart Message Routing**
   - AI-powered content filtering (spam detection)
   - Channel topic classification
   - Auto-moderation suggestions (channel owners)

3. **Privacy Features**
   - All AI processing on-device (no cloud calls)
   - Option to disable AI per channel
   - Clear AI context on emergency wipe
   - Local model storage encryption

4. **Mesh Network AI Sharing** (Experimental)
   - Share AI summaries via mesh (not raw data)
   - Collaborative knowledge base per channel
   - Encrypted AI responses for private chats

5. **Text-to-Speech (TTS) Integration**
   - Android built-in TTS service
   - Speak AI responses automatically
   - Configurable voice settings (rate, pitch, language)
   - Voice output toggle per channel

6. **Automatic Speech Recognition (ASR)**
   - VOSK API for offline speech recognition
   - Real-time voice input for messages
   - Voice commands (`/ask`, `/summarize`, etc.)
   - Push-to-talk button in UI

7. **Retrieval-Augmented Generation (RAG)**
   - ObjectBox vector database integration
   - nomic-embed-text-v1.5 for embeddings
   - Auto-index messages for semantic search
   - Context-aware AI responses using RAG
   - Optional reranking with BGE-reranker model

8. **Voice Mode**
   - Hands-free voice interaction
   - Voice command processor
   - Combined TTS + ASR for conversations
   - Accessibility features

### Phase 4: Polish & Optimization (Week 7-8)
**Goal**: Production-ready, optimized, tested

1. **Performance Optimization**
   - Model quantization (Q4_0 for smaller size)
   - Background inference with WorkManager
   - Memory management (unload models when low memory)
   - Battery impact monitoring

2. **Testing**
   - Unit tests for AIService
   - Integration tests for command processing
   - UI tests for model management
   - Performance benchmarks on real devices

3. **Documentation**
   - User guide for AI commands
   - Developer docs for AI integration
   - Model selection guide
   - Troubleshooting section

4. **Final UI Polish**
   - Animations for AI responses
   - Accessibility improvements
   - Dark/light theme consistency
   - Icon design for AI features

---

## Technical Considerations

### Dependency Compatibility
| Dependency | bitchat-android | nexa-sdk | Conflict? | Resolution |
|------------|----------------|----------|-----------|------------|
| Kotlin | 1.8.0 | 1.9.23 | ⚠️ Minor | Upgrade to 1.9.23 |
| Compose BOM | Latest | Latest | ✅ | Keep latest |
| JVM Target | 1.8 | 11 | ⚠️ | Upgrade to 11 |
| compileSdk | 34 | 36 | ⚠️ | Upgrade to 36 |
| minSdk | 26 | 27 | ⚠️ | Upgrade to 27 (acceptable) |

### Memory Management
- **Model Size**: Qwen3-0.6B (~600MB), Qwen3-1.8B (~1.8GB)
- **Context Window**: Limit to 1024 tokens initially (expandable)
- **Strategy**:
  - Load model on-demand
  - Unload when backgrounded
  - Monitor heap usage
  - Use GPU layers if available (nGpuLayers)

### Battery Impact
- AI inference is CPU/GPU intensive
- **Mitigation**:
  - Warn users before loading large models
  - Integrate with existing PowerManager
  - Reduce batch size in power saver mode
  - Option to disable AI on low battery

### Security & Privacy
- ✅ All AI processing on-device (no cloud)
- ✅ No data leaves device except encrypted mesh messages
- ✅ AI models stored in encrypted storage
- ✅ Clear AI context on emergency wipe
- ⚠️ Model downloads require internet (one-time)

---

## Merged Dependencies

### build.gradle.kts (app level)
```kotlin
dependencies {
    // Existing bitchat dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.bundles.cryptography)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nordic.ble)
    implementation(libs.okhttp)
    implementation("info.guardianproject:arti-mobile-ex:1.2.3")
    implementation(libs.androidx.security.crypto)

    // NEW: Nexa AI SDK
    implementation("ai.nexa:core:0.0.3")

    // NEW: OkDownload for model downloads
    implementation(":okdownload-core@aar")
    implementation(":okdownload-sqlite@aar")
    implementation(":okdownload-okhttp@aar")
    implementation(":okdownload-ktx@aar")

    // NEW: VOSK ASR (Speech Recognition)
    implementation("com.alphacep:vosk-android:0.3.47")

    // NEW: ObjectBox Vector Database (RAG)
    implementation("io.objectbox:objectbox-kotlin:4.0.0")
    implementation("io.objectbox:objectbox-android:4.0.0")

    // TTS is built-in Android (no dependency)

    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

android {
    // Update from bitchat values
    compileSdk = 36  // upgraded

    defaultConfig {
        minSdk = 27  // upgraded from 26
        targetSdk = 36  // upgraded
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11  // upgraded
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"  // upgraded
    }

    // NEW: Required for Nexa SDK native libs
    packagingOptions {
        jniLibs.useLegacyPackaging = true
    }
}

plugins {
    // ... existing plugins

    // NEW: Required for ObjectBox
    id("io.objectbox") version "4.0.0"
}
```

### Permissions (AndroidManifest.xml)
```xml
<!-- Existing bitchat permissions -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />

<!-- NEW: For AI model storage -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />

<!-- NEW: For ASR (Speech Recognition) -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

---

## File Structure Changes

### New Files to Create

```
app/src/main/java/com/bitchat/android/ai/
├── AIService.kt                (Core AI coordinator - 200 lines)
├── AIMessageHandler.kt         (Process AI commands - 150 lines)
├── ModelManager.kt             (Download/load models - 250 lines)
├── ConversationContext.kt      (Chat context manager - 100 lines)
├── AIFeatures.kt               (Summarize, translate, etc. - 200 lines)
├── AIPreferences.kt            (Settings storage - 80 lines)
├── AIModels.kt                 (Model definitions - 50 lines)
├── TTSService.kt               (Text-to-Speech - 120 lines)
├── AITTSManager.kt             (TTS integration - 100 lines)
├── ASRService.kt               (VOSK speech recognition - 200 lines)
├── VoiceInputManager.kt        (Voice input state - 100 lines)
├── VoiceCommandProcessor.kt    (Parse voice commands - 120 lines)
└── rag/
    ├── RAGService.kt           (RAG coordinator - 350 lines)
    ├── RerankerService.kt      (Reranking service - 100 lines)
    └── RAGEnhancedAIService.kt (RAG + AI generation - 150 lines)

app/src/main/java/com/bitchat/android/ui/ai/
├── AIAssistantSheet.kt         (AI chat interface - 300 lines)
├── ModelManagerSheet.kt        (Model download UI - 250 lines)
├── AISettingsSheet.kt          (AI config UI - 200 lines)
├── AIComponents.kt             (Reusable AI UI - 150 lines)
├── VoiceInputButton.kt         (Voice UI component - 100 lines)
├── TTSControls.kt              (TTS UI controls - 80 lines)
└── RAGSettings.kt              (RAG configuration UI - 150 lines)
```

### Files to Modify

```
app/src/main/java/com/bitchat/android/ui/
├── ChatViewModel.kt          (Add AIService, AI commands)
├── CommandProcessor.kt       (Route AI commands)
├── ChatScreen.kt             (Add AI UI elements)
├── MessageComponents.kt      (AI message rendering)
└── SidebarComponents.kt      (Add AI settings entry)

app/src/main/java/com/bitchat/android/
├── MainActivity.kt           (Initialize AIService)
├── BitchatApplication.kt     (NexaSdk.init())
└── model/BitchatMessage.kt   (Add AI message types)
```

### Total LOC Estimate (Updated with TTS/ASR/RAG)
- **New Code**: ~3,500 lines (was ~1,800)
  - Core AI: ~1,030 lines
  - TTS/ASR/Voice: ~820 lines
  - RAG Pipeline: ~600 lines
  - UI Components: ~1,050 lines
- **Modified Code**: ~500 lines (was ~400)
- **Total Impact**: ~4,000 lines (was ~2,200 lines)

---

## Testing Strategy

### Unit Tests
```kotlin
// AIServiceTest.kt
- testModelLoading()
- testGenerateResponse()
- testStreamingInference()
- testContextManagement()
- testErrorHandling()

// AIMessageHandlerTest.kt
- testCommandParsing()
- testSummarization()
- testTranslation()
- testVisionProcessing()

// ConversationContextTest.kt
- testContextAddition()
- testContextRetrieval()
- testContextClear()
- testTokenLimits()
```

### Integration Tests
```kotlin
// AIIntegrationTest.kt
- testEndToEndInference()
- testModelDownloadAndLoad()
- testAICommandInChat()
- testMultiChannelContext()
```

### UI Tests
```kotlin
// AIUITest.kt
- testModelDownloadFlow()
- testAICommandExecution()
- testStreamingResponseDisplay()
- testModelManagementSheet()

// TTSASRTest.kt
- testTTSInitialization()
- testTTSSpeakText()
- testASRModelDownload()
- testVoiceRecognition()
- testVoiceCommands()

// RAGTest.kt
- testEmbeddingGeneration()
- testVectorStorage()
- testSemanticSearch()
- testRAGContextRetrieval()
- testReranking()
```

### Device Testing Matrix
| Device Type | Android Version | RAM | Test Focus |
|-------------|----------------|-----|------------|
| Low-end | Android 9 (API 27) | 2GB | Memory limits, performance |
| Mid-range | Android 12 (API 31) | 4GB | Standard usage |
| High-end | Android 14 (API 34) | 8GB+ | GPU acceleration, large models |

---

## Risk Assessment

### High Risk
1. **Memory Constraints** - AI models may OOM on low-end devices
   - *Mitigation*: Model size warnings, automatic unload, smaller quantized models

2. **Performance Impact** - Inference may block UI or drain battery
   - *Mitigation*: Background threads, streaming, battery monitoring

### Medium Risk
3. **Dependency Conflicts** - Kotlin/Java version mismatches
   - *Mitigation*: Staged upgrade, thorough testing

4. **User Experience** - AI features may feel tacked-on
   - *Mitigation*: Native integration, consistent design language

### Low Risk
5. **Model Availability** - Download failures or model incompatibility
   - *Mitigation*: Fallback models, offline error handling

---

## Success Metrics

### Phase 1 Success
- ✅ App builds and runs with Nexa SDK
- ✅ Can download and load Qwen3-0.6B model
- ✅ `/ask` command generates responses
- ✅ No regressions in mesh networking

### Phase 2 Success
- ✅ AI responses stream in chat
- ✅ Conversation context maintained per channel
- ✅ Summarization works on message history
- ✅ Model management UI complete

### Phase 3 Success
- ✅ VLM model can analyze images
- ✅ AI features integrated across all chat modes
- ✅ Privacy verified (no external calls)
- ✅ Battery impact < 10% increase

### Phase 4 Success
- ✅ All tests passing
- ✅ User documentation complete
- ✅ Performance optimized (inference < 3s on mid-range)
- ✅ Production release ready

---

## Alternative Approaches

### Option A: Nexa SDK (Chosen)
**Pros**: Native Kotlin, actively maintained, Maven Central, VLM support
**Cons**: Newer project, smaller community

### Option B: cui-llama.rn
**Pros**: Feature-rich, established, multimodal, tool calling
**Cons**: Requires React Native integration, complex setup

### Option C: MLKit + Cloud
**Pros**: Easy integration, Google-backed
**Cons**: ❌ Violates privacy goals, requires internet

### Option D: TensorFlow Lite
**Pros**: Mature, well-documented
**Cons**: Limited LLM support, requires model conversion

**Decision**: Start with Nexa SDK (Phase 1-3), evaluate cui-llama.rn for Phase 4+ if needed.

---

## Deployment Plan

### Beta Testing
1. Internal testing (1 week)
2. Closed beta (10-20 users, 2 weeks)
3. Open beta (GitHub releases, 4 weeks)
4. Collect feedback & iterate

### Release Strategy
- **v1.0**: Existing bitchat features
- **v1.1**: AI assistant (Qwen3-0.6B, basic commands)
- **v1.2**: Advanced AI (VLM, summarization, translation)
- **v1.3**: Performance optimizations, polish
- **v2.0**: Full AI integration, mesh AI sharing

### Distribution
- GitHub Releases (primary)
- F-Droid (future)
- Google Play (future, requires compliance review)

---

## Next Steps

### Immediate Actions (This Week)
1. ✅ Complete integration plan review
2. 🔨 Set up development branch (`feature/ai-integration`)
3. 🔨 Update `build.gradle.kts` with Nexa SDK
4. 🔨 Create `ai/` package structure
5. 🔨 Implement basic `AIService.kt`
6. 🔨 Test model download and loading

### Week 1 Goals
- Working AI service initialization
- Basic `/ask` command functional
- Model download UI prototype
- No regressions in existing features

---

## Resources

### Documentation
- [Nexa SDK Android Tutorial](nexa-sdk-examples/android/README.md)
- [bitchat iOS Protocol](https://github.com/jackjackbits/bitchat)
- [llama.cpp Documentation](https://github.com/ggerganov/llama.cpp)
- [Nexa AI SDK GitHub](https://github.com/NexaAI/nexa-sdk)

### Models to Test
- **Qwen3-0.6B-Q8_0**: Lightweight, good for low-end devices
- **Qwen3-1.8B-Q8_0**: Better quality, mid-range devices
- **SmolVLM-256M-Instruct-Q8_0**: VLM for image understanding

### Development Tools
- Android Studio Hedgehog or newer
- Android Device/Emulator with 4GB+ RAM
- Bluetooth LE hardware for mesh testing
- USB OTG for device debugging

---

## Conclusion

SafeGuardian unifies secure mesh communication with on-device AI assistance, creating a unique privacy-focused platform. By integrating bitchat-android with Nexa SDK, we enable:

- **Offline AI assistance** without cloud dependency
- **Encrypted mesh networking** with intelligent features
- **Privacy-first architecture** where no data leaves the device
- **Emergency-ready** communication and AI tools

The phased approach ensures stability while delivering value incrementally. Starting with Nexa SDK provides the fastest path to a working prototype, with flexibility to migrate to cui-llama.rn if more advanced features are needed.

**Estimated Timeline**: 8 weeks to production-ready v1.2
**Team Size**: 1-2 developers
**Risk Level**: Medium (manageable with proposed mitigations)
