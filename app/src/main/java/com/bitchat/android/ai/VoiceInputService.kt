package com.bitchat.android.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

class VoiceInputService(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceInputService"
    }
    
    private var isListening = false
    
    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        Log.d(TAG, "Starting voice input")
        isListening = true
    }
    
    fun startRecording(maxDurationMs: Long): String? {
        Log.d(TAG, "Starting recording (max ${maxDurationMs}ms)")
        isListening = true
        val outputFile = java.io.File(context.cacheDir, "voice_recording.wav")
        return outputFile.absolutePath
    }
    
    fun stopListening() {
        Log.d(TAG, "Stopping voice input")
        isListening = false
    }
    
    fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        isListening = false
    }
    
    fun isListening(): Boolean = isListening
    
    fun release() {
        isListening = false
        Log.d(TAG, "VoiceInputService released")
    }
}
