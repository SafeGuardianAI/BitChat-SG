package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.DeviceIdValue
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

// NPU plugin IDs used with LlmCreateInput.plugin_id.
// "cpu_gpu" = llama.cpp CPU/GPU backend (universal)
// "npu"     = Qualcomm QNN/HTP backend (.nexa models only — Snapdragon 8 Gen 1/2/3)
private const val PLUGIN_CPU_GPU = "cpu_gpu"
private const val PLUGIN_NPU     = "npu"

sealed class AIResponse {
    data class Token(val text: String) : AIResponse()
    data class Completed(val fullText: String, val tokensGenerated: Int = 0) : AIResponse()
    data class Error(val message: String, val exception: Throwable? = null) : AIResponse()
}

interface AIService {
    /**
     * Generate a response using an explicit system prompt (backward-compat overload).
     * Prefer [generateResponse(prompt, taskConfig)] for new callers.
     */
    suspend fun generateResponse(prompt: String, systemPrompt: String = "You are a helpful AI assistant."): Flow<AIResponse>

    /**
     * Generate a response using a [TaskConfig] which bundles system prompt,
     * max tokens, stop words, and temperature for the specific task.
     */
    suspend fun generateResponse(prompt: String, taskConfig: TaskConfig): Flow<AIResponse>

    suspend fun speak(text: String)
    fun isModelLoaded(): Boolean
    suspend fun loadModel(model: ModelInfo): Result<Unit>
    /** NPU-aware load using the richer [AIModel] descriptor. Prefers NPU plugin when [qnnTier] is provided. */
    suspend fun loadAIModel(model: AIModel, qnnTier: DeviceCapabilityService.QnnTier?): Result<Unit>
    suspend fun unloadModel()
    suspend fun testModel(): Result<String>
}

/**
 * Real LLM inference via Nexa SDK's LlmWrapper.
 *
 * Lifecycle:  loadModel() → generateResponse()* → unloadModel()
 * NexaSdk.init() must have been called in Application.onCreate() first.
 */
class NexaLlmService(private val context: Context) : AIService {

    companion object {
        private const val TAG = "NexaLlmService"
        private const val DEFAULT_CTX_SIZE = 2048
        private const val MAX_TOKENS = 1024
    }

    private var llmWrapper: LlmWrapper? = null
    private val chatHistory = mutableListOf<ChatMessage>()
    private var currentModelPath: String? = null

    override fun isModelLoaded(): Boolean = llmWrapper != null

    override suspend fun loadModel(model: ModelInfo): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Unload previous model if any
            if (llmWrapper != null) {
                unloadModel()
            }

            val modelManager = ModelManager(context)
            val modelPath = modelManager.getModelPath(model)
                ?: return@withContext Result.failure(
                    IllegalStateException("Model file not found for ${model.name}. Download it first.")
                )

            // Pre-flight architecture check — fail fast with a clear message instead of
            // a raw SDK error code when the bundled llama.cpp doesn't support the model.
            val arch = GgufHeader.readArchitecture(modelPath)
            if (arch != null && arch !in GgufHeader.SUPPORTED_ARCHITECTURES) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Architecture '$arch' is not supported by the current SDK. " +
                        "Try Granite 4.0, Llama 3.2, Qwen3, or LFM2 instead."
                    )
                )
            }
            if (arch != null) Log.d(TAG, "GGUF architecture check passed: $arch")

            Log.d(TAG, "Loading model from $modelPath")

            val modelsDir = java.io.File(modelPath).parentFile?.absolutePath ?: ""
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            val conf = ModelConfig(
                nCtx = DEFAULT_CTX_SIZE,
                nGpuLayers = 0,
                enable_thinking = false,
                npu_lib_folder_path = nativeLibDir,
                npu_model_folder_path = modelsDir
            )

            val input = LlmCreateInput(
                model_name = "",
                model_path = modelPath,
                tokenizer_path = null,
                config = conf,
                plugin_id = "cpu_gpu",
                device_id = null
            )

            val result = LlmWrapper.builder()
                .llmCreateInput(input)
                .build()

            result.onSuccess { wrapper ->
                llmWrapper = wrapper
                currentModelPath = modelPath
                chatHistory.clear()
                Log.d(TAG, "Model loaded successfully: ${model.name}")
            }

            result.onFailure { error ->
                Log.e(TAG, "Failed to load model: ${error.message}", error)
            }

            if (result.isSuccess) Result.success(Unit)
            else {
                val raw = result.exceptionOrNull()
                val friendly = friendlyLoadError(raw?.message)
                Result.failure(RuntimeException(friendly, raw))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model", e)
            Result.failure(e)
        }
    }

    override suspend fun loadAIModel(
        model: AIModel,
        qnnTier: DeviceCapabilityService.QnnTier?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (llmWrapper != null) unloadModel()

            val modelManager = ModelManager(context)
            // For NPU models use the .nexa path; if not downloaded, find GGUF fallback
            val modelPath: String? = when {
                model.type == ModelType.NPU && qnnTier != null -> {
                    // Try NPU path; fall back to GGUF dependency if not yet downloaded
                    val nexaFile = java.io.File(context.filesDir, "models/${model.modelFileName}")
                    if (nexaFile.exists() && nexaFile.length() > 256) nexaFile.absolutePath
                    else {
                        // Auto-fallback to GGUF base model
                        val base = AIModelCatalog.resolveDependencies(model)
                            .firstOrNull { it.type == ModelType.LLM }
                        if (base != null) {
                            val baseInfo = ModelInfo(
                                id = base.id, name = base.name,
                                fileSizeMB = base.fileSizeMB, downloadUrl = base.downloadUrl
                            )
                            modelManager.getModelPath(baseInfo)
                        } else null
                    }
                }
                else -> {
                    val info = ModelInfo(
                        id = model.id, name = model.name,
                        fileSizeMB = model.fileSizeMB, downloadUrl = model.downloadUrl
                    )
                    modelManager.getModelPath(info)
                }
            } ?: return@withContext Result.failure(
                IllegalStateException("Model file not found for ${model.name}. Download it first.")
            )

            val isNpu = model.type == ModelType.NPU && qnnTier != null &&
                java.io.File(modelPath).name.endsWith(".nexa")
            val pluginId = if (isNpu) PLUGIN_NPU else PLUGIN_CPU_GPU
            Log.d(TAG, "loadAIModel: ${model.name} path=$modelPath plugin=$pluginId npu=$isNpu")

            // Architecture pre-flight (GGUF only — skip .nexa NPU bundles)
            if (!isNpu) {
                val arch = GgufHeader.readArchitecture(modelPath!!)
                if (arch != null && arch !in GgufHeader.SUPPORTED_ARCHITECTURES) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Architecture '$arch' is not supported by the current SDK. " +
                            "Try Granite 4.0, Llama 3.2, Qwen3, or LFM2 instead."
                        )
                    )
                }
                if (arch != null) Log.d(TAG, "GGUF architecture check passed: $arch")
            }

            val modelsDir = java.io.File(modelPath).parentFile?.absolutePath ?: ""
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            val conf = com.nexa.sdk.bean.ModelConfig(
                nCtx = DEFAULT_CTX_SIZE,
                nGpuLayers = 0,
                enable_thinking = false,
                npu_lib_folder_path = nativeLibDir,
                npu_model_folder_path = modelsDir
            )

            val input = LlmCreateInput(
                model_name = "",
                model_path = modelPath!!,
                tokenizer_path = null,
                config = conf,
                plugin_id = pluginId,
                device_id = if (isNpu) DeviceIdValue.NPU.value else null
            )

            val result = LlmWrapper.builder().llmCreateInput(input).build()
            result.onSuccess { wrapper ->
                llmWrapper = wrapper
                currentModelPath = modelPath
                chatHistory.clear()
                Log.d(TAG, "AIModel loaded: ${model.name} (plugin=$pluginId)")
            }
            result.onFailure { error ->
                Log.e(TAG, "Failed to load AIModel: ${error.message}", error)
            }

            if (result.isSuccess) Result.success(Unit)
            else {
                val raw = result.exceptionOrNull()
                val friendly = friendlyLoadError(raw?.message)
                Result.failure(RuntimeException(friendly, raw))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading AIModel", e)
            Result.failure(e)
        }
    }

    /**
     * Maps raw SDK error messages to user-readable text.
     * The Nexa SDK surfaces native llama.cpp errors as integer codes — this converts
     * the most common ones so users see actionable guidance instead of raw numbers.
     */
    private fun friendlyLoadError(raw: String?): String {
        if (raw == null) return "Unknown load error"
        // Detect numeric error code pattern from Nexa SDK: "Llm create failed, error code: -NNNN"
        val codeMatch = Regex("""error code:\s*(-?\d+)""").find(raw)
        if (codeMatch != null) {
            val code = codeMatch.groupValues[1].toLongOrNull()
            return when (code) {
                // 0xB8A69578 — architecture/tensor shape mismatch in older llama.cpp
                -1197042312L -> "Model architecture not supported by the current SDK. " +
                    "Try Granite 4.0, Llama 3.2, Qwen3, or LFM2 instead."
                // Out-of-memory during KV cache allocation
                -1L -> "Not enough RAM to load this model. Try a smaller model."
                else -> "SDK load error (code $code). The model may require a newer app version."
            }
        }
        return raw
    }

    override suspend fun generateResponse(prompt: String, systemPrompt: String): Flow<AIResponse> = flow {
        val wrapper = llmWrapper
        if (wrapper == null) {
            emit(AIResponse.Error("No model loaded. Use /download to get a model first."))
            emit(AIResponse.Completed("", 0))
            return@flow
        }

        try {
            chatHistory.add(ChatMessage(role = "user", prompt))

            val templateResult = wrapper.applyChatTemplate(
                chatHistory.toTypedArray(),
                systemPrompt,
                false
            )

            if (templateResult.isFailure) {
                val err = templateResult.exceptionOrNull()?.message ?: "Template error"
                Log.e(TAG, "Chat template failed: $err")
                emit(AIResponse.Error(err))
                emit(AIResponse.Completed("", 0))
                return@flow
            }

            val formattedPrompt = templateResult.getOrThrow().formattedText

            val genConfig = GenerationConfig(
                maxTokens = MAX_TOKENS,
                stopWords = null,
                stopCount = 0,
                nPast = 0,
                imagePaths = null,
                imageCount = 0,
                audioPaths = null,
                audioCount = 0
            )
            // NOTE: temperature is not a GenerationConfig field in this SDK version;
            // it is set in ModelConfig at load time. Stop words are also not yet wired —
            // we collect tokens and stop manually when a stop word appears.
            val stopWordsSet: Set<String> = emptySet() // wired in TaskConfig overload

            val fullText = StringBuilder()

            wrapper.generateStreamFlow(formattedPrompt, genConfig)
                .collect { streamResult ->
                    when (streamResult) {
                        is LlmStreamResult.Token -> {
                            fullText.append(streamResult.text)
                            emit(AIResponse.Token(streamResult.text))
                        }
                        is LlmStreamResult.Completed -> {
                            val tokens = streamResult.profile.generatedTokens.toInt()
                            chatHistory.add(ChatMessage(role = "assistant", fullText.toString()))
                            emit(AIResponse.Completed(fullText.toString(), tokens))
                        }
                        is LlmStreamResult.Error -> {
                            Log.e(TAG, "Stream error: $streamResult")
                            emit(AIResponse.Error("Generation error — context may be too long. Try /clear."))
                            emit(AIResponse.Completed(fullText.toString(), 0))
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "generateResponse exception", e)
            emit(AIResponse.Error("Error: ${e.message}"))
            emit(AIResponse.Completed("", 0))
        }
    }

    /**
     * TaskConfig overload — delegates to the systemPrompt overload with per-task maxTokens
     * and implements client-side stop-word filtering.
     *
     * NOTE: The Nexa SDK's GenerationConfig.maxTokens caps token generation at the source.
     * Stop words are enforced client-side by scanning accumulated text.
     */
    override suspend fun generateResponse(prompt: String, taskConfig: TaskConfig): Flow<AIResponse> = flow {
        val wrapper = llmWrapper
        if (wrapper == null) {
            emit(AIResponse.Error("No model loaded. Use /download to get a model first."))
            emit(AIResponse.Completed("", 0))
            return@flow
        }

        try {
            chatHistory.add(ChatMessage(role = "user", prompt))

            val templateResult = wrapper.applyChatTemplate(
                chatHistory.toTypedArray(),
                taskConfig.systemPrompt,
                false
            )

            if (templateResult.isFailure) {
                val err = templateResult.exceptionOrNull()?.message ?: "Template error"
                Log.e(TAG, "Chat template failed (task=${taskConfig.taskId}): $err")
                emit(AIResponse.Error(err))
                emit(AIResponse.Completed("", 0))
                return@flow
            }

            val formattedPrompt = templateResult.getOrThrow().formattedText

            val genConfig = GenerationConfig(
                maxTokens = taskConfig.maxTokens,
                stopWords = null,
                stopCount = 0,
                nPast = 0,
                imagePaths = null,
                imageCount = 0,
                audioPaths = null,
                audioCount = 0
            )

            val fullText = StringBuilder()
            var stopped = false

            wrapper.generateStreamFlow(formattedPrompt, genConfig)
                .collect { streamResult ->
                    if (stopped) return@collect
                    when (streamResult) {
                        is LlmStreamResult.Token -> {
                            fullText.append(streamResult.text)
                            // Client-side stop-word enforcement
                            val hit = taskConfig.stopWords.any { sw ->
                                fullText.contains(sw, ignoreCase = false)
                            }
                            if (hit) {
                                stopped = true
                                chatHistory.add(ChatMessage(role = "assistant", fullText.toString()))
                                emit(AIResponse.Token(streamResult.text))
                                emit(AIResponse.Completed(fullText.toString(), 0))
                            } else {
                                emit(AIResponse.Token(streamResult.text))
                            }
                        }
                        is LlmStreamResult.Completed -> {
                            val tokens = streamResult.profile.generatedTokens.toInt()
                            chatHistory.add(ChatMessage(role = "assistant", fullText.toString()))
                            emit(AIResponse.Completed(fullText.toString(), tokens))
                            Log.d(TAG, "Task ${taskConfig.taskId}: $tokens tokens generated")
                        }
                        is LlmStreamResult.Error -> {
                            Log.e(TAG, "Stream error (task=${taskConfig.taskId}): $streamResult")
                            emit(AIResponse.Error("Generation error — context may be too long. Try /clear."))
                            emit(AIResponse.Completed(fullText.toString(), 0))
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "generateResponse(TaskConfig) exception", e)
            emit(AIResponse.Error("Error: ${e.message}"))
            emit(AIResponse.Completed("", 0))
        }
    }

    override suspend fun speak(text: String) {
        AIManager.getInstance(context).ttsService.speak(text)
    }

    override suspend fun unloadModel(): Unit = withContext(Dispatchers.IO) {
        try {
            llmWrapper?.let { wrapper ->
                wrapper.stopStream()
                wrapper.destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unloading model", e)
        }
        llmWrapper = null
        currentModelPath = null
        chatHistory.clear()
        Log.d(TAG, "Model unloaded")
    }

    override suspend fun testModel(): Result<String> {
        val wrapper = llmWrapper ?: return Result.failure(IllegalStateException("No model loaded"))
        return try {
            val sb = StringBuilder()
            val testHistory = arrayOf(ChatMessage(role = "user", "Say hello in one sentence."))
            val templateResult = wrapper.applyChatTemplate(testHistory, null, false)
            if (templateResult.isFailure) {
                return Result.failure(templateResult.exceptionOrNull() ?: RuntimeException("Template failed"))
            }
            val genConfig = GenerationConfig(
                maxTokens = 32, stopWords = null, stopCount = 0, nPast = 0,
                imagePaths = null, imageCount = 0, audioPaths = null, audioCount = 0
            )
            wrapper.generateStreamFlow(templateResult.getOrThrow().formattedText, genConfig)
                .collect { result ->
                    if (result is LlmStreamResult.Token) sb.append(result.text)
                }
            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearHistory() {
        chatHistory.clear()
        try { llmWrapper?.reset() } catch (_: Exception) { }
    }
}
