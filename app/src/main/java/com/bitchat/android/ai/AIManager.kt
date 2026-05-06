package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import com.bitchat.android.audio.AudioPipeline
import com.bitchat.android.audio.PipelineEvent
import com.bitchat.android.device.ConnectivityMonitor
import com.bitchat.android.device.DeviceStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AIManager - Main AI management class
 * Coordinates AI services, models, and preferences
 */
class AIManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AIManager"
        private const val WAKE_WORD = "help"
        private var instance: AIManager? = null
        
        fun getInstance(context: Context): AIManager {
            if (instance == null) {
                instance = AIManager(context.applicationContext)
            }
            return instance!!
        }
    }
    
    val preferences = AIPreferences(context)
    val aiService: AIService = NexaLlmService(context)
    val modelManager = ModelManager(context)
    val conversationContext = ConversationContext(context)
    val ttsService = TTSService(context)
    val disasterTtsService = DisasterTTSService(ttsService, preferences)
    val deviceCapability = DeviceCapabilityService.getInstance(context)
    val deviceStateManager = DeviceStateManager(context)
    val connectivityMonitor = ConnectivityMonitor(context)
    val audioPipeline = AudioPipeline(context)

    val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val speechRecognitionService = SpeechRecognitionService(context)

    private val _keywordTriggeredTranscription =
        MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val keywordTriggeredTranscription: SharedFlow<String> =
        _keywordTriggeredTranscription.asSharedFlow()

    private var watcherJob: Job? = null

    private val rerankerService = RerankerService(context, preferences)
    private val ragService = RAGService(context, preferences, rerankerService)
    private val documentManager = RAGDocumentManager(context)

    private var initialized = false
    
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing AI Manager")
            ragService.initialize()
            deviceStateManager.startMonitoring(managerScope)
            connectivityMonitor.registerCallback()
            initialized = true
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            Result.failure(e)
        }
    }
    
    fun isAIReady(): Boolean = initialized && preferences.aiEnabled

    /**
     * Auto-enable AI if a model is downloaded. Call on app startup
     * so users don't need to run /ai on manually.
     */
    suspend fun autoEnableIfModelReady(): Boolean = withContext(Dispatchers.IO) {
        // Try the richer AIModel path first (NPU-aware); fall back to basic ModelInfo
        val aiModel = preferences.getSelectedAIModel()
        val simpleModel = preferences.getSelectedLLMModel()

        val isReady = when {
            aiModel != null -> {
                val info = ModelInfo(
                    id = aiModel.id, name = aiModel.name,
                    fileSizeMB = aiModel.fileSizeMB, downloadUrl = aiModel.downloadUrl
                )
                modelManager.isModelDownloaded(info)
            }
            simpleModel != null -> modelManager.isModelDownloaded(simpleModel)
            else -> false
        }

        if (isReady) {
            if (!initialized) initialize()
            if (!aiService.isModelLoaded()) {
                if (aiModel != null) {
                    val cap = deviceCapability.getCapability()
                    aiService.loadAIModel(aiModel, cap.socInfo.qnnTier)
                } else if (simpleModel != null) {
                    aiService.loadModel(simpleModel)
                }
            }
            preferences.aiEnabled = true
            val name = aiModel?.name ?: simpleModel?.name ?: "unknown"
            Log.d(TAG, "Auto-enabled AI with model: $name")
            true
        } else {
            false
        }
    }
    
    fun isRAGReady(): Boolean = ragService.isReady()

    fun isRerankerReady(): Boolean = rerankerService.isReady()

    /**
     * Feature availability based on device tier + AI state.
     *
     * Gallery pattern: explicitly show which features are available on the current hardware
     * instead of silently failing. Responders need to know their AI capability before
     * they rely on it.
     */
    data class FeatureSet(
        val llmAvailable: Boolean,
        val ragAvailable: Boolean,
        val ttsAvailable: Boolean,
        val npuAvailable: Boolean,
        val tier: DeviceCapabilityService.DeviceTier,
        val degradationReason: String?   // non-null when features are limited
    )

    suspend fun getAvailableFeatures(): FeatureSet {
        val cap = deviceCapability.getCapability()
        val degraded = cap.tier == DeviceCapabilityService.DeviceTier.LOW
        return FeatureSet(
            llmAvailable     = isAIReady() && !degraded,
            ragAvailable     = isRAGReady() && !degraded,
            ttsAvailable     = preferences.ttsEnabled,
            npuAvailable     = cap.supportsNPU,
            tier             = cap.tier,
            degradationReason = when {
                degraded && !isAIReady()  -> "Low RAM device — AI disabled to preserve stability"
                degraded                  -> "Low RAM device — some AI features may be slow"
                !isAIReady()              -> null
                else                      -> null
            }
        )
    }
    
    fun getStatus(): AIStatus = AIStatus(
        isReady = isAIReady(),
        modelLoaded = aiService.isModelLoaded(),
        modelName = preferences.getSelectedLLMModel()?.name,
        ragReady = isRAGReady(),
        asrReady = ASRService.isModelAvailable(context) ||
            android.speech.SpeechRecognizer.isRecognitionAvailable(context),
        ttsEnabled = preferences.ttsEnabled
    )
    
    fun getRAGService(): RAGService = ragService
    
    fun getRAGStats(): RAGStats = ragService.getIndexStats()
    
    suspend fun enableAI(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!initialized) initialize()

            val aiModel = preferences.getSelectedAIModel()
            val simpleModel = preferences.getSelectedLLMModel()

            if (aiModel != null) {
                val info = ModelInfo(
                    id = aiModel.id, name = aiModel.name,
                    fileSizeMB = aiModel.fileSizeMB, downloadUrl = aiModel.downloadUrl
                )
                if (modelManager.isModelDownloaded(info)) {
                    val cap = deviceCapability.getCapability()
                    aiService.loadAIModel(aiModel, cap.socInfo.qnnTier)
                }
            } else if (simpleModel != null && modelManager.isModelDownloaded(simpleModel)) {
                aiService.loadModel(simpleModel)
            }

            preferences.aiEnabled = true
            Log.d(TAG, "AI enabled")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable AI", e)
            Result.failure(e)
        }
    }
    
    fun disableAI() {
        preferences.aiEnabled = false
        Log.d(TAG, "AI disabled")
    }
    
    suspend fun initializeRAGWithDocuments(): Result<Int> = withContext(Dispatchers.IO) {
        documentManager.initializeRAGWithSampleDocuments(this@AIManager)
    }
    
    fun getRerankerStatus(): String {
        return if (preferences.rerankEnabled) "enabled" else "disabled"
    }
    
    fun getDocumentManager(): RAGDocumentManager = documentManager
    
    fun getModelSelectionStatus(): String {
        val model = preferences.getSelectedLLMModel()
        return if (model != null) {
            val downloaded = modelManager.isModelDownloaded(model)
            "${model.name} (${if (downloaded) "downloaded" else "not downloaded"})"
        } else {
            "No model selected"
        }
    }
    
    suspend fun recoverFromCrash(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting crash recovery")
            aiService.unloadModel()
            initialized = false
            initialize()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Start the VAD/keyword background pipeline. Call once after RECORD_AUDIO
     * permission is granted. No-op if the pipeline is already running.
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startVadPipeline(mode: AudioPipeline.PipelineMode = AudioPipeline.PipelineMode.VAD_KEYWORD) {
        audioPipeline.start(mode, managerScope)
        startKeywordTriggerWatcher()
    }

    /**
     * Subscribe to pipeline events and fire a full ASR session when the
     * wake word ("help") is detected. The resulting transcription is emitted
     * to [keywordTriggeredTranscription] so the UI can auto-send it.
     *
     * Idempotent — safe to call multiple times.
     */
    fun startKeywordTriggerWatcher() {
        if (watcherJob?.isActive == true) return
        watcherJob = managerScope.launch {
            audioPipeline.pipelineEvents.collect { event ->
                if (event is PipelineEvent.KeywordDetected &&
                    event.detection.keyword.equals(WAKE_WORD, ignoreCase = true)) {
                    setAsrActive(true)
                    try {
                        val transcription = withContext(Dispatchers.Main) {
                            speechRecognitionService.recognizeFromMicrophone()
                        }
                        if (!transcription.isNullOrBlank()) {
                            _keywordTriggeredTranscription.tryEmit(transcription)
                        }
                    } finally {
                        setAsrActive(false)
                    }
                }
            }
        }
    }

    /** Notify the VAD pipeline that AsrAudioRecorder is taking the mic. */
    fun setAsrActive(active: Boolean) {
        audioPipeline.setAsrActive(active)
    }
}
