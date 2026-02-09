package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * AIManager - Main AI management class
 * Coordinates AI services, models, and preferences
 */
class AIManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AIManager"
        private var instance: AIManager? = null
        
        fun getInstance(context: Context): AIManager {
            if (instance == null) {
                instance = AIManager(context.applicationContext)
            }
            return instance!!
        }
    }
    
    val preferences = AIPreferences(context)
    val aiService: AIService = DefaultAIService()
    val modelManager = ModelManager(context)
    val conversationContext = ConversationContext(context)
    
    private val rerankerService = RerankerService(context)
    private val ragService = RAGService(context, preferences, rerankerService)
    private val documentManager = RAGDocumentManager(context)
    
    private var initialized = false
    
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing AI Manager")
            ragService.initialize()
            initialized = true
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            Result.failure(e)
        }
    }
    
    fun isAIReady(): Boolean = initialized && preferences.aiEnabled
    
    fun isRAGReady(): Boolean = ragService.isReady()
    
    fun isRerankerReady(): Boolean = rerankerService.isInitialized()
    
    fun getRAGService(): RAGService = ragService
    
    fun getRAGStats(): RAGStats = ragService.getIndexStats()
    
    suspend fun enableAI(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!initialized) {
                initialize()
            }
            
            val model = preferences.getSelectedLLMModel()
            if (model != null && modelManager.isModelDownloaded(model)) {
                aiService.loadModel(model)
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
}
