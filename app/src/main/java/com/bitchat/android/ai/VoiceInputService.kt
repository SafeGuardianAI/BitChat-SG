package com.bitchat.android.ai

import android.content.Context
import android.util.Log

/**
 * Voice Input Service - handles voice input
 */
class VoiceInputService(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceInputService"
    }
    
    private var isListening = false
    
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        Log.d(TAG, "Starting voice input")
        isListening = true
        // Stub implementation
    }
    
    fun stopListening() {
        Log.d(TAG, "Stopping voice input")
        isListening = false
    }
    
    fun isListening(): Boolean = isListening
}
