package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import com.bitchat.android.ai.rag.DisasterRAGService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * AI Chat Service
 * 
 * Integrates AI capabilities with chat functionality
 * Provides voice input/output, text generation, and RAG features
 */
class AIChatService(
    private val context: Context,
    private val aiManager: AIManager
) {

    companion object {
        private const val TAG = "AIChatService"
    }

    private val voiceInputService = VoiceInputService(context)
    private val asrService = ASRService(context)

    /** AI message sharing service — broadcasts AI responses to mesh peers */
    val aiMessageSharing = AIMessageSharing(aiManager.preferences)

    /** Disaster RAG service — augments AI prompts with safety knowledge */
    val disasterRAG = DisasterRAGService(aiManager.preferences)

    /**
     * Process a text message with AI
     * 
     * @param message User message
     * @param channelId Channel ID for context
     * @param useRAG Whether to use RAG for context
     * @return AI response
     */
    suspend fun processMessage(
        message: String,
        channelId: String? = null,
        useRAG: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        
        if (!aiManager.isAIReady()) {
            return@withContext "AI system is not ready. Please check model status."
        }

        try {
            Log.d(TAG, "Processing message: $message")
            
            // Get conversation context and augment with disaster RAG
            var context = if (useRAG && aiManager.preferences.ragEnabled) {
                val recentMessages = aiManager.conversationContext.getRecentMessages(channelId, 5)
                val contextText = recentMessages.joinToString("\n") { "${it.role}: ${it.content}" }
                "Previous conversation:\n$contextText\n\nUser: $message"
            } else {
                message
            }

            // Augment with disaster knowledge if relevant
            context = disasterRAG.augmentPrompt(context)

            // Generate AI response with comprehensive safety checks
            var fullResponse = ""
            try {
                // Add timeout and safety wrapper
                kotlinx.coroutines.withTimeoutOrNull(65_000L) { // 65 seconds total timeout
                    try {
                        aiManager.aiService.generateResponse(context).collect { response ->
                            try {
                                when (response) {
                                    is AIResponse.Token -> {
                                        // Safety check for response length
                                        if (fullResponse.length + response.text.length > 15000) {
                                            Log.w(TAG, "Response too long, truncating")
                                            fullResponse += "\n\n[Response truncated due to length]"
                                            return@collect
                                        }
                                        fullResponse += response.text
                                    }
                                    is AIResponse.Completed -> {
                                        // Add to conversation context
                                        try {
                                            aiManager.conversationContext.addUserMessage(channelId, message)
                                            aiManager.conversationContext.addAIMessage(channelId, fullResponse)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to add to conversation context", e)
                                        }

                                        // Speak response if TTS is enabled
                                        if (aiManager.preferences.ttsEnabled) {
                                            try {
                                                aiManager.aiService.speak(fullResponse)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Failed to speak response", e)
                                            }
                                        }

                                        // Share AI response with nearby peers
                                        try {
                                            aiMessageSharing.shareWithPeers(message, fullResponse, channelId)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to share AI response with peers", e)
                                        }

                                        Log.d(TAG, "AI response: $fullResponse")
                                    }
                                    is AIResponse.Error -> {
                                        Log.e(TAG, "AI generation error: ${response.message}")
                                        fullResponse = "Error: ${response.message}"
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing AI response", e)
                                fullResponse = "Error processing response: ${e.message ?: "Unknown error"}"
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error collecting AI response", e)
                        fullResponse = "Error collecting response: ${e.message ?: "Unknown error"}"
                    }
                } ?: run {
                    Log.w(TAG, "AI response generation timed out")
                    fullResponse = "Response generation timed out. Please try again with a shorter prompt."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Critical error during AI response generation", e)
                fullResponse = "Critical error generating response: ${e.message ?: "Unknown error"}"
            }
            
            return@withContext fullResponse

        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
            return@withContext "Sorry, I encountered an error processing your message."
        }
    }

    /**
     * Process voice input
     * 
     * @param maxDurationMs Maximum recording duration
     * @param channelId Channel ID for context
     * @return AI response to the spoken message
     */
    suspend fun processVoiceInput(
        maxDurationMs: Long = 10000, // 10 seconds max
        channelId: String? = null
    ): String = withContext(Dispatchers.IO) {
        
        if (!voiceInputService.hasMicrophonePermission()) {
            return@withContext "Microphone permission required for voice input."
        }

        if (!aiManager.preferences.asrEnabled) {
            return@withContext "ASR is disabled. Please enable it in settings."
        }

        try {
            Log.d(TAG, "Starting voice input processing")
            
            // Start recording
            val audioFile = voiceInputService.startRecording(maxDurationMs)
            if (audioFile == null) {
                return@withContext "Failed to start recording."
            }

            // Wait for recording to complete (simplified - in real app you'd have UI controls)
            kotlinx.coroutines.delay(maxDurationMs)
            voiceInputService.stopRecording()

            // Transcribe audio
            val transcription = asrService.transcribeFile(File(audioFile))
            if (transcription.isNullOrBlank()) {
                return@withContext "Could not understand the audio. Please try again."
            }

            Log.d(TAG, "Transcribed: $transcription")

            // Process the transcribed text
            return@withContext processMessage(transcription, channelId, true)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing voice input", e)
            return@withContext "Error processing voice input: ${e.message}"
        }
    }

    /**
     * Stream AI response (for real-time chat)
     * 
     * @param message User message
     * @param channelId Channel ID for context
     * @param useRAG Whether to use RAG
     * @return Flow of AI response tokens
     */
    fun streamResponse(
        message: String,
        channelId: String? = null,
        useRAG: Boolean = true
    ): Flow<String> = flow {
        
        if (!aiManager.isAIReady()) {
            emit("AI system is not ready. Please check model status.")
            return@flow
        }

        try {
            Log.d(TAG, "Streaming response for: $message")
            
            // Get conversation context and augment with disaster RAG
            var context = if (useRAG && aiManager.preferences.ragEnabled) {
                val recentMessages = aiManager.conversationContext.getRecentMessages(channelId, 5)
                val contextText = recentMessages.joinToString("\n") { "${it.role}: ${it.content}" }
                "Previous conversation:\n$contextText\n\nUser: $message"
            } else {
                message
            }

            // Augment with disaster knowledge if relevant
            context = disasterRAG.augmentPrompt(context)

            // Stream AI response
            var fullResponse = ""
            aiManager.aiService.generateResponse(context).collect { response ->
                when (response) {
                    is AIResponse.Token -> {
                        fullResponse += response.text
                        emit(response.text)
                    }
                    is AIResponse.Completed -> {
                        // Add to conversation context
                        aiManager.conversationContext.addUserMessage(channelId, message)
                        aiManager.conversationContext.addAIMessage(channelId, fullResponse)

                        // Speak response if TTS is enabled
                        if (aiManager.preferences.ttsEnabled) {
                            aiManager.aiService.speak(fullResponse)
                        }

                        // Share AI response with nearby peers
                        try {
                            aiMessageSharing.shareWithPeers(message, fullResponse, channelId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to share AI response with peers", e)
                        }

                        Log.d(TAG, "Streaming completed: $fullResponse")
                    }
                    is AIResponse.Error -> {
                        emit("Error: ${response.message}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error streaming response", e)
            emit("Error: ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Search conversation history
     * 
     * @param query Search query
     * @param channelId Channel ID to search in
     * @return List of matching messages
     */
    suspend fun searchHistory(
        query: String,
        channelId: String? = null
    ): List<com.nexa.sdk.bean.ChatMessage> = withContext(Dispatchers.IO) {
        
        if (!aiManager.preferences.ragEnabled) {
            Log.w(TAG, "RAG is disabled, cannot search history")
            return@withContext emptyList()
        }

        try {
            // Get all messages for the channel
            val allMessages = aiManager.conversationContext.getRecentMessages(channelId, 100)
            
            // Simple text search for now (could be enhanced with semantic search)
            return@withContext allMessages.filter { message ->
                message.content.contains(query, ignoreCase = true)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error searching history", e)
            return@withContext emptyList()
        }
    }

    /**
     * Summarize conversation
     * 
     * @param channelId Channel ID
     * @param messageCount Number of recent messages to summarize
     * @return Summary of the conversation
     */
    suspend fun summarizeConversation(
        channelId: String? = null,
        messageCount: Int = 10
    ): String = withContext(Dispatchers.IO) {
        
        if (!aiManager.isAIReady()) {
            return@withContext "AI system is not ready."
        }

        try {
            val recentMessages = aiManager.conversationContext.getRecentMessages(channelId, messageCount)
            if (recentMessages.isEmpty()) {
                return@withContext "No messages to summarize."
            }

            val conversationText = recentMessages.joinToString("\n") { "${it.role}: ${it.content}" }
            val summaryPrompt = "Please summarize the following conversation in 2-3 sentences:\n\n$conversationText"

            var summary = ""
            aiManager.aiService.generateResponse(summaryPrompt).collect { response ->
                when (response) {
                    is AIResponse.Token -> {
                        summary += response.text
                    }
                    is AIResponse.Completed -> {
                        // Summary completed
                    }
                    is AIResponse.Error -> {
                        summary = "Error creating summary: ${response.message}"
                    }
                }
            }
            
            return@withContext summary

        } catch (e: Exception) {
            Log.e(TAG, "Error summarizing conversation", e)
            return@withContext "Error creating summary: ${e.message}"
        }
    }

    /**
     * Translate text
     * 
     * @param text Text to translate
     * @param targetLanguage Target language code
     * @return Translated text
     */
    suspend fun translateText(
        text: String,
        targetLanguage: String
    ): String = withContext(Dispatchers.IO) {
        
        if (!aiManager.isAIReady()) {
            return@withContext "AI system is not ready."
        }

        try {
            val translatePrompt = "Translate the following text to $targetLanguage: $text"
            var translation = ""
            aiManager.aiService.generateResponse(translatePrompt).collect { response ->
                when (response) {
                    is AIResponse.Token -> {
                        translation += response.text
                    }
                    is AIResponse.Completed -> {
                        // Translation completed
                    }
                    is AIResponse.Error -> {
                        translation = "Translation error: ${response.message}"
                    }
                }
            }
            
            return@withContext translation

        } catch (e: Exception) {
            Log.e(TAG, "Error translating text", e)
            return@withContext "Translation error: ${e.message}"
        }
    }

    /**
     * Get AI status
     */
    fun getAIStatus(): AIStatus {
        return aiManager.getStatus()
    }

    /**
     * Check if voice input is available
     */
    fun isVoiceInputAvailable(): Boolean {
        return voiceInputService.hasMicrophonePermission() && 
               aiManager.preferences.asrEnabled &&
               asrService.isInitialized &&
               asrService.isReady()
    }
    
    /**
     * Get ASR service status
     */
    fun getASRStatus(): String {
        return if (asrService.isInitialized) {
            asrService.getCurrentModelInfo()
        } else {
            "ASR not initialized"
        }
    }
    
    /**
     * Check if microphone permission is available
     */
    fun hasMicrophonePermission(): Boolean {
        return voiceInputService.hasMicrophonePermission()
    }
    
    /**
     * Get AI manager for external access
     */
    fun getAIManager(): AIManager {
        return aiManager
    }

    /**
     * Release resources
     */
    fun release() {
        voiceInputService.release()
        asrService.release()
        Log.d(TAG, "AIChatService released")
    }
}
