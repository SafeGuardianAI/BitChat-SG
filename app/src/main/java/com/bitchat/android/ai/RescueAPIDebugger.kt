package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RescueAPIDebugger - debugging utilities for Rescue API
 */
class RescueAPIDebugger private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "RescueAPIDebugger"
        private var instance: RescueAPIDebugger? = null
        
        fun getInstance(context: Context): RescueAPIDebugger {
            if (instance == null) {
                instance = RescueAPIDebugger(context.applicationContext)
            }
            return instance!!
        }
    }
    
    private val results = mutableListOf<String>()
    
    suspend fun testAPIConnection(rescueAPI: RescueAPIService): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Testing API connection")
        addResult("Testing API connection...")
        val result = rescueAPI.testConnection()
        addResult("Connection test: ${if (result) "SUCCESS" else "FAILED"}")
        result
    }
    
    suspend fun testVictimSubmission(rescueAPI: RescueAPIService): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Testing victim submission")
        addResult("Testing victim submission...")
        
        val testVictim = VictimInfo(
            emergency_status = "test",
            location = LocationInfo(0.0, 0.0, "Test location", null),
            personal_info = PersonalInfo("Test", 30, "Unknown", "en", null)
        )
        
        val result = rescueAPI.postVictim(testVictim)
        addResult("Submission result: ${result ?: "FAILED"}")
        result ?: "FAILED"
    }
    
    fun testTTSPlayback(aiService: AIService, text: String) {
        Log.d(TAG, "Testing TTS: $text")
        addResult("TTS test: $text")
        kotlinx.coroutines.runBlocking {
            aiService.speak(text)
        }
        addResult("TTS playback completed")
    }
    
    suspend fun runFullDiagnostics(
        rescueAPI: RescueAPIService,
        aiService: AIService,
        preferences: AIPreferences
    ): List<String> = withContext(Dispatchers.IO) {
        results.clear()
        addResult("=== Full Diagnostics ===")
        addResult("AI Enabled: ${preferences.aiEnabled}")
        addResult("TTS Enabled: ${preferences.ttsEnabled}")
        addResult("ASR Enabled: ${preferences.asrEnabled}")
        addResult("RAG Enabled: ${preferences.ragEnabled}")
        addResult("Model: ${preferences.selectedLLMModel}")
        addResult("")
        
        testAPIConnection(rescueAPI)
        addResult("")
        
        addResult("=== Diagnostics Complete ===")
        results.toList()
    }
    
    fun getResults(): List<String> = results.toList()
    
    fun clearResults() {
        results.clear()
    }
    
    private fun addResult(message: String) {
        results.add(message)
        Log.d(TAG, message)
    }
}
