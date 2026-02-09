package com.bitchat.android.ai

import kotlinx.coroutines.flow.Flow

/**
 * Sealed class for AI responses
 */
sealed class AIResponse {
    data class Token(val text: String) : AIResponse()
    data class Completed(val fullText: String, val tokensGenerated: Int = 0) : AIResponse()
    data class Error(val message: String, val exception: Throwable? = null) : AIResponse()
}

/**
 * AI Service interface for text generation
 */
interface AIService {
    suspend fun generateResponse(prompt: String): Flow<AIResponse>
    suspend fun speak(text: String)
    fun isModelLoaded(): Boolean
    suspend fun loadModel(model: ModelInfo): Result<Unit>
    suspend fun unloadModel()
    suspend fun testModel(): Result<String>
}

/**
 * Default implementation of AIService (stub)
 */
class DefaultAIService : AIService {
    private var modelLoaded = false
    
    override suspend fun generateResponse(prompt: String): Flow<AIResponse> {
        return kotlinx.coroutines.flow.flow {
            emit(AIResponse.Token("AI service not initialized"))
            emit(AIResponse.Completed("AI service not initialized", 0))
        }
    }
    
    override suspend fun speak(text: String) {
        // TTS not implemented
    }
    
    override fun isModelLoaded(): Boolean = modelLoaded
    
    override suspend fun loadModel(model: ModelInfo): Result<Unit> {
        modelLoaded = true
        return Result.success(Unit)
    }
    
    override suspend fun unloadModel() {
        modelLoaded = false
    }
    
    override suspend fun testModel(): Result<String> {
        return Result.success("Model test passed")
    }
}
