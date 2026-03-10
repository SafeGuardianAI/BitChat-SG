package com.bitchat.android.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ASR engine using Nexa SDK.
 * Loads Parakeet model via Nexa and exposes transcribeFile.
 */
class NexaAsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "NexaAsrEngine"
    }

    private val modelManager = AsrModelManager(context)
    private var modelPath: String? = null  // Resolved path; replace with AsrWrapper when Nexa API available

    /**
     * Load the ASR model. Call before transcribeFile.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (!modelManager.isModelPresent()) {
            Log.e(TAG, "ASR model not present: ${modelManager.getStatus()}")
            return@withContext false
        }

        val path = modelManager.resolveModelPath()
        if (path == null) {
            Log.e(TAG, "Could not resolve model path")
            return@withContext false
        }

        try {
            // Nexa SDK ASR: when AsrWrapper/parakeet API is available, use it here.
            Log.i(TAG, "ASR model path resolved: $path")
            modelPath = path
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ASR model", e)
            false
        }
    }

    /**
     * Transcribe audio file to text.
     * @return Transcribed text, or error message if failed.
     */
    suspend fun transcribeFile(file: File): String = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext "Error: audio file not found"
        }

        if (!modelManager.isModelPresent()) {
            return@withContext "Error: ASR model not found. " + modelManager.getStatus()
        }

        try {
            // When Nexa ASR API is available:
            // val result = asrWrapper.transcribe(file.absolutePath)
            // return result ?: "No transcription"
            val loaded = modelPath != null || loadModel()
            if (!loaded) return@withContext "Error: ASR model failed to load"

            // Placeholder: Nexa SDK may expose transcribe(filePath) or similar.
            // For now return a clear status so /asrstop works end-to-end.
            val sizeSec = file.length() / (16000 * 2)  // 16kHz, 16-bit mono
            "ASR ready. Audio: ${file.name} (~${sizeSec}s). Full transcription requires Nexa ASR API."
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            "Error: ${e.message ?: "transcription failed"}"
        }
    }

    fun isLoaded(): Boolean = modelPath != null

    fun release() {
        modelPath = null
    }
}
