package com.bitchat.android.ai

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Central coordinator for all AI functionality
 *
 * Provides a single point of access to:
 * - Model management
 * - AI service
 * - Conversation context
 * - Preferences
 */
class AIManager(context: Context) {

    // Core components
    val preferences = AIPreferences(context)
    val modelManager = ModelManager(context)
    val aiService = AIService(context, modelManager, preferences)
    val conversationContext = ConversationContext()

    /**
     * Initialize AI system
     */
    suspend fun initialize(): Result<Unit> {
        val result = aiService.initialize()
        if (result.isSuccess) {
            // Initialize TTS
            aiService.initializeTTS()
            
            // Copy models from assets if needed
            initializeModelsFromAssets()
        }
        return result
    }

    /**
     * Check if AI is ready to use (model loaded is the only requirement)
     */
    fun isAIReady(): Boolean {
        return aiService.isModelLoaded()
    }

    /**
     * Get current AI status
     */
    fun getStatus(): AIStatus {
        return when {
            !aiService.isModelLoaded() -> AIStatus.NoModelLoaded
            else -> AIStatus.Ready(aiService.getCurrentModelId() ?: "unknown")
        }
    }
    
    /**
     * Get model selection status
     */
    fun getModelSelectionStatus(): String {
        val selectedModel = preferences.getSelectedLLMModel()
        val downloadedModels = modelManager.getDownloadedModels()
        val isModelDownloaded = selectedModel?.let { modelManager.isModelDownloaded(it) } ?: false
        
        return buildString {
            append("Model Selection Status:\n")
            append("• Selected Model: ${selectedModel?.name ?: "None"}\n")
            append("• Model Downloaded: $isModelDownloaded\n")
            append("• Downloaded Models: ${downloadedModels.size}\n")
            if (downloadedModels.isNotEmpty()) {
                append("• Available Models:\n")
                downloadedModels.forEach { model ->
                    val isSelected = model.id == selectedModel?.id
                    val status = if (isSelected) " [SELECTED]" else ""
                    append("  - ${model.name} (${model.fileSizeMB}MB)$status\n")
                }
            }
        }
    }

    /**
     * Enable AI and auto-load the default model
     */
    suspend fun enableAI(): Result<Unit> {
        try {
            preferences.aiEnabled = true
            
            // Initialize Nexa SDK
            val initResult = initialize()
            if (initResult.isFailure) {
                preferences.aiEnabled = false // Rollback on failure
                return initResult
            }
            
            // Try to load the selected model (or default)
            val selectedModel = preferences.getSelectedLLMModel()
            if (selectedModel == null) {
                preferences.aiEnabled = false // Rollback on failure
                return Result.failure(Exception("No LLM model selected"))
            }
            
            // Check if model is downloaded
            if (!modelManager.isModelDownloaded(selectedModel)) {
                preferences.aiEnabled = false // Rollback on failure
                return Result.failure(Exception("Model '${selectedModel.name}' not downloaded. Please download it first."))
            }
            
            // Load the model with retry mechanism
            val loadResult = loadModelWithRetry(selectedModel)
            return if (loadResult.isSuccess) {
                Result.success(Unit)
            } else {
                preferences.aiEnabled = false // Rollback on failure
                Result.failure(loadResult.exceptionOrNull() ?: Exception("Failed to load model"))
            }
        } catch (e: Exception) {
            preferences.aiEnabled = false // Rollback on failure
            return Result.failure(Exception("Failed to enable AI: ${e.message}"))
        }
    }
    
    /**
     * Load model with retry mechanism for crash recovery
     */
    private suspend fun loadModelWithRetry(model: AIModel, maxRetries: Int = 2): Result<ModelLoadState> {
        var lastError: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                val result = aiService.loadModel(model)
                if (result.isSuccess) {
                    return result
                } else {
                    lastError = result.exceptionOrNull() as? Exception ?: Exception("Unknown error")
                    if (attempt < maxRetries) {
                        android.util.Log.w("AIManager", "Model load attempt $attempt failed, retrying...", lastError)
                        kotlinx.coroutines.delay(1000) // Wait 1 second before retry
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    android.util.Log.w("AIManager", "Model load attempt $attempt failed with exception, retrying...", e)
                    kotlinx.coroutines.delay(1000) // Wait 1 second before retry
                }
            }
        }
        
        return Result.failure(lastError ?: Exception("Failed to load model after $maxRetries attempts"))
    }

    /**
     * Disable AI and unload model
     */
    fun disableAI() {
        try {
            preferences.aiEnabled = false
            aiService.unloadModel()
        } catch (e: Exception) {
            android.util.Log.e("AIManager", "Error disabling AI", e)
        }
    }
    
    /**
     * Recover from crash by resetting AI state
     */
    suspend fun recoverFromCrash(): Result<Unit> {
        try {
            android.util.Log.w("AIManager", "Attempting crash recovery...")
            
            // Force unload any potentially corrupted model
            try {
                aiService.unloadModel()
            } catch (e: Exception) {
                android.util.Log.w("AIManager", "Error during forced unload", e)
            }
            
            // Reset AI state
            preferences.aiEnabled = false
            
            // Wait a bit for cleanup
            kotlinx.coroutines.delay(2000)
            
            // Try to reinitialize
            val initResult = aiService.initialize()
            if (initResult.isFailure) {
                return Result.failure(Exception("Failed to reinitialize after crash: ${initResult.exceptionOrNull()?.message}"))
            }
            
            android.util.Log.i("AIManager", "Crash recovery completed successfully")
            return Result.success(Unit)
            
        } catch (e: Exception) {
            android.util.Log.e("AIManager", "Crash recovery failed", e)
            return Result.failure(Exception("Crash recovery failed: ${e.message}"))
        }
    }

    /**
     * Initialize models from assets on first run
     */
    private fun initializeModelsFromAssets() {
        try {
            // Check if this is first run
            if (preferences.isFirstRun) {
                // Copy recommended models from assets
                val recommendedModels = ModelCatalog.RECOMMENDED_MODELS
                var copiedCount = 0
                
                for (model in recommendedModels) {
                    if (modelManager.isModelDownloaded(model)) {
                        copiedCount++
                    }
                }
                
                if (copiedCount > 0) {
                    android.util.Log.i("AIManager", "Initialized $copiedCount models from assets")
                }
                
                // Mark first run as complete
                preferences.isFirstRun = false
            }
        } catch (e: Exception) {
            android.util.Log.e("AIManager", "Failed to initialize models from assets", e)
        }
    }

    /**
     * Quick access to download states
     */
    val downloadStates: StateFlow<Map<String, DownloadState>> = modelManager.downloadStates

    /**
     * Shutdown AI system
     */
    fun shutdown() {
        aiService.shutdown()
    }
}

/**
 * AI system status
 */
sealed class AIStatus {
    object Disabled : AIStatus()
    object NoModelLoaded : AIStatus()
    data class Ready(val modelId: String) : AIStatus()
}
