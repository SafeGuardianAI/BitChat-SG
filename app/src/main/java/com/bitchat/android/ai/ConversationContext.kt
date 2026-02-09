package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Conversation Context - manages conversation history for AI
 */
class ConversationContext(private val context: Context) {
    
    companion object {
        private const val TAG = "ConversationContext"
        private const val MAX_MESSAGES = 20
    }
    
    private val conversations = mutableMapOf<String, MutableList<Message>>()
    
    data class Message(
        val role: String, // "user" or "assistant"
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    fun addUserMessage(channelId: String?, message: String) {
        val key = channelId ?: "default"
        val messages = conversations.getOrPut(key) { mutableListOf() }
        messages.add(Message("user", message))
        trimMessages(messages)
    }
    
    fun addAIMessage(channelId: String?, message: String) {
        val key = channelId ?: "default"
        val messages = conversations.getOrPut(key) { mutableListOf() }
        messages.add(Message("assistant", message))
        trimMessages(messages)
    }
    
    fun getContext(channelId: String?): String {
        val key = channelId ?: "default"
        val messages = conversations[key] ?: return ""
        return messages.takeLast(10).joinToString("\n") { "${it.role}: ${it.content}" }
    }
    
    fun clearContext(channelId: String?) {
        val key = channelId ?: "default"
        conversations.remove(key)
    }
    
    private fun trimMessages(messages: MutableList<Message>) {
        while (messages.size > MAX_MESSAGES) {
            messages.removeAt(0)
        }
    }
}
