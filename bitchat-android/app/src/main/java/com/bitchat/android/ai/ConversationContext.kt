package com.bitchat.android.ai

import com.nexa.sdk.bean.ChatMessage
import java.util.ArrayDeque

/**
 * Manages conversation context for AI
 *
 * Features:
 * - Per-channel context isolation
 * - Memory-efficient sliding window
 * - Token counting and limits
 * - Context summarization (future)
 */
class ConversationContext(
    private val maxMessages: Int = 50,        // Keep last 50 messages
    private val maxTokensEstimate: Int = 2048 // Rough token limit
) {

    // Context per channel
    private val channelContexts = mutableMapOf<String, ArrayDeque<ChatMessage>>()

    // System prompts per channel (optional)
    private val systemPrompts = mutableMapOf<String, String>()

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AI assistant in SafeGuardian, a secure mesh messaging app. " +
            "Provide concise, clear, and friendly responses. You have access to recent conversation history."
    }

    /**
     * Add a user message to context
     */
    fun addUserMessage(channelId: String?, message: String) {
        val context = getOrCreateContext(channelId)

        // Add message
        context.addLast(ChatMessage("user", message))

        // Prune if needed
        pruneContext(context)
    }

    /**
     * Add an AI response to context
     */
    fun addAIMessage(channelId: String?, message: String) {
        val context = getOrCreateContext(channelId)

        // Add message
        context.addLast(ChatMessage("assistant", message))

        // Prune if needed
        pruneContext(context)
    }

    /**
     * Get full context for a channel (with system prompt)
     */
    fun getContext(channelId: String?): Array<ChatMessage> {
        val systemPrompt = systemPrompts[channelId ?: "global"] ?: DEFAULT_SYSTEM_PROMPT
        val context = getOrCreateContext(channelId)

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt))
        messages.addAll(context)

        return messages.toTypedArray()
    }

    /**
     * Get recent messages only (for display or limited context)
     */
    fun getRecentMessages(channelId: String?, count: Int = 10): List<ChatMessage> {
        val context = getOrCreateContext(channelId)
        return context.toList().takeLast(count)
    }

    /**
     * Set custom system prompt for a channel
     */
    fun setSystemPrompt(channelId: String?, prompt: String) {
        systemPrompts[channelId ?: "global"] = prompt
    }

    /**
     * Clear context for a channel
     */
    fun clearContext(channelId: String?) {
        channelContexts.remove(channelId ?: "global")
        systemPrompts.remove(channelId ?: "global")
    }

    /**
     * Clear all contexts
     */
    fun clearAllContexts() {
        channelContexts.clear()
        systemPrompts.clear()
    }

    /**
     * Get context size (number of messages)
     */
    fun getContextSize(channelId: String?): Int {
        return channelContexts[channelId ?: "global"]?.size ?: 0
    }

    /**
     * Estimate token count (rough approximation)
     * Real tokenization would require the model's tokenizer
     */
    fun estimateTokenCount(channelId: String?): Int {
        val context = getOrCreateContext(channelId)
        var totalTokens = 0

        for (message in context) {
            // Rough estimate: 1 token ≈ 4 characters for English
            totalTokens += message.content.length / 4
        }

        return totalTokens
    }

    /**
     * Check if context is near token limit
     */
    fun isNearTokenLimit(channelId: String?): Boolean {
        return estimateTokenCount(channelId) > (maxTokensEstimate * 0.8).toInt()
    }

    /**
     * Get or create context for a channel
     */
    private fun getOrCreateContext(channelId: String?): ArrayDeque<ChatMessage> {
        val key = channelId ?: "global"
        return channelContexts.getOrPut(key) {
            ArrayDeque(maxMessages)
        }
    }

    /**
     * Prune context to stay within limits
     */
    private fun pruneContext(context: ArrayDeque<ChatMessage>) {
        // Remove oldest messages if over limit
        while (context.size > maxMessages) {
            context.removeFirst()
        }

        // Rough token-based pruning
        while (estimateTokensInQueue(context) > maxTokensEstimate && context.size > 2) {
            context.removeFirst()
        }
    }

    /**
     * Estimate tokens in a message queue
     */
    private fun estimateTokensInQueue(queue: ArrayDeque<ChatMessage>): Int {
        var totalTokens = 0
        for (message in queue) {
            totalTokens += message.content.length / 4
        }
        return totalTokens
    }

    /**
     * Summarize old context (future feature)
     * Could use AI to summarize old messages into a single context message
     */
    suspend fun summarizeOldContext(channelId: String?): String {
        // TODO: Implement context summarization using AI
        // Take first 20 messages, generate summary, replace with summary message
        return "Context summary not yet implemented"
    }

    /**
     * Export context as text (for debugging or backup)
     */
    fun exportContext(channelId: String?): String {
        val context = getOrCreateContext(channelId)
        return buildString {
            appendLine("=== Conversation Context ===")
            appendLine("Channel: ${channelId ?: "global"}")
            appendLine("Messages: ${context.size}")
            appendLine("Estimated tokens: ${estimateTokenCount(channelId)}")
            appendLine()

            for (message in context) {
                appendLine("[${message.role}]: ${message.content}")
            }
        }
    }

    /**
     * Get context statistics
     */
    data class ContextStats(
        val messageCount: Int,
        val estimatedTokens: Int,
        val nearLimit: Boolean,
        val channels: Int
    )

    fun getStats(): ContextStats {
        val totalMessages = channelContexts.values.sumOf { it.size }
        val totalTokens = channelContexts.keys.sumOf { estimateTokenCount(it) }
        val nearLimit = channelContexts.keys.any { isNearTokenLimit(it) }

        return ContextStats(
            messageCount = totalMessages,
            estimatedTokens = totalTokens,
            nearLimit = nearLimit,
            channels = channelContexts.size
        )
    }
}
