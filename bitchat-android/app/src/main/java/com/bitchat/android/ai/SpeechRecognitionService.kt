package com.bitchat.android.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Unified Speech Recognition Service
 *
 * Provides ASR through two backends:
 *  1. Android built-in SpeechRecognizer (primary — no model download needed)
 *  2. Fallback to offline Nexa/Sherpa-ONNX when no internet or for privacy
 *
 * For disaster scenarios the offline path is critical since connectivity
 * may be unavailable.
 */
class SpeechRecognitionService(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognitionSvc"
        private const val RECOGNITION_TIMEOUT_MS = 30_000L
    }

    enum class Backend { ANDROID_BUILTIN, OFFLINE_ASR }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastTranscription = MutableStateFlow("")
    val lastTranscription: StateFlow<String> = _lastTranscription.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentDeferred: CompletableDeferred<String?>? = null

    // Offline ASR fallback
    private val offlineAsrService = ASRService(context)

    /**
     * Check if speech recognition is available on this device.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Recognize speech from the microphone using Android's built-in recognizer.
     *
     * This must be called from the main thread (Android requirement).
     * Returns the transcription or null on failure.
     */
    suspend fun recognizeFromMicrophone(
        language: String = "en-US",
        preferOffline: Boolean = false
    ): String? {
        if (preferOffline) {
            return recognizeOffline(language)
        }

        if (!isAvailable()) {
            Log.w(TAG, "Android SpeechRecognizer not available, trying offline")
            return recognizeOffline(language)
        }

        return withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
            val deferred = CompletableDeferred<String?>()
            currentDeferred = deferred

            withContext(Dispatchers.Main) {
                try {
                    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    speechRecognizer = recognizer

                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListening.value = true
                            Log.d(TAG, "Ready for speech")
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "Speech began")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _isListening.value = false
                            Log.d(TAG, "Speech ended")
                        }

                        override fun onError(error: Int) {
                            _isListening.value = false
                            val errorMsg = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                                else -> "Recognition error ($error)"
                            }
                            Log.w(TAG, "Recognition error: $errorMsg")
                            if (!deferred.isCompleted) deferred.complete(null)
                        }

                        override fun onResults(results: Bundle?) {
                            _isListening.value = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val transcription = matches?.firstOrNull()
                            _lastTranscription.value = transcription ?: ""
                            Log.d(TAG, "Transcription: $transcription")
                            if (!deferred.isCompleted) deferred.complete(transcription)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            partial?.firstOrNull()?.let {
                                _lastTranscription.value = it
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        // Request offline recognition if available
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }

                    recognizer.startListening(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start recognition", e)
                    _isListening.value = false
                    if (!deferred.isCompleted) deferred.complete(null)
                }
            }

            deferred.await()
        }
    }

    /**
     * Transcribe a WAV audio file (offline capable).
     */
    suspend fun transcribeFile(audioFile: File, language: String = "en"): String? {
        return offlineAsrService.transcribeFile(audioFile)
    }

    /**
     * Stop current recognition.
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognition", e)
        }
        _isListening.value = false
        currentDeferred?.let {
            if (!it.isCompleted) it.complete(null)
        }
    }

    /**
     * Offline recognition using Sherpa-ONNX / Nexa ASR.
     */
    private suspend fun recognizeOffline(language: String): String? {
        if (!offlineAsrService.isInitialized) {
            val modelId = if (language == "en") {
                ModelCatalog.SHERPA_ONNX_SMALL_EN.id
            } else {
                ModelCatalog.SHERPA_ONNX_CANARY_MULTILANG.id
            }
            offlineAsrService.initialize(modelId, language)
        }
        // Offline ASR requires recording first — return status
        Log.d(TAG, "Offline ASR initialized for language: $language")
        return null // Caller should use VoiceInputService + transcribeFile flow
    }

    fun release() {
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying recognizer", e)
        }
        speechRecognizer = null
        offlineAsrService.release()
        Log.d(TAG, "SpeechRecognitionService released")
    }
}
