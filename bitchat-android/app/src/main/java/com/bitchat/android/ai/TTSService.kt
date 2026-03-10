package com.bitchat.android.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

/**
 * Text-to-Speech Service
 * 
 * Provides TTS functionality using Android's built-in TextToSpeech engine
 */
class TTSService(private val context: Context) {

    companion object {
        private const val TAG = "TTSService"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isEnabled = true

    // TTS state
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    // TTS settings
    private var speechRate = 1.0f
    private var pitch = 1.0f
    private var language = Locale.ENGLISH

    /**
     * Initialize TTS engine
     */
    fun initialize(): Boolean {
        if (isInitialized) return true

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(language)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Language not supported, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                }
                
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(pitch)
                
                // Set up utterance progress listener
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        Log.d(TAG, "TTS started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        Log.d(TAG, "TTS completed: $utteranceId")
                    }

                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        Log.e(TAG, "TTS error: $utteranceId")
                    }
                })

                isInitialized = true
                _isAvailable.value = true
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed")
                _isAvailable.value = false
            }
        }

        return true
    }

    /**
     * Speak text
     * 
     * @param text Text to speak
     * @param utteranceId Optional utterance ID for tracking
     */
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()) {
        if (!isEnabled || !isInitialized) {
            Log.w(TAG, "TTS not available or disabled")
            return
        }

        if (text.isBlank()) {
            Log.w(TAG, "Empty text provided for TTS")
            return
        }

        try {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "Failed to speak text")
                _isSpeaking.value = false
            } else {
                Log.d(TAG, "Speaking: $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text", e)
            _isSpeaking.value = false
        }
    }

    /**
     * Stop current speech
     */
    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        Log.d(TAG, "TTS stopped")
    }

    /**
     * Set speech rate
     * 
     * @param rate Speech rate (0.1f to 3.0f)
     */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.1f, 3.0f)
        tts?.setSpeechRate(speechRate)
        Log.d(TAG, "Speech rate set to: $speechRate")
    }

    /**
     * Set pitch
     * 
     * @param pitch Pitch (0.1f to 2.0f)
     */
    fun setPitch(pitch: Float) {
        this.pitch = pitch.coerceIn(0.1f, 2.0f)
        tts?.setPitch(this.pitch)
        Log.d(TAG, "Pitch set to: $pitch")
    }

    /**
     * Set language
     * 
     * @param locale Language locale
     */
    fun setLanguage(locale: Locale) {
        language = locale
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not supported: $locale")
        } else {
            Log.d(TAG, "Language set to: $locale")
        }
    }

    /**
     * Enable/disable TTS
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            stop()
        }
        Log.d(TAG, "TTS enabled: $enabled")
    }

    /**
     * Check if TTS is available
     */
    fun isAvailable(): Boolean {
        return isInitialized && _isAvailable.value
    }

    /**
     * Check if currently speaking
     */
    fun isSpeaking(): Boolean {
        return _isSpeaking.value
    }

    /**
     * Get available languages
     */
    fun getAvailableLanguages(): List<Locale> {
        return if (isInitialized) {
            tts?.availableLanguages?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Release TTS resources
     */
    fun release() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            _isAvailable.value = false
            _isSpeaking.value = false
            Log.i(TAG, "TTS released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TTS", e)
        }
    }
}








