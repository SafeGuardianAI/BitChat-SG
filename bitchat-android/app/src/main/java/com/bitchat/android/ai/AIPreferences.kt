package com.bitchat.android.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure preferences for AI settings
 *
 * Uses EncryptedSharedPreferences to protect sensitive data
 */
class AIPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "ai_preferences_encrypted"

        // Keys
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_SELECTED_LLM_MODEL = "selected_llm_model"
        private const val KEY_SELECTED_EMBEDDING_MODEL = "selected_embedding_model"
        private const val KEY_SELECTED_ASR_MODEL = "selected_asr_model"
        private const val KEY_POWER_MODE = "power_mode"
        private const val KEY_AUTO_LOAD_MODEL = "auto_load_model"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_ASR_ENABLED = "asr_enabled"
        private const val KEY_RAG_ENABLED = "rag_enabled"
        private const val KEY_RAG_AUTO_INDEX = "rag_auto_index"
        private const val KEY_STRUCTURED_OUTPUT = "structured_output"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_CONTEXT_LENGTH = "context_length"
        private const val KEY_FIRST_RUN = "first_run"
        private const val KEY_ANALYTICS_ENABLED = "analytics_enabled"
        private const val KEY_MICROPHONE_ENABLED = "microphone_enabled"
        private const val KEY_AUTO_SEND_VOICE_TRANSCRIPTION = "auto_send_voice_transcription"
        private const val KEY_VOICE_MAX_DURATION_MS = "voice_max_duration_ms"
        private const val KEY_VOICE_NOISE_THRESHOLD = "voice_noise_threshold"

        // AI Sharing & Hosting
        private const val KEY_AI_MESSAGE_SHARING_ENABLED = "ai_message_sharing_enabled"
        private const val KEY_AI_HOSTING_ENABLED = "ai_hosting_enabled"
        private const val KEY_DISASTER_MODE_ENABLED = "disaster_mode_enabled"
        private const val KEY_FUNCTION_CALLS_ENABLED = "function_calls_enabled"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ============================================
    // General AI Settings
    // ============================================

    var aiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, true)  // Enabled by default
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    var autoLoadModel: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LOAD_MODEL, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LOAD_MODEL, value).apply()

    var isFirstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()

    // ============================================
    // Model Selection
    // ============================================

    var selectedLLMModel: String
        get() {
            val stored = prefs.getString(KEY_SELECTED_LLM_MODEL, ModelCatalog.GRANITE_4_0_MICRO_Q4.id)
                ?: ModelCatalog.GRANITE_4_0_MICRO_Q4.id
            // Migrate old model IDs
            val migrated = migrateModelId(stored)
            if (migrated != stored) {
                prefs.edit().putString(KEY_SELECTED_LLM_MODEL, migrated).apply()
            }
            return migrated
        }
        set(value) = prefs.edit().putString(KEY_SELECTED_LLM_MODEL, value).apply()

    var selectedEmbeddingModel: String
        get() = prefs.getString(KEY_SELECTED_EMBEDDING_MODEL, ModelCatalog.EMBEDDING_GEMMA_300M.id)
            ?: ModelCatalog.EMBEDDING_GEMMA_300M.id
        set(value) = prefs.edit().putString(KEY_SELECTED_EMBEDDING_MODEL, value).apply()

    var selectedASRModel: String
        get() = prefs.getString(KEY_SELECTED_ASR_MODEL, ModelCatalog.SHERPA_ONNX_CANARY_MULTILANG.id)
            ?: ModelCatalog.SHERPA_ONNX_CANARY_MULTILANG.id
        set(value) = prefs.edit().putString(KEY_SELECTED_ASR_MODEL, value).apply()

    // ============================================
    // Power Management
    // ============================================

    var powerMode: PowerMode
        get() {
            val modeName = prefs.getString(KEY_POWER_MODE, PowerMode.BALANCED.name)
                ?: PowerMode.BALANCED.name
            return try {
                PowerMode.valueOf(modeName)
            } catch (e: Exception) {
                PowerMode.BALANCED
            }
        }
        set(value) = prefs.edit().putString(KEY_POWER_MODE, value.name).apply()

    // ============================================
    // Generation Settings
    // ============================================

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 1024)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value.coerceIn(128, 4096)).apply()

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_TEMPERATURE, value.coerceIn(0.1f, 2.0f)).apply()

    var contextLength: Int
        get() = prefs.getInt(KEY_CONTEXT_LENGTH, 2048)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_LENGTH, value.coerceIn(512, 4096)).apply()

    // ============================================
    // TTS Settings
    // ============================================

    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_RATE, value.coerceIn(0.5f, 2.0f)).apply()

    var ttsPitch: Float
        get() = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_PITCH, value.coerceIn(0.5f, 2.0f)).apply()

    // ============================================
    // ASR Settings
    // ============================================

    var asrEnabled: Boolean
        get() = prefs.getBoolean(KEY_ASR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ASR_ENABLED, value).apply()

    // ============================================
    // Microphone Settings
    // ============================================

    var microphoneEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICROPHONE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MICROPHONE_ENABLED, value).apply()

    var autoSendVoiceTranscription: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND_VOICE_TRANSCRIPTION, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SEND_VOICE_TRANSCRIPTION, value).apply()

    var voiceMaxDurationMs: Long
        get() = prefs.getLong(KEY_VOICE_MAX_DURATION_MS, 30000L) // 30 seconds default
        set(value) = prefs.edit().putLong(KEY_VOICE_MAX_DURATION_MS, value.coerceIn(5000L, 120000L)).apply()

    var voiceNoiseThreshold: Float
        get() = prefs.getFloat(KEY_VOICE_NOISE_THRESHOLD, 0.1f)
        set(value) = prefs.edit().putFloat(KEY_VOICE_NOISE_THRESHOLD, value.coerceIn(0.0f, 1.0f)).apply()

    // ============================================
    // RAG Settings
    // ============================================

    var ragEnabled: Boolean
        get() = prefs.getBoolean(KEY_RAG_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RAG_ENABLED, value).apply()

    var ragAutoIndex: Boolean
        get() = prefs.getBoolean(KEY_RAG_AUTO_INDEX, false)  // Disabled by default (battery)
        set(value) = prefs.edit().putBoolean(KEY_RAG_AUTO_INDEX, value).apply()

    var structuredOutput: Boolean
        get() = prefs.getBoolean(KEY_STRUCTURED_OUTPUT, false)  // Disabled by default
        set(value) = prefs.edit().putBoolean(KEY_STRUCTURED_OUTPUT, value).apply()

    // ============================================
    // AI Sharing & Hosting Settings
    // ============================================

    var aiMessageSharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_MESSAGE_SHARING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_MESSAGE_SHARING_ENABLED, value).apply()

    var aiHostingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_HOSTING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_HOSTING_ENABLED, value).apply()

    var disasterModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DISASTER_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISASTER_MODE_ENABLED, value).apply()

    var functionCallsEnabled: Boolean
        get() = prefs.getBoolean(KEY_FUNCTION_CALLS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FUNCTION_CALLS_ENABLED, value).apply()

    // ============================================
    // Privacy Settings
    // ============================================

    var analyticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANALYTICS_ENABLED, false)  // Disabled by default
        set(value) = prefs.edit().putBoolean(KEY_ANALYTICS_ENABLED, value).apply()

    // ============================================
    // Helper Methods
    // ============================================

    /**
     * Get current model configuration based on settings
     */
    fun getModelConfig(): ModelConfig {
        return when (powerMode) {
            PowerMode.ULTRA_SAVER -> ModelConfig.powerSaver()
            PowerMode.SAVER -> ModelConfig.balanced().copy(
                nCtx = contextLength / 2,
                maxTokens = maxTokens / 2
            )
            PowerMode.BALANCED -> ModelConfig.balanced().copy(
                nCtx = contextLength,
                maxTokens = maxTokens,
                temperature = temperature
            )
            PowerMode.PERFORMANCE -> ModelConfig.performance().copy(
                nCtx = contextLength,
                maxTokens = maxTokens,
                temperature = temperature
            )
        }
    }

    /**
     * Get selected LLM model object
     */
    fun getSelectedLLMModel(): AIModel? {
        return ModelCatalog.getModelById(selectedLLMModel)
    }

    /**
     * Get selected embedding model object
     */
    fun getSelectedEmbeddingModel(): AIModel? {
        return ModelCatalog.getModelById(selectedEmbeddingModel)
    }

    /**
     * Get selected ASR model object
     */
    fun getSelectedASRModel(): AIModel? {
        return ModelCatalog.getModelById(selectedASRModel)
    }

    /**
     * Migrate old model IDs to current IDs
     */
    private fun migrateModelId(id: String): String {
        return when (id) {
            "granite-4.0-micro-q4" -> ModelCatalog.GRANITE_4_0_MICRO_Q4.id
            "granite-4.0-micro-q8" -> ModelCatalog.GRANITE_4_0_MICRO_Q8.id
            else -> id
        }
    }

    /**
     * Reset to defaults
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    /**
     * Export settings as JSON string (for backup)
     */
    fun exportSettings(): String {
        return buildString {
            append("{")
            append("\"ai_enabled\": $aiEnabled,")
            append("\"selected_llm\": \"$selectedLLMModel\",")
            append("\"power_mode\": \"${powerMode.name}\",")
            append("\"max_tokens\": $maxTokens,")
            append("\"temperature\": $temperature,")
            append("\"tts_enabled\": $ttsEnabled,")
            append("\"asr_enabled\": $asrEnabled,")
            append("\"microphone_enabled\": $microphoneEnabled,")
            append("\"auto_send_voice\": $autoSendVoiceTranscription,")
            append("\"voice_max_duration\": $voiceMaxDurationMs,")
            append("\"rag_enabled\": $ragEnabled,")
            append("\"structured_output\": $structuredOutput")
            append("}")
        }
    }

    /**
     * Get settings summary for display
     */
    data class SettingsSummary(
        val aiEnabled: Boolean,
        val selectedLLM: String,
        val powerMode: PowerMode,
        val ttsEnabled: Boolean,
        val asrEnabled: Boolean,
        val microphoneEnabled: Boolean,
        val ragEnabled: Boolean,
        val structuredOutput: Boolean
    )

    fun getSettingsSummary(): SettingsSummary {
        return SettingsSummary(
            aiEnabled = aiEnabled,
            selectedLLM = getSelectedLLMModel()?.name ?: "None",
            powerMode = powerMode,
            ttsEnabled = ttsEnabled,
            asrEnabled = asrEnabled,
            microphoneEnabled = microphoneEnabled,
            ragEnabled = ragEnabled,
            structuredOutput = structuredOutput
        )
    }
}

/**
 * Power management modes
 */
enum class PowerMode {
    ULTRA_SAVER,   // Minimal: 1 thread, 512 tokens, 1024 context
    SAVER,         // Light: 2 threads, 1024 tokens, 2048 context
    BALANCED,      // Normal: 2 threads, 1024 tokens, 2048 context
    PERFORMANCE    // Max: 4 threads, 2048 tokens, 4096 context
}
