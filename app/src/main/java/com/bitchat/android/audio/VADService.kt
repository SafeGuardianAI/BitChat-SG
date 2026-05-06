package com.bitchat.android.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.max
import kotlin.math.min

/**
 * Voice Activity Detection Service
 *
 * Uses energy-based detection with an adaptive noise-floor threshold.
 * Runs entirely on-device with minimal battery impact.
 *
 * Usage:
 * ```
 * val vad = VADService()
 * vad.start(viewModelScope)          // starts recording + detection
 * vad.speechEvents.collect { event ->
 *     when (event) { ... }
 * }
 * vad.stop()
 * ```
 */
class VADService {

    companion object {
        private const val TAG = "VADService"
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE_MS = 30 // 30 ms frames
        const val FRAME_SIZE = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 480 samples
        const val SILENCE_THRESHOLD_DEFAULT = 500.0 // RMS energy threshold
        const val SPEECH_MIN_DURATION_MS = 300L // min speech duration to trigger
        const val SILENCE_MIN_DURATION_MS = 1000L // min silence to end speech
        const val ADAPTIVE_ALPHA = 0.02 // learning rate for noise floor
        private const val DISTRESS_ENERGY_FACTOR = 4.0 // multiplier above threshold
        private const val DISTRESS_ZCR_LOW = 0.02 // low ZCR ⇒ voiced shout
        private const val DISTRESS_ZCR_HIGH = 0.35
    }

    // ── Public state & events ──────────────────────────────────────────

    private val _vadState = MutableStateFlow<VADState>(VADState.Idle)
    val vadState: StateFlow<VADState> = _vadState.asStateFlow()

    private val _speechEvents =
        MutableSharedFlow<SpeechEvent>(replay = 0, extraBufferCapacity = 10)
    val speechEvents: SharedFlow<SpeechEvent> = _speechEvents.asSharedFlow()

    // ── Internal state ─────────────────────────────────────────────────

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRunning = false
    private var recordingJob: Job? = null

    var silenceThreshold = SILENCE_THRESHOLD_DEFAULT
        private set
    private var adaptiveNoiseFloor = 0.0
    private var noiseFloorInitialised = false

    // Timing trackers
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var currentlySpeaking = false

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Start recording from the microphone and processing frames.
     * Requires [Manifest.permission.RECORD_AUDIO].
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope) {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            _vadState.value = VADState.Error("Unable to query min buffer size")
            return
        }

        val bufferSize = max(minBuf, FRAME_SIZE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            _vadState.value = VADState.Error("Microphone permission not granted")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _vadState.value = VADState.Error("AudioRecord failed to initialise")
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRunning = true
        audioRecord?.startRecording()
        _vadState.value = VADState.Listening

        recordingJob = scope.launch(Dispatchers.IO) {
            val frame = ShortArray(FRAME_SIZE)
            try {
                while (isActive && isRunning) {
                    val read = audioRecord?.read(frame, 0, FRAME_SIZE) ?: -1
                    if (read > 0) {
                        val usable = if (read == FRAME_SIZE) frame else frame.copyOf(read)
                        processFrame(usable)
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop error", e)
                _vadState.value = VADState.Error(e.message ?: "Unknown error")
            } finally {
                releaseAudioRecord()
            }
        }
    }

    /** Stop VAD and release the microphone. */
    fun stop() {
        isRunning = false
        recordingJob?.cancel()
        recordingJob = null
        releaseAudioRecord()
        if (currentlySpeaking) {
            finaliseSpeechEnd(System.currentTimeMillis())
        }
        _vadState.value = VADState.Idle
    }

    // ── Frame processing ───────────────────────────────────────────────

    /**
     * Analyse a single audio frame.
     *
     * @return `true` if the frame is classified as speech.
     */
    fun processFrame(frame: ShortArray): Boolean {
        val energy = calculateRMS(frame)
        val zcr = calculateZeroCrossingRate(frame)
        val now = System.currentTimeMillis()

        updateThreshold(energy, currentlySpeaking)

        val isSpeech = energy > silenceThreshold

        // Emit audio level
        _speechEvents.tryEmit(SpeechEvent.AudioLevel(energy, isSpeech))

        if (isSpeech) {
            if (!currentlySpeaking) {
                // Potential speech start – wait for min duration before confirming
                if (speechStartTime == 0L) {
                    speechStartTime = now
                }
                if (now - speechStartTime >= SPEECH_MIN_DURATION_MS) {
                    currentlySpeaking = true
                    _vadState.value = VADState.SpeechDetected
                    _speechEvents.tryEmit(SpeechEvent.SpeechStart(speechStartTime))
                }
            }
            lastSpeechTime = now

            // Distress check
            if (detectDistress(energy, zcr)) {
                val confidence = min(
                    1.0f,
                    ((energy / silenceThreshold - DISTRESS_ENERGY_FACTOR) / DISTRESS_ENERGY_FACTOR).toFloat()
                ).coerceIn(0.3f, 1.0f)
                _speechEvents.tryEmit(SpeechEvent.DistressDetected(now, confidence))
            }
        } else {
            // Silence detected
            if (currentlySpeaking && now - lastSpeechTime >= SILENCE_MIN_DURATION_MS) {
                finaliseSpeechEnd(now)
            }
            if (!currentlySpeaking) {
                speechStartTime = 0L
                _vadState.value = VADState.Listening
            }
        }

        return isSpeech
    }

    // ── Signal processing helpers ──────────────────────────────────────

    /** RMS (root-mean-square) energy of a PCM frame. */
    fun calculateRMS(frame: ShortArray): Double = AudioFeatures.rms(frame)

    /** Zero-crossing rate of the frame (0.0 – 1.0). */
    fun calculateZeroCrossingRate(frame: ShortArray): Double =
        AudioFeatures.zeroCrossingRate(frame)

    /**
     * Adaptive threshold: slowly track the noise floor so that the
     * threshold adjusts to ambient conditions.
     */
    fun updateThreshold(energy: Double, isSpeech: Boolean) {
        if (!isSpeech) {
            if (!noiseFloorInitialised) {
                adaptiveNoiseFloor = energy
                noiseFloorInitialised = true
            } else {
                adaptiveNoiseFloor =
                    adaptiveNoiseFloor * (1.0 - ADAPTIVE_ALPHA) + energy * ADAPTIVE_ALPHA
            }
            // Threshold = 3× the noise floor, but never below default minimum
            silenceThreshold = max(adaptiveNoiseFloor * 3.0, SILENCE_THRESHOLD_DEFAULT * 0.5)
        }
    }

    /**
     * Heuristic distress detection.
     *
     * A distress vocalisation (scream / shout) tends to have very high energy
     * combined with a zero-crossing rate that is either very low (sustained
     * voiced shout) or moderately high (scream).
     */
    fun detectDistress(energy: Double, zeroCrossingRate: Double): Boolean {
        if (energy < silenceThreshold * DISTRESS_ENERGY_FACTOR) return false
        return zeroCrossingRate < DISTRESS_ZCR_LOW || zeroCrossingRate > DISTRESS_ZCR_HIGH
    }

    // ── Private helpers ────────────────────────────────────────────────

    private fun finaliseSpeechEnd(now: Long) {
        val duration = now - speechStartTime
        _speechEvents.tryEmit(SpeechEvent.SpeechEnd(now, duration))
        _vadState.value = VADState.SilenceDetected
        currentlySpeaking = false
        speechStartTime = 0L
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped
        }
        audioRecord?.release()
        audioRecord = null
    }
}

// ── State & Event types ────────────────────────────────────────────────

sealed class VADState {
    object Idle : VADState()
    object Listening : VADState()
    object SpeechDetected : VADState()
    object SilenceDetected : VADState()
    data class Error(val message: String) : VADState()
}

sealed class SpeechEvent {
    data class SpeechStart(val timestamp: Long) : SpeechEvent()
    data class SpeechEnd(val timestamp: Long, val durationMs: Long) : SpeechEvent()
    data class DistressDetected(val timestamp: Long, val confidence: Float) : SpeechEvent()
    data class AudioLevel(val rms: Double, val isSpeech: Boolean) : SpeechEvent()
}
