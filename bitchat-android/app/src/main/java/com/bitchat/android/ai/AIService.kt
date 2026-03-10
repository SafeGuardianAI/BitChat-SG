package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.DeviceIdValue
import com.nexa.sdk.bean.ModelConfig as NexaModelConfig
import android.speech.tts.TextToSpeech
import com.bitchat.android.ai.functions.FunctionRegistry
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * Core AI service coordinating LLM inference
 *
 * Features:
 * - Model loading/unloading with auto-unload
 * - Streaming text generation
 * - Power-aware configuration
 * - Memory management
 * - Thread-safe operations
 */
class AIService(
    private val context: Context,
    private val modelManager: ModelManager,
    private val preferences: AIPreferences
) {

    companion object {
        private const val TAG = "AIService"
        private const val AUTO_UNLOAD_DELAY_MS = 300_000L  // 5 minutes
        private const val GENERATION_TIMEOUT_MS = 60_000L  // 1 minute timeout for generation
        private const val MAX_RESPONSE_LENGTH = 10_000     // Max response length to prevent memory issues
    }

    // Current loaded model
    private var llmWrapper: LlmWrapper? = null
    private var currentModelId: String? = null
    private val autoUnloadTimer = Timer("AIService-AutoUnload", true)
    private var unloadTask: java.util.TimerTask? = null

    // Initialization state
    private var isNexaInitialized = false

    // TTS service
    private val ttsService = TTSService(context)

    /**
     * Nexa manifest data — matches cookbook's NexaManifestBean
     */
    private data class NexaManifest(
        val modelName: String?,
        val modelType: String?,
        val pluginId: String?
    )

    /**
     * Read nexa.manifest from model directory (used by NPU models)
     */
    private fun readNexaManifest(modelDir: File): NexaManifest? {
        val manifestFile = File(modelDir, "nexa.manifest")
        if (!manifestFile.exists()) {
            Log.d(TAG, "No nexa.manifest found in ${modelDir.absolutePath}")
            return null
        }
        return try {
            val json = manifestFile.readText()
            val obj = JSONObject(json)
            // Handle both manifest formats: SDK uses "ModelName", some manifests use "Name"
            val manifest = NexaManifest(
                modelName = obj.optString("ModelName", null)
                    ?: obj.optString("Name", null),
                modelType = obj.optString("ModelType", null),
                pluginId = obj.optString("PluginId", null)
            )
            Log.d(TAG, "Read nexa.manifest: name=${manifest.modelName}, type=${manifest.modelType}, plugin=${manifest.pluginId}")
            manifest
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse nexa.manifest in ${modelDir.absolutePath}", e)
            null
        }
    }

    /**
     * Find tokenizer file in model directory (cookbook pattern)
     */
    private fun findTokenizerFile(modelDir: File): File? {
        val names = listOf("tokenizer.model", "tokenizer.json", "vocab.txt")
        return names.map { File(modelDir, it) }.firstOrNull { it.exists() }
    }

    /**
     * Initialize Nexa SDK (call once on app start)
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isNexaInitialized) {
                NexaSdk.getInstance().init(context)
                isNexaInitialized = true
                Log.d(TAG, "Nexa SDK initialized")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Nexa SDK", e)
            Result.failure(e)
        }
    }

    /**
     * Load a model
     */
    suspend fun loadModel(model: AIModel): Result<ModelLoadState> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading model: ${model.name}")

            // Validate model
            if (model.id.isBlank()) {
                Log.e(TAG, "Invalid model ID")
                return@withContext Result.failure(Exception("Invalid model ID"))
            }

            // Check if already loaded
            if (currentModelId == model.id && llmWrapper != null) {
                Log.d(TAG, "Model already loaded: ${model.name}")
                return@withContext Result.success(ModelLoadState.Loaded(model.id))
            }

            // Unload current model if any
            try {
                if (llmWrapper != null) {
                    unloadModel()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unloading previous model", e)
                // Continue anyway
            }

            // Check if model is downloaded
            val modelPath = modelManager.getModelPath(model)
                ?: return@withContext Result.failure(Exception("Model not downloaded"))

            // Ensure Nexa SDK is initialized
            if (!isNexaInitialized) {
                initialize().getOrThrow()
            }

            // Get power-aware config
            val config = preferences.getModelConfig()
            val isNPU = model.type == ModelType.NPU
            val modelDir = modelManager.getModelDir(model)

            // Read nexa.manifest from model directory (cookbook pattern)
            val manifest = readNexaManifest(modelDir)

            // model_name: use manifest ModelName, or empty string (cookbook default)
            val modelName = manifest?.modelName ?: ""

            // Determine plugin_id and device_id from manifest PluginId (cookbook mapping)
            var pluginId = manifest?.pluginId ?: if (isNPU) "npu_llama" else "llama_cpp"
            var deviceId: String? = null
            var nGpuLayers = config.nGpuLayers

            when (pluginId) {
                "cpu" -> {
                    pluginId = "cpu_gpu"
                    nGpuLayers = 0
                }
                "gpu" -> {
                    pluginId = "cpu_gpu"
                    deviceId = DeviceIdValue.GPU.value
                    nGpuLayers = 999
                }
                "npu_llama", "npu" -> {
                    pluginId = "cpu_gpu"
                    deviceId = DeviceIdValue.NPU.value
                    nGpuLayers = 999
                }
                "llama_cpp" -> {
                    pluginId = "cpu_gpu"
                    // nGpuLayers stays at config default
                }
                else -> {
                    pluginId = "cpu_gpu"
                }
            }

            Log.d(TAG, "Loading with: model_name='$modelName', plugin_id='$pluginId', device_id=$deviceId, nGpuLayers=$nGpuLayers")

            // Find tokenizer file in model directory (cookbook pattern)
            val tokenizerPath = findTokenizerFile(modelDir)?.absolutePath
            if (tokenizerPath != null) {
                Log.d(TAG, "Found tokenizer: $tokenizerPath")
            }

            // Build Nexa ModelConfig — match cookbook pattern
            val nexaConfig = if (isNPU || deviceId == DeviceIdValue.NPU.value) {
                NexaModelConfig(
                    nCtx = config.nCtx,
                    nGpuLayers = nGpuLayers,
                    npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
                    npu_model_folder_path = modelDir.absolutePath
                )
            } else {
                NexaModelConfig(
                    nCtx = config.nCtx,
                    nGpuLayers = nGpuLayers
                )
            }

            // Create LLM wrapper — match cookbook LlmCreateInput pattern
            val result = try {
                LlmWrapper.builder()
                    .llmCreateInput(
                        LlmCreateInput(
                            model_path = modelPath,
                            model_name = modelName,
                            tokenizer_path = tokenizerPath,
                            config = nexaConfig,
                            plugin_id = pluginId,
                            device_id = deviceId
                        )
                    )
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create LLM wrapper", e)
                return@withContext Result.failure(Exception("Failed to create LLM wrapper: ${e.message}"))
            }

            result.onSuccess { wrapper ->
                try {
                    llmWrapper = wrapper
                    currentModelId = model.id
                    scheduleAutoUnload()
                    Log.d(TAG, "Model loaded successfully: ${model.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up loaded model", e)
                    return@withContext Result.failure(Exception("Error setting up loaded model: ${e.message}"))
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to load model: ${model.name}", error)

                // NPU models can't fallback to CPU config — the .nexa format is NPU-only
                if (isNPU) {
                    val manifestInfo = if (manifest != null) {
                        "manifest: name='${manifest.modelName}', plugin='${manifest.pluginId}'"
                    } else {
                        "no nexa.manifest found in ${modelDir.absolutePath}"
                    }
                    val filesInfo = modelDir.listFiles()?.joinToString(", ") { it.name } ?: "empty dir"
                    val hint = "NPU model '${model.name}' failed to load (${error.message}). " +
                        "[$manifestInfo] [files: $filesInfo] " +
                        "Use '/select-model granite-4.0-micro-Q4_0' to switch to a CPU model."
                    Log.e(TAG, hint)
                    return@withContext Result.failure(Exception(hint))
                }

                // GGUF models: try with minimal config as fallback
                try {
                    Log.d(TAG, "Trying fallback configuration...")
                    val fallbackConfig = NexaModelConfig(
                        nCtx = 1024,
                        nGpuLayers = 0
                    )

                    val fallbackResult = try {
                        LlmWrapper.builder()
                            .llmCreateInput(
                                LlmCreateInput(
                                    model_path = modelPath,
                                    model_name = "",
                                    tokenizer_path = null,
                                    config = fallbackConfig,
                                    plugin_id = "cpu_gpu"
                                )
                            )
                            .build()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create fallback wrapper", e)
                        return@withContext Result.failure(Exception("Failed to create fallback wrapper: ${e.message}"))
                    }

                    fallbackResult.onSuccess { fallbackWrapper ->
                        try {
                            llmWrapper = fallbackWrapper
                            currentModelId = model.id
                            scheduleAutoUnload()
                            Log.d(TAG, "Model loaded with fallback config: ${model.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting up fallback model", e)
                            return@withContext Result.failure(Exception("Error setting up fallback model: ${e.message}"))
                        }
                    }.onFailure { fallbackError ->
                        Log.e(TAG, "Fallback config also failed", fallbackError)
                        return@withContext Result.failure(Exception("Both primary and fallback configs failed: ${fallbackError.message}"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback attempt failed", e)
                    return@withContext Result.failure(Exception("Fallback attempt failed: ${e.message}"))
                }
            }

            if (result.isSuccess) {
                Result.success(ModelLoadState.Loaded(model.id))
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            Result.failure(e)
        }
    }

    /**
     * Unload current model
     */
    fun unloadModel() {
        try {
            llmWrapper?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying LLM wrapper", e)
        }
        
        llmWrapper = null
        currentModelId = null
        
        try {
            cancelAutoUnload()
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling auto-unload", e)
        }
        
        try {
            System.gc()  // Suggest garbage collection
        } catch (e: Exception) {
            Log.e(TAG, "Error during garbage collection", e)
        }
        
        Log.d(TAG, "Model unloaded")
    }

    /**
     * Check if a model is loaded
     */
    fun isModelLoaded(): Boolean {
        return llmWrapper != null
    }

    /**
     * Get currently loaded model ID
     */
    fun getCurrentModelId(): String? {
        return currentModelId
    }

    /**
     * Generate text response (streaming)
     */
    fun generateResponse(
        prompt: String,
        systemPrompt: String = "You are a helpful AI assistant in SafeGuardian, a secure mesh messaging app. Be concise and friendly."
    ): Flow<AIResponse> = flow {
        try {
            val wrapper = llmWrapper
                ?: run {
                    Log.e(TAG, "No model loaded")
                    emit(AIResponse.Error("No AI model loaded. Please load a model first."))
                    return@flow
                }

            Log.d(TAG, "Starting text generation for prompt: ${prompt.take(100)}...")
            
            // Comprehensive safety checks
            if (prompt.isBlank()) {
                Log.w(TAG, "Empty prompt provided")
                emit(AIResponse.Error("Prompt cannot be empty."))
                return@flow
            }
            
            if (prompt.length > MAX_RESPONSE_LENGTH) {
                Log.w(TAG, "Prompt too long: ${prompt.length} > $MAX_RESPONSE_LENGTH")
                emit(AIResponse.Error("Prompt too long. Maximum length is $MAX_RESPONSE_LENGTH characters."))
                return@flow
            }
            
            // Check if model is still valid
            if (!isModelLoaded()) {
                Log.e(TAG, "Model became unloaded during generation")
                emit(AIResponse.Error("Model was unloaded during generation. Please try again."))
                return@flow
            }
            
            // Reset auto-unload timer
            scheduleAutoUnload()

            // Build chat messages with structured output / function calls if enabled
            val finalSystemPrompt = buildString {
                append(systemPrompt)
                if (preferences.functionCallsEnabled) {
                    append(FunctionRegistry.generateToolPrompt())
                }
                if (preferences.structuredOutput) {
                    append("\n\n[STRUCTURED OUTPUT ENFORCED]\nYou MUST format all responses as valid JSON. Use this schema:\n{\n  \"type\": \"response|analysis|query|action\",\n  \"content\": \"your main response text\",\n  \"confidence\": 0.0-1.0,\n  \"metadata\": {\"additional_info\": \"value\"}\n}\nAlways produce valid, parseable JSON only. No additional text outside the JSON block.")
                }
            }
            
            val messages = arrayOf(
                ChatMessage("system", finalSystemPrompt),
                ChatMessage("user", prompt)
            )

            Log.d(TAG, "Applying chat template...")
            // Apply chat template with error handling
            val templateResult = try {
                wrapper.applyChatTemplate(messages, null, false)
            } catch (e: Exception) {
                Log.e(TAG, "Template application failed with exception", e)
                emit(AIResponse.Error("Failed to process prompt: ${e.message ?: "Unknown error"}"))
                return@flow
            }

            templateResult.onSuccess { result ->
                try {
                    Log.d(TAG, "Chat template applied successfully")
                    val formattedText = result.formattedText
                    
                    // Validate formatted text
                    if (formattedText.isBlank()) {
                        Log.w(TAG, "Formatted text is blank")
                        emit(AIResponse.Error("Failed to format prompt properly."))
                        return@flow
                    }

                    // Generate with streaming
                    val config = try {
                        preferences.getModelConfig()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get model config", e)
                        emit(AIResponse.Error("Failed to get model configuration."))
                        return@flow
                    }
                    
                    // Match cookbook's GenerationConfigSample.toGenerationConfig() exactly
                    val generationConfig = try {
                        com.nexa.sdk.bean.GenerationConfig(
                            maxTokens = config.maxTokens,
                            stopWords = null,
                            stopCount = 0,
                            nPast = 0,
                            imagePaths = null,
                            imageCount = 0,
                            audioPaths = null,
                            audioCount = 0
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create generation config", e)
                        emit(AIResponse.Error("Failed to create generation configuration."))
                        return@flow
                    }

                    Log.d(TAG, "Starting stream generation with maxTokens: ${config.maxTokens}")
                    Log.d(TAG, "Formatted prompt (first 300 chars): ${formattedText.take(300)}")
                    
                    // Use timeout to prevent hanging
                    withTimeoutOrNull(GENERATION_TIMEOUT_MS) {
                        try {
                            var responseLength = 0
                            var tokenCount = 0
                            
                            wrapper.generateStreamFlow(formattedText, generationConfig)
                                .collect { streamResult ->
                                    try {
                                        when (streamResult) {
                                            is LlmStreamResult.Token -> {
                                                // Safety check for response length
                                                responseLength += streamResult.text.length
                                                tokenCount++

                                                if (responseLength > MAX_RESPONSE_LENGTH) {
                                                    Log.w(TAG, "Response too long, stopping generation")
                                                    emit(AIResponse.Error("Response too long. Maximum length is $MAX_RESPONSE_LENGTH characters."))
                                                    return@collect
                                                }

                                                if (tokenCount > 10000) { // Prevent infinite loops
                                                    Log.w(TAG, "Too many tokens, stopping generation")
                                                    emit(AIResponse.Error("Response too long. Stopping generation."))
                                                    return@collect
                                                }

                                                // Log first 10 tokens at DEBUG level for diagnostics
                                                if (tokenCount <= 10) {
                                                    val bytes = streamResult.text.toByteArray(Charsets.UTF_8)
                                                    val hex = bytes.joinToString(" ") { "%02x".format(it) }
                                                    Log.d(TAG, "Token #$tokenCount: '${streamResult.text}' (hex: $hex)")
                                                }
                                                emit(AIResponse.Token(streamResult.text))
                                            }
                                            is LlmStreamResult.Completed -> {
                                                Log.d(TAG, "Generation completed")
                                                emit(AIResponse.Completed(streamResult.profile))
                                            }
                                            is LlmStreamResult.Error -> {
                                                Log.e(TAG, "Generation error: ${streamResult.throwable.message}", streamResult.throwable)
                                                emit(AIResponse.Error(streamResult.throwable.message ?: "Unknown error"))
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error processing stream result", e)
                                        emit(AIResponse.Error("Error processing response: ${e.message ?: "Unknown error"}"))
                                        return@collect
                                    }
                                }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during stream generation", e)
                            emit(AIResponse.Error("Generation failed: ${e.message ?: "Unknown error"}"))
                        }
                    } ?: run {
                        Log.w(TAG, "Generation timed out after ${GENERATION_TIMEOUT_MS}ms")
                        emit(AIResponse.Error("Generation timed out. Please try a shorter prompt or check your model."))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in template success handler", e)
                    emit(AIResponse.Error("Generation failed: ${e.message ?: "Unknown error"}"))
                }
            }.onFailure { error ->
                Log.e(TAG, "Template application failed", error)
                emit(AIResponse.Error(error.message ?: "Template application failed"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in generateResponse", e)
            try {
                emit(AIResponse.Error("AI generation failed: ${e.message ?: "Unknown error"}"))
            } catch (emitError: Exception) {
                Log.e(TAG, "Failed to emit error response", emitError)
                // If we can't even emit an error, something is seriously wrong
                // The flow will complete with an exception, which should be caught by the caller
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stop current generation
     */
    suspend fun stopGeneration() = withContext(Dispatchers.IO) {
        llmWrapper?.stopStream()
        Log.d(TAG, "Generation stopped")
    }

    /**
     * Reset model state (clear context)
     */
    suspend fun reset() = withContext(Dispatchers.IO) {
        llmWrapper?.reset()
        Log.d(TAG, "Model state reset")
    }

    /**
     * Schedule auto-unload after inactivity
     */
    private fun scheduleAutoUnload() {
        cancelAutoUnload()

        unloadTask = autoUnloadTimer.schedule(AUTO_UNLOAD_DELAY_MS) {
            Log.d(TAG, "Auto-unloading model after inactivity")
            unloadModel()
        }
    }

    /**
     * Cancel auto-unload timer
     */
    private fun cancelAutoUnload() {
        unloadTask?.cancel()
        unloadTask = null
    }

    /**
     * Get embedding for text (for RAG)
     */
    suspend fun getEmbedding(text: String): Result<FloatArray> = withContext(Dispatchers.IO) {
        try {
            val wrapper = llmWrapper
                ?: return@withContext Result.failure(Exception("No model loaded"))

            // Note: Embedding requires a separate embedding model
            // This is a placeholder - implement with EmbeddingGemma model
            // TODO: Implement proper embedding generation with Nexa SDK
            
            Log.w(TAG, "Embedding generation not yet implemented")
            Result.failure(Exception("Embedding generation not implemented - requires separate embedding model"))
        } catch (e: Exception) {
            Log.e(TAG, "Embedding error", e)
            Result.failure(e)
        }
    }

    /**
     * Initialize TTS service
     */
    fun initializeTTS(): Boolean {
        return ttsService.initialize()
    }

    /**
     * Speak text using TTS
     */
    fun speak(text: String) {
        if (preferences.ttsEnabled) {
            ttsService.speak(text)
        }
    }

    /**
     * Stop TTS
     */
    fun stopTTS() {
        ttsService.stop()
    }

    /**
     * Set TTS enabled/disabled
     */
    fun setTTSEnabled(enabled: Boolean) {
        preferences.ttsEnabled = enabled
        ttsService.setEnabled(enabled)
    }

    /**
     * Get TTS service for advanced control
     */
    fun getTTSService(): TTSService {
        return ttsService
    }

    /**
     * Test if the loaded model can generate a simple response
     */
    suspend fun testModel(): String = withContext(Dispatchers.IO) {
        try {
            val wrapper = llmWrapper
                ?: return@withContext "No model loaded"
            
            Log.d(TAG, "Testing model with simple prompt...")
            
            // Simple test prompt
            val testPrompt = "Hello, how are you?"
            val messages = arrayOf(
                ChatMessage("system", "You are a helpful assistant. Respond briefly."),
                ChatMessage("user", testPrompt)
            )
            
            val templateResult = wrapper.applyChatTemplate(messages, null, false)
            
            return@withContext if (templateResult.isSuccess) {
                "Model test successful - template application works"
            } else {
                "Model test failed - template application error: ${templateResult.exceptionOrNull()?.message}"
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Model test failed", e)
            return@withContext "Model test failed: ${e.message}"
        }
    }

    /**
     * Cleanup resources
     */
    fun shutdown() {
        unloadModel()
        ttsService.release()
        autoUnloadTimer.cancel()
        Log.d(TAG, "AIService shutdown")
    }
}

/**
 * AI response types
 */
sealed class AIResponse {
    data class Token(val text: String) : AIResponse()
    data class Completed(val profile: Any?) : AIResponse()
    data class Error(val message: String) : AIResponse()
}

/**
 * AI Message Statistics
 * Tracks performance metrics for AI generations
 */
data class AIMessageStats(
    val totalTokens: Int = 0,
    val tokensPerSecond: Float = 0f,
    val generationTimeMs: Long = 0L,
    val processingUnit: ProcessingUnit = ProcessingUnit.CPU,
    val startTime: Long = 0L,
    val endTime: Long = 0L
) {
    companion object {
        fun calculate(startTime: Long, endTime: Long, tokenCount: Int, processingUnit: ProcessingUnit = ProcessingUnit.CPU): AIMessageStats {
            val durationMs = endTime - startTime
            val tokensPerSec = if (durationMs > 0) (tokenCount * 1000f) / durationMs else 0f
            return AIMessageStats(
                totalTokens = tokenCount,
                tokensPerSecond = tokensPerSec,
                generationTimeMs = durationMs,
                processingUnit = processingUnit,
                startTime = startTime,
                endTime = endTime
            )
        }
    }
}

/**
 * Processing unit types
 */
enum class ProcessingUnit {
    CPU, GPU, NPU, UNKNOWN
}