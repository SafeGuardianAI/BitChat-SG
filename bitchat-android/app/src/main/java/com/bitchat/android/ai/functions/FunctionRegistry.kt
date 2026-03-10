package com.bitchat.android.ai.functions

import android.util.Log

/**
 * Function Registry
 *
 * Defines the callable functions available to the on-device AI.
 * Each function has a name, description, parameter schema, and
 * whether it requires user confirmation before execution.
 *
 * The registry generates the tool definitions for the LLM's system
 * prompt so it knows what functions are available.
 */
object FunctionRegistry {

    private const val TAG = "FunctionRegistry"

    data class FunctionParam(
        val name: String,
        val type: String, // "string", "number", "boolean"
        val description: String,
        val required: Boolean = true,
        val enumValues: List<String>? = null
    )

    data class FunctionDef(
        val name: String,
        val description: String,
        val params: List<FunctionParam>,
        val requiresConfirmation: Boolean = false
    )

    val functions: List<FunctionDef> = listOf(
        FunctionDef(
            name = "broadcast_emergency",
            description = "Send an emergency broadcast message to all nearby peers via the mesh network. Use when the user reports a dangerous situation that others should know about.",
            params = listOf(
                FunctionParam("message", "string", "The emergency message to broadcast"),
                FunctionParam("severity", "string", "Severity level", enumValues = listOf("info", "warning", "critical"))
            ),
            requiresConfirmation = true
        ),

        FunctionDef(
            name = "search_disaster_info",
            description = "Search the local disaster knowledge base for safety information. Use when the user asks about emergency procedures, first aid, survival techniques, or disaster preparedness.",
            params = listOf(
                FunctionParam("query", "string", "Search query for disaster information")
            ),
            requiresConfirmation = false
        ),

        FunctionDef(
            name = "set_disaster_mode",
            description = "Toggle disaster mode which enables auto-TTS for emergency messages, increased mesh broadcast frequency, and AI message sharing. Use when the user indicates they are in a disaster situation.",
            params = listOf(
                FunctionParam("enabled", "boolean", "Whether to enable or disable disaster mode")
            ),
            requiresConfirmation = true
        ),

        FunctionDef(
            name = "request_help",
            description = "Send a help request to nearby peers describing what kind of help is needed. Use when the user needs assistance from others.",
            params = listOf(
                FunctionParam("type", "string", "Type of help needed", enumValues = listOf("medical", "rescue", "supplies", "shelter", "transport", "general")),
                FunctionParam("description", "string", "Detailed description of the situation and help needed")
            ),
            requiresConfirmation = true
        ),

        FunctionDef(
            name = "share_ai_response",
            description = "Share the current AI response with all nearby peers. Use when the AI has generated useful information that would help others.",
            params = listOf(
                FunctionParam("summary", "string", "Brief summary of what is being shared")
            ),
            requiresConfirmation = false
        )
    )

    /**
     * Get a function definition by name.
     */
    fun getFunction(name: String): FunctionDef? = functions.find { it.name == name }

    /**
     * Generate the system prompt addition that describes available tools.
     * This is injected into the AI's system prompt so it knows what it can call.
     */
    fun generateToolPrompt(): String {
        val sb = StringBuilder()
        sb.append("\n\n[AVAILABLE TOOLS]\n")
        sb.append("You can call the following tools by responding with a JSON function call.\n")
        sb.append("Format: {\"function\": \"<name>\", \"params\": {<parameters>}}\n")
        sb.append("Only use a tool when the user's request clearly matches the tool's purpose.\n")
        sb.append("If no tool is needed, respond normally without JSON.\n\n")

        for (fn in functions) {
            sb.append("Tool: ${fn.name}\n")
            sb.append("  Description: ${fn.description}\n")
            sb.append("  Parameters:\n")
            for (param in fn.params) {
                val required = if (param.required) " (required)" else " (optional)"
                val enums = param.enumValues?.joinToString(", ") { "\"$it\"" }?.let { " [$it]" } ?: ""
                sb.append("    - ${param.name}: ${param.type}$required$enums — ${param.description}\n")
            }
            if (fn.requiresConfirmation) {
                sb.append("  ⚠️ Requires user confirmation before execution.\n")
            }
            sb.append("\n")
        }

        sb.append("[END TOOLS]\n")
        return sb.toString()
    }
}
