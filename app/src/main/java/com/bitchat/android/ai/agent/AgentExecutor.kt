package com.bitchat.android.ai.agent

import android.content.Context
import android.util.Log
import com.bitchat.android.ai.AIResponse
import com.bitchat.android.ai.AIService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/**
 * Agent Executor
 *
 * Implements a ReAct (Reasoning + Acting) loop:
 * 1. LLM receives user query + device context + tool descriptions
 * 2. LLM outputs Thought + Action (tool call as JSON)
 * 3. Executor parses and runs the tool
 * 4. Observation (tool result) is fed back to LLM
 * 5. Repeat until LLM outputs a final answer
 *
 * Max iterations: 5 (prevent infinite loops)
 * Timeout: 30 seconds per iteration
 */
class AgentExecutor(
    private val context: Context,
    private val aiService: AIService
) {

    companion object {
        private const val TAG = "AgentExecutor"
        const val MAX_ITERATIONS = 5
        const val ITERATION_TIMEOUT_MS = 30_000L

        // Markers the LLM uses in its output
        private const val TOOL_CALL_START = "<tool_call>"
        private const val TOOL_CALL_END = "</tool_call>"
        private const val FINAL_ANSWER_START = "<final_answer>"
        private const val FINAL_ANSWER_END = "</final_answer>"
    }

    private val toolExecutors = ToolExecutors(context)
    private val pendingConfirmations = mutableMapOf<String, ToolCall>()

    /**
     * Execute the full ReAct agent loop.
     *
     * Emits [AgentEvent]s as the agent reasons, calls tools, and produces a final answer.
     * The flow completes when the agent produces a final answer or hits the iteration limit.
     */
    fun execute(
        userQuery: String,
        disasterMode: Boolean = false
    ): Flow<AgentEvent> = flow {
        val history = mutableListOf<AgentStep>()

        Log.i(TAG, "Starting agent execution: query='$userQuery', disasterMode=$disasterMode")

        for (iteration in 1..MAX_ITERATIONS) {
            Log.d(TAG, "Iteration $iteration/$MAX_ITERATIONS")

            // Build the full prompt with history
            val prompt = buildAgentPrompt(userQuery, history, disasterMode)

            // Call the LLM with a timeout
            val llmOutput = withTimeoutOrNull(ITERATION_TIMEOUT_MS) {
                collectLLMResponse(prompt)
            }

            if (llmOutput == null) {
                Log.w(TAG, "LLM timed out on iteration $iteration")
                emit(AgentEvent.Error("LLM response timed out after ${ITERATION_TIMEOUT_MS / 1000}s"))
                return@flow
            }

            Log.d(TAG, "LLM output (iteration $iteration): ${llmOutput.take(500)}")

            // Check for final answer
            val finalAnswer = extractFinalAnswer(llmOutput)
            if (finalAnswer != null) {
                val step = AgentStep(
                    thought = extractThought(llmOutput),
                    action = null,
                    observation = null,
                    isFinal = true
                )
                history.add(step)
                emit(AgentEvent.Thinking(step.thought))
                emit(AgentEvent.FinalAnswer(finalAnswer))
                Log.i(TAG, "Agent completed with final answer after $iteration iterations")
                return@flow
            }

            // Try to parse a tool call
            val toolCall = parseToolCall(llmOutput)
            val thought = extractThought(llmOutput)

            if (thought.isNotBlank()) {
                emit(AgentEvent.Thinking(thought))
            }

            if (toolCall != null) {
                Log.d(TAG, "Tool call: ${toolCall.toolName}(${toolCall.arguments})")
                emit(AgentEvent.ToolUse(toolCall.toolName, toolCall.arguments))

                // Check if confirmation is needed
                if (AgentToolRegistry.needsConfirmation(toolCall.toolName, disasterMode)) {
                    val toolDef = AgentToolRegistry.getToolByName(toolCall.toolName)
                    val desc = "Execute ${toolCall.toolName}: ${toolDef?.description ?: "unknown tool"}"
                    pendingConfirmations[toolCall.toolName] = toolCall
                    emit(AgentEvent.ConfirmationRequired(toolCall.toolName, desc))

                    // Add a step noting confirmation is pending
                    val step = AgentStep(
                        thought = thought,
                        action = toolCall,
                        observation = "AWAITING_CONFIRMATION: User must confirm before ${toolCall.toolName} can execute.",
                        isFinal = false
                    )
                    history.add(step)
                    emit(AgentEvent.ToolResponse(step.observation!!))
                    // Continue loop -- LLM will see the pending state and can do other things
                    continue
                }

                // Execute the tool
                val result = executeTool(toolCall, disasterMode)

                val observation = if (result.success) {
                    result.data
                } else {
                    "ERROR: ${result.error ?: "Unknown error"}"
                }

                val step = AgentStep(
                    thought = thought,
                    action = toolCall,
                    observation = observation,
                    isFinal = false
                )
                history.add(step)
                emit(AgentEvent.ToolResponse(observation))
            } else {
                // No tool call and no final answer -- treat the entire output as the answer
                val step = AgentStep(
                    thought = thought,
                    action = null,
                    observation = null,
                    isFinal = true
                )
                history.add(step)

                // Use the raw output (minus thought markers) as the final answer
                val answer = llmOutput
                    .replace(Regex("<thought>.*?</thought>", RegexOption.DOT_MATCHES_ALL), "")
                    .trim()
                    .ifBlank { llmOutput.trim() }
                emit(AgentEvent.FinalAnswer(answer))
                Log.i(TAG, "Agent completed (no tool call) after $iteration iterations")
                return@flow
            }
        }

        // Hit max iterations without a final answer
        Log.w(TAG, "Agent hit max iterations ($MAX_ITERATIONS)")
        val summary = history.lastOrNull()?.observation ?: "No result"
        emit(AgentEvent.FinalAnswer("I've gathered the following information but reached my reasoning limit:\n\n$summary"))
    }

    /**
     * Confirm a pending tool call that required user approval.
     * Returns the tool result.
     */
    suspend fun confirmToolCall(toolName: String): ToolResult {
        val toolCall = pendingConfirmations.remove(toolName)
            ?: return ToolResult(false, "", "No pending confirmation for $toolName")
        return executeTool(toolCall, disasterMode = true)
    }

    /**
     * Deny a pending tool call.
     */
    fun denyToolCall(toolName: String): ToolResult {
        pendingConfirmations.remove(toolName)
        return ToolResult(true, "Tool call '$toolName' was denied by user.", null)
    }

    // ---------- Internal helpers ----------

    /**
     * Collect the full LLM response from the streaming flow.
     */
    private suspend fun collectLLMResponse(prompt: String): String {
        val sb = StringBuilder()
        try {
            aiService.generateResponse(prompt).collect { response ->
                when (response) {
                    is AIResponse.Token -> sb.append(response.text)
                    is AIResponse.Completed -> {
                        // Use the completed text if available, otherwise keep what we have
                        if (response.fullText.isNotBlank() && sb.isEmpty()) {
                            sb.append(response.fullText)
                        }
                    }
                    is AIResponse.Error -> {
                        Log.e(TAG, "LLM error: ${response.message}")
                        if (sb.isEmpty()) {
                            sb.append("ERROR: ${response.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception collecting LLM response", e)
            if (sb.isEmpty()) {
                sb.append("ERROR: ${e.message}")
            }
        }
        return sb.toString()
    }

    /**
     * Parse a tool call from the LLM output.
     *
     * Expected format:
     * ```
     * <tool_call>
     * {"name": "tool_name", "arguments": {"param": "value"}}
     * </tool_call>
     * ```
     */
    internal fun parseToolCall(llmOutput: String): ToolCall? {
        val startIdx = llmOutput.indexOf(TOOL_CALL_START)
        val endIdx = llmOutput.indexOf(TOOL_CALL_END)

        if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
            return null
        }

        val jsonStr = llmOutput
            .substring(startIdx + TOOL_CALL_START.length, endIdx)
            .trim()

        return try {
            val json = JSONObject(jsonStr)
            val name = json.getString("name")
            val argsObj = json.optJSONObject("arguments") ?: JSONObject()

            val args = mutableMapOf<String, Any>()
            for (key in argsObj.keys()) {
                args[key] = argsObj.get(key)
            }

            // Validate that the tool exists
            if (AgentToolRegistry.getToolByName(name) == null) {
                Log.w(TAG, "LLM requested unknown tool: $name")
                null
            } else {
                ToolCall(name, args)
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to parse tool call JSON: $jsonStr", e)
            null
        }
    }

    /**
     * Extract the thought section from LLM output.
     */
    private fun extractThought(llmOutput: String): String {
        val thoughtRegex = Regex("<thought>(.*?)</thought>", RegexOption.DOT_MATCHES_ALL)
        val match = thoughtRegex.find(llmOutput)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    /**
     * Extract the final answer from LLM output.
     */
    private fun extractFinalAnswer(llmOutput: String): String? {
        val startIdx = llmOutput.indexOf(FINAL_ANSWER_START)
        val endIdx = llmOutput.indexOf(FINAL_ANSWER_END)

        if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
            return null
        }

        return llmOutput
            .substring(startIdx + FINAL_ANSWER_START.length, endIdx)
            .trim()
    }

    /**
     * Execute a single tool and return the result.
     */
    private suspend fun executeTool(toolCall: ToolCall, disasterMode: Boolean): ToolResult {
        return try {
            toolExecutors.execute(toolCall.toolName, toolCall.arguments)
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: ${toolCall.toolName}", e)
            ToolResult(false, "", "Tool execution failed: ${e.message}")
        }
    }

    /**
     * Build the full agent prompt including system instructions, device context,
     * tool descriptions, conversation history, and the user query.
     */
    private fun buildAgentPrompt(
        userQuery: String,
        history: List<AgentStep>,
        disasterMode: Boolean
    ): String {
        val deviceContext = toolExecutors.getDeviceContextSummary()
        val systemPrompt = AgentPromptTemplates.systemPrompt(deviceContext, disasterMode)
        val toolPrompt = AgentToolRegistry.generateSystemPrompt()

        val sb = StringBuilder()
        sb.appendLine(systemPrompt)
        sb.appendLine()
        sb.appendLine(toolPrompt)
        sb.appendLine()
        sb.appendLine("USER QUERY: $userQuery")
        sb.appendLine()

        // Append conversation history (ReAct trace)
        if (history.isNotEmpty()) {
            sb.appendLine("PREVIOUS STEPS:")
            for ((i, step) in history.withIndex()) {
                sb.appendLine("--- Step ${i + 1} ---")
                if (step.thought.isNotBlank()) {
                    sb.appendLine("Thought: ${step.thought}")
                }
                if (step.action != null) {
                    sb.appendLine("Action: ${step.action.toolName}(${formatArgs(step.action.arguments)})")
                }
                if (step.observation != null) {
                    sb.appendLine("Observation: ${step.observation}")
                }
                sb.appendLine()
            }
            sb.appendLine("Based on the above, continue reasoning. Use <thought>...</thought> for thinking, <tool_call>...</tool_call> for actions, or <final_answer>...</final_answer> for your response.")
        } else {
            sb.appendLine("Respond using <thought>...</thought> for thinking, <tool_call>...</tool_call> for tool use, or <final_answer>...</final_answer> for your answer.")
        }

        return sb.toString()
    }

    private fun formatArgs(args: Map<String, Any>): String {
        return args.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }
}

// ---------- Data classes ----------

data class ToolCall(
    val toolName: String,
    val arguments: Map<String, Any>
)

data class ToolResult(
    val success: Boolean,
    val data: String,
    val error: String? = null
)

data class AgentStep(
    val thought: String,
    val action: ToolCall?,
    val observation: String?,
    val isFinal: Boolean = false
)

sealed class AgentEvent {
    data class Thinking(val thought: String) : AgentEvent()
    data class ToolUse(val tool: String, val args: Map<String, Any>) : AgentEvent()
    data class ToolResponse(val result: String) : AgentEvent()
    data class FinalAnswer(val answer: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class ConfirmationRequired(val tool: String, val description: String) : AgentEvent()
}
