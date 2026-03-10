package com.bitchat.android.ai

import android.util.Log
import com.bitchat.android.mesh.BluetoothMeshService

/**
 * AI Message Sharing Service
 *
 * Bridges AI-generated responses to the BLE mesh network so nearby
 * peers benefit from local AI inference during disaster scenarios.
 *
 * Messages are broadcast with an [AI] prefix so recipients can
 * distinguish AI-generated content from human messages.
 */
class AIMessageSharing(
    private val preferences: AIPreferences
) {
    companion object {
        private const val TAG = "AIMessageSharing"
        const val AI_MESSAGE_PREFIX = "[AI] "
        const val AI_QUERY_PREFIX = "[AI Query] "
        private const val MAX_SHARE_LENGTH = 2000 // BLE-friendly message size
        private const val MIN_SHARE_INTERVAL_MS = 5000L // Rate limit: 1 share per 5 seconds
    }

    private var meshService: BluetoothMeshService? = null
    private var lastShareTimestamp = 0L

    /**
     * Attach the mesh service for broadcasting.
     * Called once when the mesh is initialized.
     */
    fun attachMeshService(mesh: BluetoothMeshService) {
        meshService = mesh
        Log.d(TAG, "Mesh service attached for AI message sharing")
    }

    /**
     * Share an AI response with nearby peers via broadcast.
     *
     * @param userQuery The original user question (shared for context)
     * @param aiResponse The AI-generated answer
     * @param channel Optional channel to scope the broadcast
     */
    fun shareWithPeers(userQuery: String, aiResponse: String, channel: String? = null) {
        if (!preferences.aiMessageSharingEnabled) {
            Log.d(TAG, "AI message sharing is disabled")
            return
        }

        val mesh = meshService
        if (mesh == null) {
            Log.w(TAG, "Mesh service not attached, cannot share AI message")
            return
        }

        // Rate limiting
        val now = System.currentTimeMillis()
        if (now - lastShareTimestamp < MIN_SHARE_INTERVAL_MS) {
            Log.d(TAG, "Rate limited, skipping AI message share")
            return
        }

        // Truncate to BLE-friendly size
        val truncatedResponse = if (aiResponse.length > MAX_SHARE_LENGTH) {
            aiResponse.take(MAX_SHARE_LENGTH - 20) + "... [truncated]"
        } else {
            aiResponse
        }

        val shareContent = buildString {
            append(AI_MESSAGE_PREFIX)
            // Include a short version of the query for context
            val shortQuery = if (userQuery.length > 100) userQuery.take(97) + "..." else userQuery
            append("Q: $shortQuery\n")
            append("A: $truncatedResponse")
        }

        try {
            mesh.sendMessage(shareContent, channel = channel)
            lastShareTimestamp = now
            Log.d(TAG, "Shared AI response with peers (${shareContent.length} chars, channel=$channel)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share AI message with peers", e)
        }
    }

    /**
     * Check if an incoming message is an AI-shared message.
     */
    fun isAISharedMessage(content: String): Boolean {
        return content.startsWith(AI_MESSAGE_PREFIX)
    }

    /**
     * Parse the query and response from an AI-shared message.
     */
    fun parseAISharedMessage(content: String): Pair<String, String>? {
        if (!isAISharedMessage(content)) return null

        val body = content.removePrefix(AI_MESSAGE_PREFIX)
        val queryLine = body.lineSequence().firstOrNull { it.startsWith("Q: ") }
        val answerStart = body.indexOf("\nA: ")

        if (queryLine == null || answerStart < 0) return null

        val query = queryLine.removePrefix("Q: ")
        val answer = body.substring(answerStart + 4)
        return query to answer
    }

    fun detach() {
        meshService = null
        Log.d(TAG, "Mesh service detached")
    }
}
