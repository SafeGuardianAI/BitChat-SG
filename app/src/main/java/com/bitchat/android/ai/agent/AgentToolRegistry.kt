package com.bitchat.android.ai.agent

/**
 * Agent Tool Registry
 *
 * Defines all tools the on-device AI agent can invoke.
 * Tools are categorized by domain and risk level.
 *
 * Categories:
 * - QUERY: Read-only information gathering (no confirmation needed)
 * - ACTION: Device actions (may need confirmation)
 * - EMERGENCY: High-priority actions (auto-confirmed in disaster mode)
 * - MESH: BLE mesh network operations
 * - TRIAGE: Medical triage operations
 */
object AgentToolRegistry {

    data class ToolDefinition(
        val name: String,
        val description: String,
        val category: ToolCategory,
        val parameters: List<ToolParameter>,
        val requiresConfirmation: Boolean = false,
        val riskLevel: RiskLevel = RiskLevel.LOW
    )

    data class ToolParameter(
        val name: String,
        val type: String,  // "string", "number", "boolean"
        val description: String,
        val required: Boolean = true,
        val enumValues: List<String>? = null
    )

    enum class ToolCategory { QUERY, ACTION, EMERGENCY, MESH, TRIAGE }
    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    val tools: List<ToolDefinition> = listOf(
        // === QUERY TOOLS (no confirmation) ===
        ToolDefinition(
            name = "get_battery_status",
            description = "Get current battery level, charging state, temperature, and health",
            category = ToolCategory.QUERY,
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "get_location",
            description = "Get current GPS coordinates, accuracy, altitude, and speed",
            category = ToolCategory.QUERY,
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "get_network_status",
            description = "Get network connectivity state: WiFi, cellular, BLE mesh peers",
            category = ToolCategory.QUERY,
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "get_sensor_data",
            description = "Get sensor readings: accelerometer, barometer, light, proximity",
            category = ToolCategory.QUERY,
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "get_mesh_peers",
            description = "List connected BLE mesh peers with signal strength and last seen time",
            category = ToolCategory.QUERY,
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "get_device_state",
            description = "Get full device state snapshot: battery, network, location, storage, power mode",
            category = ToolCategory.QUERY,
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "search_disaster_info",
            description = "Search the disaster knowledge base for survival and safety information",
            category = ToolCategory.QUERY,
            parameters = listOf(
                ToolParameter("query", "string", "Search query about disaster preparedness")
            )
        ),
        ToolDefinition(
            name = "get_vital_data",
            description = "Get stored vital/medical data for the user",
            category = ToolCategory.QUERY,
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "get_pending_sync",
            description = "Get count and details of pending sync operations",
            category = ToolCategory.QUERY,
            parameters = emptyList()
        ),

        // === ACTION TOOLS (may need confirmation) ===
        ToolDefinition(
            name = "set_power_mode",
            description = "Set AI power mode: ultra_saver (minimal), balanced, performance",
            category = ToolCategory.ACTION,
            parameters = listOf(
                ToolParameter(
                    "mode", "string", "Power mode",
                    enumValues = listOf("ultra_saver", "balanced", "performance")
                )
            ),
            requiresConfirmation = false
        ),
        ToolDefinition(
            name = "enable_emergency_mode",
            description = "Enable disaster emergency mode: auto-TTS, high-priority mesh, GPS tracking",
            category = ToolCategory.ACTION,
            parameters = listOf(
                ToolParameter("enabled", "boolean", "Enable or disable emergency mode")
            ),
            requiresConfirmation = true,
            riskLevel = RiskLevel.MEDIUM
        ),
        ToolDefinition(
            name = "speak_aloud",
            description = "Speak text aloud using text-to-speech",
            category = ToolCategory.ACTION,
            parameters = listOf(
                ToolParameter("text", "string", "Text to speak"),
                ToolParameter(
                    "priority", "string", "Priority level",
                    required = false,
                    enumValues = listOf("low", "normal", "high", "emergency")
                )
            )
        ),
        ToolDefinition(
            name = "save_vital_data",
            description = "Save vital/medical data to encrypted local storage",
            category = ToolCategory.ACTION,
            parameters = listOf(
                ToolParameter("data_json", "string", "Vital data as JSON string")
            )
        ),
        ToolDefinition(
            name = "start_location_tracking",
            description = "Start GPS location tracking at specified accuracy",
            category = ToolCategory.ACTION,
            parameters = listOf(
                ToolParameter(
                    "mode", "string", "Tracking mode",
                    enumValues = listOf("passive", "balanced", "high_accuracy")
                )
            )
        ),

        // === EMERGENCY TOOLS (auto-confirmed in disaster mode) ===
        ToolDefinition(
            name = "broadcast_emergency",
            description = "Send emergency broadcast to all mesh peers with location and distress info",
            category = ToolCategory.EMERGENCY,
            parameters = listOf(
                ToolParameter("message", "string", "Emergency message"),
                ToolParameter("include_location", "boolean", "Include GPS coordinates", required = false)
            ),
            requiresConfirmation = true,
            riskLevel = RiskLevel.HIGH
        ),
        ToolDefinition(
            name = "request_rescue",
            description = "Send rescue request with triage data via mesh and any available network",
            category = ToolCategory.EMERGENCY,
            parameters = listOf(
                ToolParameter(
                    "triage_level", "string", "START triage category",
                    enumValues = listOf("immediate", "delayed", "minimal", "expectant")
                ),
                ToolParameter("description", "string", "Situation description"),
                ToolParameter("injuries", "string", "Injury description", required = false)
            ),
            requiresConfirmation = true,
            riskLevel = RiskLevel.CRITICAL
        ),
        ToolDefinition(
            name = "generate_cap_alert",
            description = "Generate a CAP v1.2 formatted emergency alert message",
            category = ToolCategory.EMERGENCY,
            parameters = listOf(
                ToolParameter(
                    "event_type", "string", "Type of emergency",
                    enumValues = listOf(
                        "earthquake", "fire", "flood", "tsunami",
                        "landslide", "collapse", "medical", "other"
                    )
                ),
                ToolParameter(
                    "severity", "string", "Severity level",
                    enumValues = listOf("extreme", "severe", "moderate", "minor")
                ),
                ToolParameter("description", "string", "Event description")
            ),
            requiresConfirmation = true,
            riskLevel = RiskLevel.HIGH
        ),
        ToolDefinition(
            name = "offload_data",
            description = "Offload critical data to BLE mesh peers for safekeeping (low battery scenario)",
            category = ToolCategory.EMERGENCY,
            parameters = emptyList(),
            requiresConfirmation = true,
            riskLevel = RiskLevel.MEDIUM
        )
    )

    /**
     * Generate the tool description prompt for the LLM.
     * Formatted for small models (3-4B params) with clear structure.
     */
    fun generateSystemPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("AVAILABLE TOOLS:")
        sb.appendLine()

        tools.forEachIndexed { index, tool ->
            sb.appendLine("${index + 1}. ${tool.name} [${tool.category}]")
            sb.appendLine("   ${tool.description}")
            if (tool.parameters.isNotEmpty()) {
                sb.appendLine("   Parameters:")
                for (param in tool.parameters) {
                    val reqTag = if (param.required) "required" else "optional"
                    val enumTag = if (param.enumValues != null) " (one of: ${param.enumValues.joinToString(", ")})" else ""
                    sb.appendLine("   - ${param.name} (${param.type}, $reqTag): ${param.description}$enumTag")
                }
            } else {
                sb.appendLine("   Parameters: none")
            }
            if (tool.requiresConfirmation) {
                sb.appendLine("   ⚠ Requires confirmation (risk: ${tool.riskLevel})")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Generate a JSON schema description of tools for structured output.
     */
    fun generateToolSchemaJson(): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        tools.forEachIndexed { index, tool ->
            sb.appendLine("  {")
            sb.appendLine("    \"name\": \"${tool.name}\",")
            sb.appendLine("    \"description\": \"${tool.description}\",")
            sb.appendLine("    \"parameters\": {")
            sb.appendLine("      \"type\": \"object\",")
            sb.appendLine("      \"properties\": {")
            tool.parameters.forEachIndexed { pIndex, param ->
                sb.appendLine("        \"${param.name}\": {")
                sb.appendLine("          \"type\": \"${param.type}\",")
                sb.appendLine("          \"description\": \"${param.description}\"")
                if (param.enumValues != null) {
                    sb.append(",\n          \"enum\": [${param.enumValues.joinToString(", ") { "\"$it\"" }}]")
                    sb.appendLine()
                }
                val pTrail = if (pIndex < tool.parameters.size - 1) "," else ""
                sb.appendLine("        }$pTrail")
            }
            sb.appendLine("      },")
            val requiredParams = tool.parameters.filter { it.required }.joinToString(", ") { "\"${it.name}\"" }
            sb.appendLine("      \"required\": [$requiredParams]")
            sb.appendLine("    }")
            val trail = if (index < tools.size - 1) "," else ""
            sb.appendLine("  }$trail")
        }
        sb.appendLine("]")
        return sb.toString()
    }

    /**
     * Look up a tool by name.
     */
    fun getToolByName(name: String): ToolDefinition? = tools.find { it.name == name }

    /**
     * Get all tools in a specific category.
     */
    fun getToolsByCategory(category: ToolCategory): List<ToolDefinition> =
        tools.filter { it.category == category }

    /**
     * Check whether a tool requires user confirmation given the current mode.
     * In disaster mode, EMERGENCY tools are auto-confirmed.
     */
    fun needsConfirmation(toolName: String, disasterMode: Boolean): Boolean {
        val tool = getToolByName(toolName) ?: return true
        if (!tool.requiresConfirmation) return false
        if (disasterMode && tool.category == ToolCategory.EMERGENCY) return false
        return true
    }
}
