package com.bitchat.android.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Keyword Recognition Service
 *
 * Listens for emergency keywords without running full ASR.
 * Uses simple energy-envelope matching combined with VAD speech segments.
 * When a speech segment is detected it is analysed for keyword patterns;
 * if a full ASR engine is available the segment is forwarded for
 * transcription and the result is matched against the keyword list.
 *
 * Ultra-low power compared to continuous speech recognition.
 */
class KeywordRecognitionService(private val context: Context) {

    companion object {
        private const val TAG = "KeywordRecognition"
        val DEFAULT_KEYWORDS = listOf("help", "emergency", "mayday", "sos", "fire", "danger")
        const val DETECTION_COOLDOWN_MS = 3000L
        private const val PREFS_NAME = "keyword_recognition_prefs"
        private const val KEY_CUSTOM_KEYWORDS = "custom_keywords"
    }

    // ── Public events ──────────────────────────────────────────────────

    private val _detections =
        MutableSharedFlow<KeywordDetection>(replay = 0, extraBufferCapacity = 10)
    val detections: SharedFlow<KeywordDetection> = _detections.asSharedFlow()

    // ── Internal state ─────────────────────────────────────────────────

    private var keywords: MutableList<String> = DEFAULT_KEYWORDS.toMutableList()
    private var lastDetectionTime = 0L
    private var listeningJob: Job? = null
    @Volatile
    private var isListening = false

    // Buffer to accumulate speech audio between SpeechStart and SpeechEnd
    private val speechBuffer = mutableListOf<ShortArray>()
    private var bufferingActive = false

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Optional external ASR callback for full-pipeline mode.
    // When set, speech segments are forwarded here for transcription.
    var asrTranscriber: (suspend (ShortArray) -> String?)? = null

    init {
        loadCustomKeywords()
    }

    // ── Keyword configuration ──────────────────────────────────────────

    /** Replace the active keyword list. */
    fun setKeywords(newKeywords: List<String>) {
        keywords = newKeywords.map { it.lowercase().trim() }.toMutableList()
        saveCustomKeywords()
    }

    fun addKeyword(keyword: String) {
        val normalised = keyword.lowercase().trim()
        if (normalised.isNotEmpty() && normalised !in keywords) {
            keywords.add(normalised)
            saveCustomKeywords()
        }
    }

    fun removeKeyword(keyword: String) {
        keywords.remove(keyword.lowercase().trim())
        saveCustomKeywords()
    }

    fun getKeywords(): List<String> = keywords.toList()

    // ── Processing ─────────────────────────────────────────────────────

    /**
     * Analyse a speech audio segment for keyword content.
     *
     * Strategy:
     * 1. If an [asrTranscriber] is provided, transcribe the segment and
     *    match against the keyword list.
     * 2. Otherwise fall back to a simple energy-heuristic that flags
     *    short, high-energy bursts as potential keyword utterances
     *    (confidence is lower without ASR).
     */
    suspend fun processAudioSegment(audio: ShortArray): KeywordDetection? {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < DETECTION_COOLDOWN_MS) return null

        // Attempt ASR-based detection first
        val transcription = try {
            asrTranscriber?.invoke(audio)
        } catch (e: Exception) {
            Log.w(TAG, "ASR transcription failed, falling back to heuristic", e)
            null
        }

        if (transcription != null) {
            val lower = transcription.lowercase()
            for (kw in keywords) {
                if (lower.contains(kw)) {
                    val detection = KeywordDetection(
                        keyword = kw,
                        confidence = 0.85f,
                        timestamp = now,
                        audioDurationMs = audio.size * 1000L / VADService.SAMPLE_RATE
                    )
                    lastDetectionTime = now
                    _detections.tryEmit(detection)
                    return detection
                }
            }
        }

        // Heuristic fallback: short high-energy segments may be a shout keyword
        val detection = heuristicKeywordCheck(audio, now)
        if (detection != null) {
            lastDetectionTime = now
            _detections.tryEmit(detection)
        }
        return detection
    }

    // ── Continuous listening (integrates with VAD) ─────────────────────

    /**
     * Start continuous keyword listening driven by a [VADService].
     *
     * Speech segments delimited by [SpeechEvent.SpeechStart] /
     * [SpeechEvent.SpeechEnd] are buffered and then analysed for keywords.
     */
    fun startListening(scope: CoroutineScope, vadService: VADService) {
        if (isListening) return
        isListening = true

        listeningJob = scope.launch {
            vadService.speechEvents.collect { event ->
                when (event) {
                    is SpeechEvent.SpeechStart -> {
                        speechBuffer.clear()
                        bufferingActive = true
                    }

                    is SpeechEvent.SpeechEnd -> {
                        bufferingActive = false
                        if (speechBuffer.isNotEmpty()) {
                            val merged = mergeBuffers(speechBuffer)
                            speechBuffer.clear()
                            processAudioSegment(merged)
                        }
                    }

                    is SpeechEvent.AudioLevel -> {
                        // We don't buffer raw levels here; the VAD recording
                        // loop drives processFrame which already feeds us.
                    }

                    is SpeechEvent.DistressDetected -> {
                        // Distress events are handled by the pipeline layer.
                    }
                }
            }
        }
    }

    /** Provide raw audio frames while recording is ongoing (called from pipeline). */
    fun feedAudio(frame: ShortArray) {
        if (bufferingActive) {
            speechBuffer.add(frame.copyOf())
        }
    }

    fun stopListening() {
        isListening = false
        listeningJob?.cancel()
        listeningJob = null
        speechBuffer.clear()
        bufferingActive = false
    }

    // ── Private helpers ────────────────────────────────────────────────

    /**
     * Very simple heuristic: a short (< 2 s), loud segment with moderate
     * ZCR is likely a shouted keyword.  Confidence is low (0.35) because
     * we cannot actually recognise words without ASR.
     */
    private fun heuristicKeywordCheck(audio: ShortArray, now: Long): KeywordDetection? {
        val durationMs = audio.size * 1000L / VADService.SAMPLE_RATE
        if (durationMs > 2000 || durationMs < 100) return null

        val rms = AudioFeatures.rms(audio)
        val zcr = AudioFeatures.zeroCrossingRate(audio)

        // Expect a loud, voiced burst
        if (rms < 2000.0) return null
        if (zcr < 0.01 || zcr > 0.45) return null

        return KeywordDetection(
            keyword = "unknown",
            confidence = 0.35f,
            timestamp = now,
            audioDurationMs = durationMs
        )
    }

    private fun mergeBuffers(buffers: List<ShortArray>): ShortArray {
        val totalSize = buffers.sumOf { it.size }
        val merged = ShortArray(totalSize)
        var offset = 0
        for (buf in buffers) {
            buf.copyInto(merged, offset)
            offset += buf.size
        }
        return merged
    }

    private fun saveCustomKeywords() {
        prefs.edit().putStringSet(KEY_CUSTOM_KEYWORDS, keywords.toSet()).apply()
    }

    private fun loadCustomKeywords() {
        val saved = prefs.getStringSet(KEY_CUSTOM_KEYWORDS, null)
        if (saved != null && saved.isNotEmpty()) {
            keywords = saved.toMutableList()
        }
    }
}

// ── Data classes ────────────────────────────────────────────────────────

data class KeywordDetection(
    val keyword: String,
    val confidence: Float,
    val timestamp: Long,
    val audioDurationMs: Long
)
