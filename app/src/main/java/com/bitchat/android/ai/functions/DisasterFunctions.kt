package com.bitchat.android.ai.functions

import android.util.Log
import com.bitchat.android.ai.AIMessageSharing
import com.bitchat.android.ai.AIPreferences
import com.bitchat.android.ai.rag.DisasterRAGService
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.radio.EmergencyFmRepository

/**
 * Disaster-Specific Function Implementations
 *
 * Concrete implementations of the AI-callable functions for
 * disaster scenarios. These bridge AI decisions to actual device
 * actions like mesh broadcasts and settings changes.
 */
class DisasterFunctions(
    private val preferences: AIPreferences,
    private val ragService: DisasterRAGService,
    private val fmRepository: EmergencyFmRepository? = null
) {
    companion object {
        private const val TAG = "DisasterFunctions"
        private const val EMERGENCY_PREFIX = "🚨 EMERGENCY"
        private const val HELP_PREFIX = "🆘 HELP REQUEST"
    }

    private var meshService: BluetoothMeshService? = null
    private var aiMessageSharing: AIMessageSharing? = null

    fun attachMeshService(mesh: BluetoothMeshService) {
        meshService = mesh
    }

    fun attachAIMessageSharing(sharing: AIMessageSharing) {
        aiMessageSharing = sharing
    }

    /**
     * Broadcast an emergency message to all nearby peers.
     */
    fun broadcastEmergency(
        message: String,
        severity: String
    ): FunctionExecutor.ExecutionResult {
        val mesh = meshService ?: return FunctionExecutor.ExecutionResult.Error(
            "Mesh network not available"
        )

        val severityIcon = when (severity) {
            "critical" -> "🔴"
            "warning" -> "🟡"
            else -> "🔵"
        }

        val broadcastContent = "$EMERGENCY_PREFIX $severityIcon [$severity.uppercase()]\n$message"

        return try {
            mesh.sendMessage(broadcastContent)
            Log.d(TAG, "Emergency broadcast sent: $message (severity=$severity)")
            FunctionExecutor.ExecutionResult.Success(
                "Emergency broadcast sent to nearby peers: $message"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast emergency", e)
            FunctionExecutor.ExecutionResult.Error("Failed to send broadcast: ${e.message}")
        }
    }

    /**
     * Search the disaster knowledge base.
     */
    fun searchDisasterInfo(query: String): FunctionExecutor.ExecutionResult {
        return try {
            val results = ragService.searchKnowledge(query, maxResults = 3)

            if (results.isEmpty()) {
                FunctionExecutor.ExecutionResult.Success(
                    "No matching disaster information found for: \"$query\""
                )
            } else {
                val response = buildString {
                    append("Found ${results.size} relevant entries:\n\n")
                    for (result in results) {
                        append("📋 ${result.entry.title} (${result.entry.category})\n")
                        append(result.entry.content)
                        append("\n\n")
                    }
                }
                FunctionExecutor.ExecutionResult.Success(response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching disaster info", e)
            FunctionExecutor.ExecutionResult.Error("Search failed: ${e.message}")
        }
    }

    /**
     * Toggle disaster mode.
     */
    fun setDisasterMode(enabled: Boolean): FunctionExecutor.ExecutionResult {
        return try {
            preferences.disasterModeEnabled = enabled

            // When enabling disaster mode, also enable related features
            if (enabled) {
                preferences.ttsEnabled = true
                preferences.aiMessageSharingEnabled = true
            }

            val status = if (enabled) "enabled" else "disabled"
            Log.d(TAG, "Disaster mode $status")

            FunctionExecutor.ExecutionResult.Success(
                "Disaster mode $status." + if (enabled) {
                    " TTS auto-read and AI message sharing have been activated."
                } else ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting disaster mode", e)
            FunctionExecutor.ExecutionResult.Error("Failed to set disaster mode: ${e.message}")
        }
    }

    /**
     * Send a help request to nearby peers.
     */
    fun requestHelp(
        type: String,
        description: String
    ): FunctionExecutor.ExecutionResult {
        val mesh = meshService ?: return FunctionExecutor.ExecutionResult.Error(
            "Mesh network not available"
        )

        val typeEmoji = when (type) {
            "medical" -> "🏥"
            "rescue" -> "🚒"
            "supplies" -> "📦"
            "shelter" -> "🏠"
            "transport" -> "🚗"
            else -> "❓"
        }

        val helpContent = "$HELP_PREFIX $typeEmoji [$type.uppercase()]\n$description"

        return try {
            mesh.sendMessage(helpContent)
            Log.d(TAG, "Help request sent: $type — $description")
            FunctionExecutor.ExecutionResult.Success(
                "Help request broadcast to nearby peers: [$type] $description"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send help request", e)
            FunctionExecutor.ExecutionResult.Error("Failed to send help request: ${e.message}")
        }
    }

    /**
     * Share an AI response with nearby peers.
     */
    fun shareAIResponse(summary: String): FunctionExecutor.ExecutionResult {
        val sharing = aiMessageSharing ?: return FunctionExecutor.ExecutionResult.Error(
            "AI message sharing not available"
        )

        return try {
            sharing.shareWithPeers("Shared by AI", summary)
            Log.d(TAG, "AI response shared with peers")
            FunctionExecutor.ExecutionResult.Success(
                "AI response shared with nearby peers: $summary"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share AI response", e)
            FunctionExecutor.ExecutionResult.Error("Failed to share: ${e.message}")
        }
    }

    /**
     * Find the nearest emergency FM station to the given coordinates.
     * Returns a JSON-style summary the AI can read aloud or display.
     */
    fun getNearestFmStation(
        latitude: Double,
        longitude: Double
    ): FunctionExecutor.ExecutionResult {
        val repo = fmRepository ?: return FunctionExecutor.ExecutionResult.Error(
            "FM repository not available"
        )
        return try {
            val station = repo.findNearestSync(latitude, longitude)
                ?: return FunctionExecutor.ExecutionResult.Error("No FM stations found near your location")

            Log.d(TAG, "Nearest FM station: ${station.name} @ ${station.frequencyMHz} MHz in ${station.city}")
            FunctionExecutor.ExecutionResult.Success(
                "Nearest emergency FM station: ${station.name} on ${station.frequencyMHz} MHz in ${station.city}, ${station.country}. " +
                "Tune your FM radio to this frequency for emergency broadcasts."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error finding nearest FM station", e)
            FunctionExecutor.ExecutionResult.Error("Failed to find FM station: ${e.message}")
        }
    }

    fun detach() {
        meshService = null
        aiMessageSharing = null
    }
}
