package com.bitchat.android.ai

import android.util.Log
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AI Host Service
 *
 * Allows this device to serve as an AI inference host for nearby peers
 * who don't have a model loaded. Peers send inference requests over BLE
 * mesh, this device runs the model, and broadcasts the response.
 *
 * Protocol (over existing MESSAGE type):
 *   Request:  [AI_REQ]<requestId>:<prompt>
 *   Response: [AI_RES]<requestId>:<response>
 *
 * Safety:
 *   - Rate limiting per peer (1 request / 30s)
 *   - Max 3 concurrent pending requests
 *   - Max prompt length 500 chars
 *   - Max response length 1500 chars
 */
class AIHostService(
    private val aiService: AIService,
    private val preferences: AIPreferences
) {
    companion object {
        private const val TAG = "AIHostService"
        const val AI_REQ_PREFIX = "[AI_REQ]"
        const val AI_RES_PREFIX = "[AI_RES]"
        private const val RATE_LIMIT_MS = 30_000L
        private const val MAX_PENDING_REQUESTS = 3
        private const val MAX_PROMPT_LENGTH = 500
        private const val MAX_RESPONSE_LENGTH = 1500
    }

    private var meshService: BluetoothMeshService? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Rate limiting: peerID -> last request timestamp
    private val peerRateLimits = ConcurrentHashMap<String, Long>()
    private var pendingRequests = 0

    // Client-side: waiting for remote responses
    private val pendingClientRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun attachMeshService(mesh: BluetoothMeshService) {
        meshService = mesh
        Log.d(TAG, "Mesh service attached for AI hosting")
    }

    /**
     * Handle an incoming message — check if it's an AI request or response.
     * Called from the mesh delegate's didReceiveMessage.
     */
    fun handleIncomingMessage(message: BitchatMessage): Boolean {
        val content = message.content
        return when {
            content.startsWith(AI_REQ_PREFIX) -> {
                handleInferenceRequest(message)
                true
            }
            content.startsWith(AI_RES_PREFIX) -> {
                handleInferenceResponse(content)
                true
            }
            else -> false
        }
    }

    /**
     * SERVER SIDE: Process an inference request from a peer.
     */
    private fun handleInferenceRequest(message: BitchatMessage) {
        if (!preferences.aiHostingEnabled) {
            Log.d(TAG, "AI hosting disabled, ignoring request from ${message.sender}")
            return
        }

        if (!aiService.isModelLoaded()) {
            Log.d(TAG, "No model loaded, ignoring hosting request")
            return
        }

        val body = message.content.removePrefix(AI_REQ_PREFIX)
        val colonIdx = body.indexOf(':')
        if (colonIdx < 0) {
            Log.w(TAG, "Malformed AI request (no colon separator)")
            return
        }

        val requestId = body.substring(0, colonIdx)
        val prompt = body.substring(colonIdx + 1).take(MAX_PROMPT_LENGTH)
        val peerID = message.senderPeerID ?: message.sender

        // Rate limiting
        val now = System.currentTimeMillis()
        val lastRequest = peerRateLimits[peerID] ?: 0L
        if (now - lastRequest < RATE_LIMIT_MS) {
            Log.d(TAG, "Rate limited request from $peerID")
            return
        }

        // Queue capacity
        if (pendingRequests >= MAX_PENDING_REQUESTS) {
            Log.d(TAG, "Too many pending requests ($pendingRequests), dropping from $peerID")
            return
        }

        peerRateLimits[peerID] = now
        pendingRequests++

        Log.d(TAG, "Processing AI request $requestId from $peerID: ${prompt.take(60)}...")

        scope.launch {
            try {
                val response = StringBuilder()
                aiService.generateResponse(
                    prompt = prompt,
                    systemPrompt = "You are a helpful AI assistant in SafeGuardian, a disaster communication app. " +
                            "Be concise — keep answers under 200 words. Focus on practical, actionable advice."
                ).collect { result ->
                    when (result) {
                        is AIResponse.Token -> response.append(result.text)
                        is AIResponse.Completed -> { /* handled below */ }
                        is AIResponse.Error -> {
                            response.clear()
                            response.append("Error: ${result.message}")
                        }
                    }
                }

                val finalResponse = response.toString().take(MAX_RESPONSE_LENGTH)
                sendInferenceResponse(requestId, finalResponse, message.channel)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing AI request $requestId", e)
                sendInferenceResponse(requestId, "Error processing request", message.channel)
            } finally {
                pendingRequests--
            }
        }
    }

    /**
     * Send inference response back via mesh.
     */
    private fun sendInferenceResponse(requestId: String, response: String, channel: String?) {
        val mesh = meshService ?: return
        val content = "$AI_RES_PREFIX$requestId:$response"
        try {
            mesh.sendMessage(content, channel = channel)
            Log.d(TAG, "Sent AI response for $requestId (${response.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send AI response for $requestId", e)
        }
    }

    /**
     * CLIENT SIDE: Handle a response to one of our requests.
     */
    private fun handleInferenceResponse(content: String) {
        val body = content.removePrefix(AI_RES_PREFIX)
        val colonIdx = body.indexOf(':')
        if (colonIdx < 0) return

        val requestId = body.substring(0, colonIdx)
        val response = body.substring(colonIdx + 1)

        val deferred = pendingClientRequests.remove(requestId)
        if (deferred != null) {
            deferred.complete(response)
            Log.d(TAG, "Received remote AI response for $requestId")
        }
    }

    /**
     * CLIENT SIDE: Request inference from a remote peer host.
     *
     * Broadcasts an AI_REQ message and waits for a response.
     * Returns null if no response within timeout.
     */
    suspend fun requestRemoteInference(
        prompt: String,
        channel: String? = null,
        timeoutMs: Long = 60_000L
    ): String? = withContext(Dispatchers.IO) {
        val mesh = meshService ?: return@withContext null
        val requestId = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<String>()
        pendingClientRequests[requestId] = deferred

        val content = "$AI_REQ_PREFIX$requestId:${prompt.take(MAX_PROMPT_LENGTH)}"
        try {
            mesh.sendMessage(content, channel = channel)
            Log.d(TAG, "Sent remote inference request $requestId")
        } catch (e: Exception) {
            pendingClientRequests.remove(requestId)
            Log.e(TAG, "Failed to send remote inference request", e)
            return@withContext null
        }

        try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } catch (e: Exception) {
            pendingClientRequests.remove(requestId)
            Log.e(TAG, "Remote inference request $requestId failed", e)
            null
        }
    }

    /**
     * Check if this device can host AI for peers.
     */
    fun canHost(): Boolean {
        return preferences.aiHostingEnabled && aiService.isModelLoaded()
    }

    /**
     * Get hosting status summary.
     */
    fun getStatusSummary(): String {
        return buildString {
            append("AI Hosting: ${if (preferences.aiHostingEnabled) "Enabled" else "Disabled"}")
            append(" | Model: ${if (aiService.isModelLoaded()) "Loaded" else "Not loaded"}")
            append(" | Pending: $pendingRequests/$MAX_PENDING_REQUESTS")
        }
    }

    fun detach() {
        meshService = null
        scope.cancel()
        pendingClientRequests.values.forEach { it.cancel() }
        pendingClientRequests.clear()
        peerRateLimits.clear()
        Log.d(TAG, "AI Host Service detached")
    }
}
