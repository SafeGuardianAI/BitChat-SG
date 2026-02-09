# TTS, ASR, and RAG Architecture for SafeGuardian

## Overview

This document extends the SafeGuardian integration plan with **local Text-to-Speech (TTS)**, **Automatic Speech Recognition (ASR)**, and **Retrieval-Augmented Generation (RAG)** capabilities. All processing happens on-device to maintain privacy and enable offline operation.

---

## 1. Text-to-Speech (TTS) Integration

### Architecture

```
User Message → TTS Service → Audio Output
     ↓
AI Response → TTS Service → Speaker/Headphones
```

### Implementation Options

#### Option A: Android Built-in TTS (Recommended)
```kotlin
class TTSService(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    suspend fun initialize() = suspendCoroutine<Boolean> { continuation ->
        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            continuation.resume(isInitialized)
        }
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (isInitialized) {
            tts?.speak(text, queueMode, null, text.hashCode().toString())
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun setLanguage(locale: Locale) {
        tts?.language = locale
    }

    fun shutdown() {
        tts?.shutdown()
    }
}
```

**Pros**:
- Built into Android (no additional dependencies)
- Supports multiple languages
- Low latency, good quality
- Works offline (if language pack installed)

**Cons**:
- Requires user to download language packs
- Quality varies by device manufacturer

#### Option B: Google Cloud TTS On-Device
- Requires Google Play Services
- Higher quality but larger download
- May have licensing considerations

#### Option C: eSpeak-ng (Open Source)
- Fully open source
- Smaller footprint
- Lower quality than Android TTS

**Decision**: Use Android Built-in TTS (Option A) for Phase 1, add eSpeak-ng as fallback in Phase 2.

### TTS Features for SafeGuardian

```kotlin
class AITTSManager(
    private val context: Context,
    private val ttsService: TTSService
) {
    private val preferences = context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)

    // User configurable settings
    var enabled: Boolean
        get() = preferences.getBoolean("tts_enabled", false)
        set(value) = preferences.edit().putBoolean("tts_enabled", value).apply()

    var speakAIResponses: Boolean
        get() = preferences.getBoolean("speak_ai_responses", true)
        set(value) = preferences.edit().putBoolean("speak_ai_responses", value).apply()

    var speakIncomingMessages: Boolean
        get() = preferences.getBoolean("speak_incoming_messages", false)
        set(value) = preferences.edit().putBoolean("speak_incoming_messages", value).apply()

    var speechRate: Float
        get() = preferences.getFloat("speech_rate", 1.0f)
        set(value) = preferences.edit().putFloat("speech_rate", value).apply()

    var pitch: Float
        get() = preferences.getFloat("pitch", 1.0f)
        set(value) = preferences.edit().putFloat("pitch", value).apply()

    suspend fun speakAIResponse(text: String) {
        if (enabled && speakAIResponses) {
            ttsService.speak(text, TextToSpeech.QUEUE_ADD)
        }
    }

    suspend fun speakMessage(message: BitchatMessage) {
        if (enabled && speakIncomingMessages) {
            val spokenText = "${message.senderID}: ${message.content}"
            ttsService.speak(spokenText, TextToSpeech.QUEUE_ADD)
        }
    }

    fun stopSpeaking() {
        ttsService.stop()
    }
}
```

### UI Integration

```kotlin
// Add to ChatScreen.kt
@Composable
fun TTSControls(aiTTSManager: AITTSManager) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Switch(
            checked = aiTTSManager.enabled,
            onCheckedChange = { aiTTSManager.enabled = it },
            modifier = Modifier.padding(8.dp)
        )

        IconButton(onClick = { aiTTSManager.stopSpeaking() }) {
            Icon(Icons.Default.Stop, "Stop speaking")
        }
    }
}
```

---

## 2. Automatic Speech Recognition (ASR) Integration

### Architecture

```
Microphone → ASR Service → Text Input
     ↓
Voice Command → ASR → Command Processor → AI/Chat
```

### Implementation Options

#### Option A: VOSK API (Recommended)
**Pros**:
- Fully offline
- Open source (Apache 2.0)
- Supports 20+ languages
- Low latency (real-time streaming)
- Compact models (50MB - 1.8GB)
- Works on ARM (mobile-optimized)

**Cons**:
- Lower accuracy than cloud services
- Requires model download

#### Option B: Whisper (OpenAI) via TensorFlow Lite
**Pros**:
- State-of-the-art accuracy
- Multilingual support
- Good for transcription

**Cons**:
- Larger models (70MB - 1.5GB)
- Higher latency (not real-time)
- More CPU intensive

#### Option C: Android Built-in SpeechRecognizer
**Pros**:
- No setup required
- Good accuracy

**Cons**:
- Requires internet by default
- Offline requires Google Voice Typing download
- Privacy concerns (may phone home)

**Decision**: Use VOSK API (Option A) for Phase 1 - best balance of privacy, offline capability, and performance.

### VOSK Integration

#### build.gradle.kts
```kotlin
dependencies {
    // VOSK ASR
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Permissions already in bitchat
    // android.permission.RECORD_AUDIO (add to manifest)
}
```

#### ASRService.kt
```kotlin
package com.bitchat.android.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File

class ASRService(private val context: Context) {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private val sampleRate = 16000f

    companion object {
        private const val TAG = "ASRService"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Download model if not present
            val modelPath = File(context.filesDir, "vosk-model-small-en-us-0.15")

            if (!modelPath.exists()) {
                Log.d(TAG, "Downloading VOSK model...")
                StorageService.unpack(
                    context,
                    "vosk-model-small-en-us-0.15.zip",
                    "vosk-model-small-en-us-0.15",
                    { model ->
                        this@ASRService.model = model
                    },
                    { exception ->
                        Log.e(TAG, "Failed to unpack model", exception)
                    }
                )
                true
            } else {
                model = Model(modelPath.absolutePath)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASR initialization failed", e)
            false
        }
    }

    fun startListening(onResult: (String) -> Unit, onPartialResult: (String) -> Unit) {
        model?.let { m ->
            speechService = SpeechService(
                m,
                sampleRate
            ).apply {
                startListening(object : RecognitionListener {
                    override fun onResult(hypothesis: String?) {
                        hypothesis?.let {
                            val result = parseVoskResult(it)
                            onResult(result)
                        }
                    }

                    override fun onPartialResult(hypothesis: String?) {
                        hypothesis?.let {
                            val result = parseVoskResult(it)
                            onPartialResult(result)
                        }
                    }

                    override fun onError(exception: Exception?) {
                        Log.e(TAG, "Recognition error", exception)
                    }

                    override fun onTimeout() {
                        Log.d(TAG, "Recognition timeout")
                    }
                })
            }
        }
    }

    fun stopListening() {
        speechService?.stop()
        speechService = null
    }

    private fun parseVoskResult(json: String): String {
        // VOSK returns JSON: {"text": "recognized text"}
        return try {
            val textStart = json.indexOf("\"text\" : \"") + 10
            val textEnd = json.indexOf("\"", textStart)
            if (textStart > 9 && textEnd > textStart) {
                json.substring(textStart, textEnd)
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun shutdown() {
        stopListening()
        model?.close()
        model = null
    }
}
```

#### VoiceInputManager.kt
```kotlin
package com.bitchat.android.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VoiceInputManager(
    private val context: Context,
    private val asrService: ASRService
) {
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    private val _finalText = MutableStateFlow("")
    val finalText: StateFlow<String> = _finalText

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startListening() {
        if (!hasPermission()) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        _isListening.value = true
        asrService.startListening(
            onResult = { text ->
                _finalText.value = text
                _isListening.value = false
            },
            onPartialResult = { text ->
                _partialText.value = text
            }
        )
    }

    fun stopListening() {
        asrService.stopListening()
        _isListening.value = false
    }

    fun clearText() {
        _partialText.value = ""
        _finalText.value = ""
    }
}
```

### ASR UI Components

```kotlin
@Composable
fun VoiceInputButton(
    voiceInputManager: VoiceInputManager,
    onTextRecognized: (String) -> Unit
) {
    val isListening by voiceInputManager.isListening.collectAsState()
    val partialText by voiceInputManager.partialText.collectAsState()
    val finalText by voiceInputManager.finalText.collectAsState()

    LaunchedEffect(finalText) {
        if (finalText.isNotEmpty()) {
            onTextRecognized(finalText)
            voiceInputManager.clearText()
        }
    }

    Column {
        IconButton(
            onClick = {
                if (isListening) {
                    voiceInputManager.stopListening()
                } else {
                    if (voiceInputManager.hasPermission()) {
                        voiceInputManager.startListening()
                    } else {
                        // Request permission
                    }
                }
            }
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = "Voice input",
                tint = if (isListening) Color.Red else Color.Gray
            )
        }

        if (partialText.isNotEmpty()) {
            Text(
                text = partialText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
```

### Voice Command Processing

```kotlin
class VoiceCommandProcessor(
    private val commandProcessor: CommandProcessor
) {
    suspend fun processVoiceInput(recognizedText: String): String? {
        // Check if it's a command
        if (recognizedText.startsWith("command ") || recognizedText.startsWith("slash ")) {
            val command = recognizedText.removePrefix("command ").removePrefix("slash ")
            return commandProcessor.processCommand("/$command")
        }

        // Check for natural language commands
        return when {
            recognizedText.contains("ask", ignoreCase = true) -> {
                val question = recognizedText.substringAfter("ask", "")
                commandProcessor.processCommand("/ask $question")
            }
            recognizedText.contains("summarize", ignoreCase = true) -> {
                commandProcessor.processCommand("/summarize 10")
            }
            recognizedText.contains("translate", ignoreCase = true) -> {
                val text = recognizedText.substringAfter("translate", "")
                commandProcessor.processCommand("/translate es $text")
            }
            else -> null // Regular message
        }
    }
}
```

---

## 3. Retrieval-Augmented Generation (RAG) Pipeline

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    RAG Pipeline                          │
│                                                          │
│  [Documents] → [Chunking] → [Embedding] → [Vector DB]   │
│                                     ↓                    │
│  [Query] → [Embedding] → [Similarity Search] → [Rerank] │
│                                     ↓                    │
│  [Retrieved Context] + [Query] → [LLM] → [Response]     │
└─────────────────────────────────────────────────────────┘
```

### Components

#### 1. Embedding Models

**Nexa SDK supports embedding** (from cui-llama.rn README):
```kotlin
// Using Nexa SDK for embeddings
val embeddingContext = LlmWrapper.builder().llmCreateInput(
    LlmCreateInput(
        model_path = "path/to/nomic-embed-text-v1.5.gguf",
        config = ModelConfig(
            nCtx = 512,
            // embedding-specific config
        )
    )
).build().getOrThrow()

// Generate embedding
val embedding = embeddingContext.embedding("text to embed")
```

**Recommended Models**:
- **nomic-embed-text-v1.5-GGUF** (137M params, ~270MB quantized)
  - Best for English text
  - 768-dimensional embeddings
  - Trained for RAG applications

- **all-MiniLM-L6-v2** (23M params, ~80MB)
  - Smaller, faster
  - 384-dimensional embeddings
  - Good for resource-constrained devices

#### 2. Vector Database - ObjectBox

**Why ObjectBox?**
- Native Android/Kotlin support
- On-device vector search
- No external dependencies
- HNSW index for fast similarity search
- 100% private (data never leaves device)

**build.gradle.kts**
```kotlin
plugins {
    id("io.objectbox") version "4.0.0"
}

dependencies {
    implementation("io.objectbox:objectbox-kotlin:4.0.0")
    implementation("io.objectbox:objectbox-android:4.0.0")
}
```

**Data Models**
```kotlin
package com.bitchat.android.ai.rag

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.VectorDistanceType

@Entity
data class MessageEmbedding(
    @Id var id: Long = 0,

    // Metadata
    var messageId: String = "",
    var channelId: String? = null,
    var senderId: String = "",
    var timestamp: Long = 0,

    // Text content
    var content: String = "",
    var contentChunk: String = "",  // For long messages split into chunks
    var chunkIndex: Int = 0,

    // Vector embedding
    @HnswIndex(
        dimensions = 768,  // nomic-embed dimensions
        distanceType = VectorDistanceType.COSINE,
        maxElements = 10000,
        m = 16,
        efConstruction = 200
    )
    var embedding: FloatArray? = null,

    // For filtering
    var isPrivateChat: Boolean = false,
    var language: String? = null
)

@Entity
data class DocumentEmbedding(
    @Id var id: Long = 0,

    // Document metadata
    var documentId: String = "",
    var title: String = "",
    var source: String = "",  // e.g., "manual", "faq", "wiki"
    var url: String? = null,

    // Content
    var content: String = "",
    var contentChunk: String = "",
    var chunkIndex: Int = 0,

    // Vector
    @HnswIndex(
        dimensions = 768,
        distanceType = VectorDistanceType.COSINE,
        maxElements = 50000,
        m = 16,
        efConstruction = 200
    )
    var embedding: FloatArray? = null,

    // Metadata for filtering
    var category: String? = null,
    var tags: String? = null  // Comma-separated
)
```

#### 3. RAG Service Implementation

```kotlin
package com.bitchat.android.ai.rag

import android.content.Context
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nexa.sdk.LlmWrapper

class RAGService(
    private val context: Context,
    private val boxStore: BoxStore,
    private val embeddingModel: LlmWrapper
) {
    private val messageEmbeddingBox: Box<MessageEmbedding> = boxStore.boxFor()
    private val documentEmbeddingBox: Box<DocumentEmbedding> = boxStore.boxFor()

    companion object {
        private const val CHUNK_SIZE = 512  // tokens
        private const val CHUNK_OVERLAP = 50
        private const val TOP_K = 5
    }

    /**
     * Index a message for later retrieval
     */
    suspend fun indexMessage(
        messageId: String,
        content: String,
        channelId: String?,
        senderId: String,
        timestamp: Long,
        isPrivateChat: Boolean
    ) = withContext(Dispatchers.IO) {
        try {
            // Split long messages into chunks
            val chunks = chunkText(content)

            chunks.forEachIndexed { index, chunk ->
                // Generate embedding
                val embedding = embeddingModel.embedding(chunk).getOrNull()?.embedding

                if (embedding != null) {
                    val messageEmbedding = MessageEmbedding(
                        messageId = messageId,
                        channelId = channelId,
                        senderId = senderId,
                        timestamp = timestamp,
                        content = content,
                        contentChunk = chunk,
                        chunkIndex = index,
                        embedding = embedding,
                        isPrivateChat = isPrivateChat
                    )

                    messageEmbeddingBox.put(messageEmbedding)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RAGService", "Failed to index message", e)
        }
    }

    /**
     * Search for relevant messages using semantic similarity
     */
    suspend fun searchMessages(
        query: String,
        channelId: String? = null,
        topK: Int = TOP_K,
        includePrivateChats: Boolean = false
    ): List<MessageEmbedding> = withContext(Dispatchers.IO) {
        try {
            // Generate query embedding
            val queryEmbedding = embeddingModel.embedding(query).getOrNull()?.embedding
                ?: return@withContext emptyList()

            // Build query with filters
            val queryBuilder = messageEmbeddingBox.query()

            if (channelId != null) {
                queryBuilder.equal(MessageEmbedding_.channelId, channelId)
            }

            if (!includePrivateChats) {
                queryBuilder.equal(MessageEmbedding_.isPrivateChat, false)
            }

            // Perform nearest neighbor search
            queryBuilder.nearestNeighbors(
                MessageEmbedding_.embedding,
                queryEmbedding,
                topK
            )

            queryBuilder.build().find()
        } catch (e: Exception) {
            android.util.Log.e("RAGService", "Search failed", e)
            emptyList()
        }
    }

    /**
     * Get context for AI generation
     */
    suspend fun getContextForQuery(
        query: String,
        channelId: String?,
        maxTokens: Int = 1024
    ): String {
        val results = searchMessages(query, channelId, topK = 5)

        return buildString {
            appendLine("Relevant context:")
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. [${result.senderId}]: ${result.contentChunk}")
            }
        }
    }

    /**
     * Index a document (manual, FAQ, etc.)
     */
    suspend fun indexDocument(
        documentId: String,
        title: String,
        content: String,
        source: String,
        category: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val chunks = chunkText(content)

            chunks.forEachIndexed { index, chunk ->
                val embedding = embeddingModel.embedding(chunk).getOrNull()?.embedding

                if (embedding != null) {
                    val docEmbedding = DocumentEmbedding(
                        documentId = documentId,
                        title = title,
                        source = source,
                        content = content,
                        contentChunk = chunk,
                        chunkIndex = index,
                        embedding = embedding,
                        category = category
                    )

                    documentEmbeddingBox.put(docEmbedding)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RAGService", "Failed to index document", e)
        }
    }

    /**
     * Search documents (manuals, FAQs, etc.)
     */
    suspend fun searchDocuments(
        query: String,
        source: String? = null,
        topK: Int = TOP_K
    ): List<DocumentEmbedding> = withContext(Dispatchers.IO) {
        try {
            val queryEmbedding = embeddingModel.embedding(query).getOrNull()?.embedding
                ?: return@withContext emptyList()

            val queryBuilder = documentEmbeddingBox.query()

            if (source != null) {
                queryBuilder.equal(DocumentEmbedding_.source, source)
            }

            queryBuilder.nearestNeighbors(
                DocumentEmbedding_.embedding,
                queryEmbedding,
                topK
            )

            queryBuilder.build().find()
        } catch (e: Exception) {
            android.util.Log.e("RAGService", "Document search failed", e)
            emptyList()
        }
    }

    /**
     * Chunk text into smaller pieces for embedding
     */
    private fun chunkText(text: String, chunkSize: Int = CHUNK_SIZE): List<String> {
        // Simple word-based chunking
        val words = text.split(" ")
        val chunks = mutableListOf<String>()

        var currentChunk = StringBuilder()
        var wordCount = 0

        for (word in words) {
            currentChunk.append(word).append(" ")
            wordCount++

            if (wordCount >= chunkSize) {
                chunks.add(currentChunk.toString().trim())
                // Keep overlap
                val overlapWords = currentChunk.toString().split(" ").takeLast(CHUNK_OVERLAP)
                currentChunk = StringBuilder(overlapWords.joinToString(" ") + " ")
                wordCount = CHUNK_OVERLAP
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.ifEmpty { listOf(text) }
    }

    /**
     * Clear all embeddings (for privacy/emergency wipe)
     */
    fun clearAllEmbeddings() {
        messageEmbeddingBox.removeAll()
        documentEmbeddingBox.removeAll()
    }

    /**
     * Clear embeddings for a specific channel
     */
    fun clearChannelEmbeddings(channelId: String) {
        val query = messageEmbeddingBox.query()
            .equal(MessageEmbedding_.channelId, channelId)
            .build()
        query.remove()
    }
}
```

#### 4. Reranking (Optional Enhancement)

Nexa SDK supports reranking via specialized models:

```kotlin
class RerankerService(
    private val context: Context
) {
    private var reranker: LlmWrapper? = null

    suspend fun initialize() {
        reranker = LlmWrapper.builder().llmCreateInput(
            LlmCreateInput(
                model_path = "path/to/bge-reranker-v2-m3.gguf",
                config = ModelConfig(
                    pooling_type = "rank"  // Important for reranker models
                )
            )
        ).build().getOrNull()
    }

    suspend fun rerank(
        query: String,
        documents: List<String>,
        topK: Int = 5
    ): List<Pair<String, Float>> {
        val results = reranker?.rerank(query, documents.toTypedArray(), null)
            ?.getOrNull()
            ?: return documents.map { it to 0f }

        return results.sortedByDescending { it.score }
            .take(topK)
            .map { it.document to it.score }
    }
}
```

#### 5. RAG-Enhanced AI Generation

```kotlin
class RAGEnhancedAIService(
    private val aiService: AIService,
    private val ragService: RAGService,
    private val rerankerService: RerankerService? = null
) {
    suspend fun generateWithContext(
        query: String,
        channelId: String?,
        useRAG: Boolean = true
    ): Flow<String> = flow {
        if (!useRAG) {
            // Standard generation without RAG
            aiService.generateResponse(query).collect { emit(it) }
            return@flow
        }

        // 1. Retrieve relevant context
        val retrievedMessages = ragService.searchMessages(
            query = query,
            channelId = channelId,
            topK = 10
        )

        // 2. Optionally rerank results
        val rankedMessages = if (rerankerService != null) {
            val docs = retrievedMessages.map { it.contentChunk }
            val reranked = rerankerService.rerank(query, docs, topK = 5)
            reranked.map { (doc, score) ->
                retrievedMessages.first { it.contentChunk == doc }
            }
        } else {
            retrievedMessages.take(5)
        }

        // 3. Build prompt with context
        val contextPrompt = buildString {
            appendLine("Context from previous messages:")
            rankedMessages.forEachIndexed { index, msg ->
                appendLine("${index + 1}. [${msg.senderId}]: ${msg.contentChunk}")
            }
            appendLine()
            appendLine("User question: $query")
            appendLine()
            appendLine("Answer based on the context above:")
        }

        // 4. Generate with context
        aiService.generateResponse(contextPrompt).collect { emit(it) }
    }
}
```

### RAG UI Integration

```kotlin
@Composable
fun RAGSettings(ragService: RAGService, viewModel: ChatViewModel) {
    var ragEnabled by remember { mutableStateOf(true) }
    var indexingEnabled by remember { mutableStateOf(true) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("RAG Settings", style = MaterialTheme.typography.titleLarge)

        SwitchRow(
            label = "Enable RAG for AI queries",
            checked = ragEnabled,
            onCheckedChange = { ragEnabled = it }
        )

        SwitchRow(
            label = "Auto-index messages",
            checked = indexingEnabled,
            onCheckedChange = { indexingEnabled = it }
        )

        Button(
            onClick = {
                viewModel.viewModelScope.launch {
                    ragService.clearAllEmbeddings()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text("Clear All Embeddings")
        }

        Text(
            text = "Note: Embeddings are stored locally and never leave your device.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}
```

---

## Integration with SafeGuardian

### Updated Architecture

```
┌───────────────────────────────────────────────────────────┐
│                   SafeGuardian App                         │
│                                                            │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Mesh     │  │  AI Service  │  │  RAG Service │      │
│  │ Networking │  │   (Nexa)     │  │  (ObjectBox) │      │
│  └─────┬──────┘  └──────┬───────┘  └──────┬───────┘      │
│        │                │                  │              │
│        └────────────────┴──────────────────┘              │
│                         ↓                                 │
│            ┌────────────────────────┐                     │
│            │   ChatViewModel         │                    │
│            └────────────────────────┘                     │
│                         ↓                                 │
│     ┌──────────────────────────────────────┐             │
│     │  TTS    │   ASR    │   Voice Commands │             │
│     └──────────────────────────────────────┘             │
│                                                            │
└───────────────────────────────────────────────────────────┘
```

### Enhanced AI Commands

```
/ask [question]              - Ask AI with RAG context
/ask-raw [question]          - Ask AI without RAG
/summarize [n]               - Summarize last N messages
/search [query]              - Semantic search through messages
/index-docs [path]           - Index documents for RAG
/voice-mode                  - Enable voice input/output mode
/speak [text]                - Speak text via TTS
/listen                      - Listen for voice input
```

### Privacy Considerations

1. **TTS/ASR**: All processing on-device, no audio leaves device
2. **RAG**: Embeddings stored locally in encrypted ObjectBox database
3. **Vector Search**: HNSW index computed locally
4. **Emergency Wipe**: Clears all embeddings, models, and voice data
5. **Opt-in**: Users must explicitly enable TTS/ASR/RAG features

---

## Dependencies Summary

```kotlin
// build.gradle.kts (app)
dependencies {
    // Existing bitchat dependencies
    implementation("com.bitchat.android:...")

    // AI/LLM
    implementation("ai.nexa:core:0.0.3")

    // ASR (Speech Recognition)
    implementation("com.alphacep:vosk-android:0.3.47")

    // Vector Database
    implementation("io.objectbox:objectbox-kotlin:4.0.0")
    implementation("io.objectbox:objectbox-android:4.0.0")

    // TTS is built-in Android (no dependency needed)

    // Model downloading (already in nexa-sdk-examples)
    implementation(":okdownload-core@aar")
    implementation(":okdownload-okhttp@aar")
}

plugins {
    id("io.objectbox") version "4.0.0"
}
```

### Permissions (AndroidManifest.xml)

```xml
<!-- Existing -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- NEW for ASR -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- TTS uses no additional permissions -->
```

---

## Model Downloads

### Required Models for Full Feature Set

| Feature | Model | Size | Purpose |
|---------|-------|------|---------|
| LLM | Qwen3-0.6B-Q8_0 | ~600MB | Text generation |
| Embedding | nomic-embed-text-v1.5-Q8_0 | ~270MB | RAG embeddings |
| Reranker | bge-reranker-v2-m3-Q8_0 | ~220MB | Context reranking (optional) |
| ASR | vosk-model-small-en-us | ~40MB | Speech recognition |
| VLM | SmolVLM-256M-Q8_0 | ~260MB | Vision (Phase 3) |
| **Total** | | **~1.4GB** | (2.1GB with all optional) |

**Storage Recommendations**:
- Minimum: 2GB free space
- Recommended: 4GB free space
- Warn user before download if space is limited

---

## Testing Strategy

### TTS Testing
```kotlin
class TTSServiceTest {
    @Test
    fun testInitialization()

    @Test
    fun testSpeakText()

    @Test
    fun testLanguageSwitch()

    @Test
    fun testStopSpeaking()
}
```

### ASR Testing
```kotlin
class ASRServiceTest {
    @Test
    fun testModelDownload()

    @Test
    fun testRecognition()

    @Test
    fun testRealtimeStreaming()

    @Test
    fun testVoiceCommands()
}
```

### RAG Testing
```kotlin
class RAGServiceTest {
    @Test
    fun testEmbeddingGeneration()

    @Test
    fun testVectorStorage()

    @Test
    fun testSemanticSearch()

    @Test
    fun testReranking()

    @Test
    fun testContextBuilding()
}
```

---

## Performance Considerations

### Memory Usage
- **LLM**: 600MB - 2GB (model dependent)
- **Embedding Model**: 270MB
- **Vector Database**: ~1KB per message embedding
- **ASR Model**: 40MB - 1.8GB (model dependent)
- **TTS**: Minimal (system service)

**Total Peak Memory**: ~1.5GB - 4GB

**Optimization**:
- Lazy load models (load on first use)
- Unload embeddings model after batch indexing
- Use model quantization (Q4_0, Q8_0)
- Limit vector DB size (prune old embeddings)

### Battery Impact
- **TTS**: Minimal (native Android)
- **ASR**: Low (optimized for mobile)
- **RAG**: Medium (one-time indexing cost)
- **LLM Inference**: High (mitigate with batching)

### Latency
- **TTS**: <100ms
- **ASR**: Real-time (streaming)
- **Vector Search**: <50ms for 10k embeddings
- **Reranking**: ~500ms for 10 documents
- **LLM Generation**: 1-5 tokens/sec (device dependent)

---

## Next Steps

1. ✅ Design TTS/ASR/RAG architecture
2. 🔨 Update INTEGRATION_PLAN.md with TTS/ASR/RAG phases
3. 🔨 Implement TTS service (Android built-in)
4. 🔨 Integrate VOSK ASR
5. 🔨 Add ObjectBox for vector storage
6. 🔨 Implement RAG pipeline
7. 🔨 Build voice command system
8. 🔨 Create UI for voice mode
9. 🔨 Test end-to-end voice-to-AI workflow
10. 🔨 Optimize memory and battery usage

---

## Conclusion

SafeGuardian now includes a comprehensive **multimodal AI architecture**:

- **Voice Input**: VOSK ASR for local speech recognition
- **Voice Output**: Android TTS for natural AI responses
- **Contextual AI**: RAG with ObjectBox vector search
- **Privacy-First**: All processing on-device
- **Offline-Ready**: Works without internet after model download

This creates a unique **voice-enabled, context-aware AI assistant** integrated into a secure mesh communication platform - perfect for hands-free operation, accessibility, and emergency scenarios.
