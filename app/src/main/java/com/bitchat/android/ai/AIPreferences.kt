package com.bitchat.android.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * AI Preferences - manages AI-related settings
 */
class AIPreferences(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
    
    var aiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()
    
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()
    
    var asrEnabled: Boolean
        get() = prefs.getBoolean(KEY_ASR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ASR_ENABLED, value).apply()
    
    var ragEnabled: Boolean
        get() = prefs.getBoolean(KEY_RAG_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RAG_ENABLED, value).apply()
    
    var rerankEnabled: Boolean
        get() = prefs.getBoolean(KEY_RERANK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RERANK_ENABLED, value).apply()
    
    var rerankTopN: Int
        get() = prefs.getInt(KEY_RERANK_TOP_N, 5)
        set(value) = prefs.edit().putInt(KEY_RERANK_TOP_N, value).apply()
    
    var structuredOutput: Boolean
        get() = prefs.getBoolean(KEY_STRUCTURED_OUTPUT, false)
        set(value) = prefs.edit().putBoolean(KEY_STRUCTURED_OUTPUT, value).apply()
    
    var structuredOutputMode: StructuredOutputMode
        get() = StructuredOutputMode.entries.getOrElse(
            prefs.getInt(KEY_STRUCTURED_OUTPUT_MODE, 0)
        ) { StructuredOutputMode.OFF }
        set(value) = prefs.edit().putInt(KEY_STRUCTURED_OUTPUT_MODE, value.ordinal).apply()
    
    var aiHostingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_HOSTING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_HOSTING_ENABLED, value).apply()

    var aiMessageSharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_MESSAGE_SHARING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_MESSAGE_SHARING_ENABLED, value).apply()

    var disasterModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DISASTER_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISASTER_MODE_ENABLED, value).apply()

    var selectedLLMModel: String
        get() = prefs.getString(KEY_SELECTED_LLM_MODEL, "qwen2.5-0.5b") ?: "qwen2.5-0.5b"
        set(value) = prefs.edit().putString(KEY_SELECTED_LLM_MODEL, value).apply()
    
    fun getSelectedLLMModel(): ModelInfo? {
        return ModelCatalog.getModelById(selectedLLMModel)
    }

    /**
     * Returns the richer [AIModel] from [AIModelCatalog] for the selected model ID.
     * Used when NPU plugin selection or dependency resolution is needed.
     * Falls back to null if the ID is only in the basic [ModelCatalog].
     */
    fun getSelectedAIModel(): AIModel? {
        return AIModelCatalog.getModelById(selectedLLMModel)
    }
    
    fun getSelectedRerankerModel(): ModelInfo? {
        return if (rerankEnabled) {
            ModelInfo(
                id = "jina-reranker-v1-tiny",
                name = "Jina Reranker v1 Tiny",
                fileSizeMB = 33,
                description = "Lightweight reranker model"
            )
        } else null
    }
    
    companion object {
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_ASR_ENABLED = "asr_enabled"
        private const val KEY_RAG_ENABLED = "rag_enabled"
        private const val KEY_RERANK_ENABLED = "rerank_enabled"
        private const val KEY_RERANK_TOP_N = "rerank_top_n"
        private const val KEY_STRUCTURED_OUTPUT = "structured_output"
        private const val KEY_STRUCTURED_OUTPUT_MODE = "structured_output_mode"
        private const val KEY_SELECTED_LLM_MODEL = "selected_llm_model"
        private const val KEY_AI_HOSTING_ENABLED = "ai_hosting_enabled"
        private const val KEY_AI_MESSAGE_SHARING_ENABLED = "ai_message_sharing_enabled"
        private const val KEY_DISASTER_MODE_ENABLED = "disaster_mode_enabled"
    }
}
