package com.bitchat.android.ai

import android.util.Log
import com.bitchat.android.model.BitchatMessage

/**
 * Disaster TTS Service
 *
 * Monitors incoming messages for emergency-related content and
 * automatically reads them aloud using TTS. This is critical during
 * disasters when users may not be able to look at their screen
 * (e.g., navigating debris, hands occupied, low visibility).
 *
 * Triggers on:
 *  - AI-generated messages (isAIGenerated flag)
 *  - Messages containing emergency keywords
 *  - Messages from AI host responses ([AI_RES] prefix)
 *  - Shared AI messages ([AI] prefix)
 */
class DisasterTTSService(
    private val ttsService: TTSService,
    private val preferences: AIPreferences
) {
    companion object {
        private const val TAG = "DisasterTTSService"
        private const val MIN_TTS_INTERVAL_MS = 3000L // Don't read messages too rapidly
        private const val MAX_TTS_LENGTH = 500 // Truncate very long messages for TTS

        private val EMERGENCY_KEYWORDS = setOf(
            "earthquake", "flood", "fire", "evacuation", "tsunami",
            "emergency", "shelter", "rescue", "tornado", "hurricane",
            "warning", "alert", "danger", "help", "sos",
            "collapse", "aftershock", "landslide", "explosion",
            "medical", "injured", "trapped", "missing",
            "evacuate", "safe zone", "first aid", "water purification"
        )
    }

    private var lastTTSTimestamp = 0L

    /**
     * Process an incoming message and auto-read if disaster-relevant.
     *
     * @param message The received BitchatMessage
     * @return true if the message was read aloud
     */
    fun processIncomingMessage(message: BitchatMessage): Boolean {
        if (!preferences.disasterModeEnabled) return false
        if (!preferences.ttsEnabled) return false
        if (!ttsService.isAvailable()) return false

        // Rate limiting
        val now = System.currentTimeMillis()
        if (now - lastTTSTimestamp < MIN_TTS_INTERVAL_MS) {
            Log.d(TAG, "Rate limited TTS, skipping message")
            return false
        }

        val content = message.content
        val shouldRead = when {
            // Always read AI-generated messages in disaster mode
            message.isAIGenerated -> true
            // Read shared AI messages
            content.startsWith(AIMessageSharing.AI_MESSAGE_PREFIX) -> true
            // Read AI host responses
            content.startsWith(AIHostService.AI_RES_PREFIX) -> true
            // Read messages containing emergency keywords
            containsEmergencyKeyword(content) -> true
            else -> false
        }

        if (!shouldRead) return false

        val textToRead = prepareForTTS(content, message.sender)
        ttsService.speak(textToRead)
        lastTTSTimestamp = now
        Log.d(TAG, "Auto-reading disaster message from ${message.sender}")
        return true
    }

    /**
     * Check if text contains emergency keywords.
     */
    fun containsEmergencyKeyword(text: String): Boolean {
        val lower = text.lowercase()
        return EMERGENCY_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    /**
     * Prepare message content for TTS reading.
     * Strips protocol prefixes and formats for natural speech.
     */
    private fun prepareForTTS(content: String, sender: String): String {
        var text = content

        // Strip protocol prefixes
        text = text.removePrefix(AIMessageSharing.AI_MESSAGE_PREFIX)
        text = text.removePrefix(AIHostService.AI_RES_PREFIX)

        // Remove request IDs from host responses
        val colonIdx = text.indexOf(':')
        if (colonIdx in 1..10 && content.startsWith(AIHostService.AI_RES_PREFIX)) {
            text = text.substring(colonIdx + 1)
        }

        // Truncate for TTS
        if (text.length > MAX_TTS_LENGTH) {
            text = text.take(MAX_TTS_LENGTH) + ". Message truncated."
        }

        // Add sender context
        return "Message from $sender: $text"
    }

    /**
     * Force-read a message regardless of disaster mode.
     * Used for critical broadcasts.
     */
    fun forceRead(text: String) {
        if (!ttsService.isAvailable()) {
            Log.w(TAG, "TTS not available for force read")
            return
        }

        val truncated = if (text.length > MAX_TTS_LENGTH) {
            text.take(MAX_TTS_LENGTH) + ". Message truncated."
        } else text

        ttsService.speak(truncated)
        lastTTSTimestamp = System.currentTimeMillis()
    }

    /**
     * Get the list of emergency keywords for display purposes.
     */
    fun getEmergencyKeywords(): Set<String> = EMERGENCY_KEYWORDS
}
