package com.bitchat.android.ai.functions

import android.util.Log
import org.json.JSONObject

/**
 * Function Call Executor
 *
 * Parses AI responses for function call JSON, validates parameters
 * against the registry schema, and delegates execution to the
 * appropriate handler.
 *
 * Safety: Functions marked with requiresConfirmation return a
 * PendingConfirmation result that the UI must present to the user.
 */
class FunctionExecutor(
    private val disasterFunctions: DisasterFunctions
) {
    companion object {
        private const val TAG = "FunctionExecutor"
    }

    sealed class ExecutionResult {
        data class Success(val message: String) : ExecutionResult()
        data class PendingConfirmation(
            val functionName: String,
            val params: Map<String, Any>,
            val description: String
        ) : ExecutionResult()
        data class Error(val message: String) : ExecutionResult()
        object NotAFunctionCall : ExecutionResult()
    }

    /**
     * Try to parse and execute a function call from an AI response.
     *
     * @param aiResponse The full AI response text
     * @return Execution result, or NotAFunctionCall if the response isn't a function call
     */
    fun tryExecute(aiResponse: String): ExecutionResult {
        val json = extractFunctionCall(aiResponse) ?: return ExecutionResult.NotAFunctionCall

        return try {
            val functionName = json.getString("function")
            val params = json.optJSONObject("params") ?: JSONObject()

            Log.d(TAG, "Detected function call: $functionName")

            val functionDef = FunctionRegistry.getFunction(functionName)
            if (functionDef == null) {
                Log.w(TAG, "Unknown function: $functionName")
                return ExecutionResult.Error("Unknown function: $functionName")
            }

            // Validate required parameters
            val validationError = validateParams(functionDef, params)
            if (validationError != null) {
                return ExecutionResult.Error(validationError)
            }

            // Check if confirmation is needed
            if (functionDef.requiresConfirmation) {
                val paramMap = jsonToMap(params)
                val description = describeAction(functionName, paramMap)
                return ExecutionResult.PendingConfirmation(functionName, paramMap, description)
            }

            // Execute immediately
            execute(functionName, params)

        } catch (e: Exception) {
            Log.e(TAG, "Error executing function call", e)
            ExecutionResult.Error("Failed to execute function: ${e.message}")
        }
    }

    /**
     * Execute a confirmed function call.
     */
    fun executeConfirmed(functionName: String, params: Map<String, Any>): ExecutionResult {
        return try {
            val jsonParams = JSONObject(params)
            execute(functionName, jsonParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing confirmed function", e)
            ExecutionResult.Error("Failed to execute: ${e.message}")
        }
    }

    /**
     * Execute a function with given parameters.
     */
    private fun execute(functionName: String, params: JSONObject): ExecutionResult {
        return when (functionName) {
            "broadcast_emergency" -> {
                val message = params.getString("message")
                val severity = params.optString("severity", "warning")
                disasterFunctions.broadcastEmergency(message, severity)
            }

            "search_disaster_info" -> {
                val query = params.getString("query")
                disasterFunctions.searchDisasterInfo(query)
            }

            "set_disaster_mode" -> {
                val enabled = params.getBoolean("enabled")
                disasterFunctions.setDisasterMode(enabled)
            }

            "request_help" -> {
                val type = params.getString("type")
                val description = params.getString("description")
                disasterFunctions.requestHelp(type, description)
            }

            "share_ai_response" -> {
                val summary = params.getString("summary")
                disasterFunctions.shareAIResponse(summary)
            }

            "get_nearest_fm_station" -> {
                val latitude = params.getDouble("latitude")
                val longitude = params.getDouble("longitude")
                disasterFunctions.getNearestFmStation(latitude, longitude)
            }

            else -> ExecutionResult.Error("Unknown function: $functionName")
        }
    }

    /**
     * Extract a JSON function call from the AI response.
     * Looks for the first valid JSON object with a "function" key.
     */
    private fun extractFunctionCall(text: String): JSONObject? {
        // Look for JSON blocks in the response
        val braceStart = text.indexOf('{')
        if (braceStart < 0) return null

        // Find matching closing brace
        var depth = 0
        var braceEnd = -1
        for (i in braceStart until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        braceEnd = i
                        break
                    }
                }
            }
        }

        if (braceEnd < 0) return null

        return try {
            val jsonStr = text.substring(braceStart, braceEnd + 1)
            val json = JSONObject(jsonStr)
            if (json.has("function")) json else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validate function parameters against the registry schema.
     */
    private fun validateParams(def: FunctionRegistry.FunctionDef, params: JSONObject): String? {
        for (param in def.params) {
            if (param.required && !params.has(param.name)) {
                return "Missing required parameter: ${param.name}"
            }

            if (params.has(param.name) && param.enumValues != null) {
                val value = params.optString(param.name)
                if (value !in param.enumValues) {
                    return "Invalid value for ${param.name}: '$value'. Must be one of: ${param.enumValues}"
                }
            }
        }
        return null
    }

    /**
     * Generate a human-readable description of the action for confirmation.
     */
    private fun describeAction(functionName: String, params: Map<String, Any>): String {
        return when (functionName) {
            "broadcast_emergency" ->
                "Send emergency broadcast: \"${params["message"]}\" (severity: ${params["severity"]})"
            "set_disaster_mode" ->
                if (params["enabled"] == true) "Enable disaster mode" else "Disable disaster mode"
            "request_help" ->
                "Send help request (${params["type"]}): \"${params["description"]}\""
            else -> "Execute $functionName"
        }
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            map[key] = json.get(key)
        }
        return map
    }
}
