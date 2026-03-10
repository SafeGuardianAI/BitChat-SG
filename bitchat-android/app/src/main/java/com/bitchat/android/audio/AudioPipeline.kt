package com.bitchat.android.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.max

/**
 * Unified Audio Pipeline
 *
 * Coordinates VAD, Keyword Recognition, and (optionally) full ASR into a
 * single efficient pipeline:
 *
 * 1. **Microphone** → reads PCM frames at 16 kHz.
 * 2. **VAD** → classifies each frame as speech / silence.
 * 3. When speech is detected → frames are forwarded to **KeywordRecognitionService**.
 * 4. If a keyword is found → emits [PipelineEvent.KeywordDetected] immediately.
 * 5. In FULL_PIPELINE mode, accumulated speech is also sent to ASR.
 * 6. Distress alerts from VAD are surfaced as [PipelineEvent.DistressAlert].
 *
 * Three power profiles are available via [PipelineMode].
 */
class AudioPipeline(private val context: Context) {

    companion object {
        private const val TAG = "AudioPipeline"
    }

    // ── Child services ─────────────────────────────────────────────────

    val vadService = VADService()
    val keywordService = KeywordRecognitionService(context)

    // ── Public events ──────────────────────────────────────────────────

    private val _pipelineEvents =
        MutableSharedFlow<PipelineEvent>(replay = 0, extraBufferCapacity = 20)
    val pipelineEvents: SharedFlow<PipelineEvent> = _pipelineEvents.asSharedFlow()

    // ── State ──────────────────────────────────────────────────────────

    private var mode: PipelineMode = PipelineMode.VAD_KEYWORD

    @Volatile
    private var isRunning = false
    private var pipelineJob: Job? = null
    private var eventForwardJob: Job? = null
    private var audioRecord: AudioRecord? = null

    /** Pipeline operating modes. */
    enum class PipelineMode {
        /** Only listen for keywords via heuristic (lowest power). */
        KEYWORD_ONLY,
        /** VAD + keyword recognition (low power). */
        VAD_KEYWORD,
        /** VAD + keyword + full ASR transcription (highest power). */
        FULL_PIPELINE
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Start the pipeline in the given [mode].
     *
     * The caller must hold [Manifest.permission.RECORD_AUDIO].
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(mode: PipelineMode, scope: CoroutineScope) {
        if (isRunning) {
            Log.w(TAG, "Pipeline already running")
            return
        }
        this.mode = mode
        isRunning = true

        // Forward VAD speech events → pipeline events
        eventForwardJob = scope.launch {
            launch {
                vadService.speechEvents.collect { event ->
                    when (event) {
                        is SpeechEvent.SpeechStart ->
                            _pipelineEvents.tryEmit(PipelineEvent.SpeechActivity(true))

                        is SpeechEvent.SpeechEnd ->
                            _pipelineEvents.tryEmit(PipelineEvent.SpeechActivity(false))

                        is SpeechEvent.DistressDetected ->
                            _pipelineEvents.tryEmit(PipelineEvent.DistressAlert(event.confidence))

                        is SpeechEvent.AudioLevel -> {
                            // Optionally could expose raw levels
                        }
                    }
                }
            }
            launch {
                keywordService.detections.collect { detection ->
                    _pipelineEvents.tryEmit(PipelineEvent.KeywordDetected(detection))
                }
            }
        }

        when (mode) {
            PipelineMode.KEYWORD_ONLY -> startKeywordOnly(scope)
            PipelineMode.VAD_KEYWORD -> startVadKeyword(scope)
            PipelineMode.FULL_PIPELINE -> startFullPipeline(scope)
        }
    }

    /** Stop the pipeline and release resources. */
    fun stop() {
        isRunning = false
        pipelineJob?.cancel()
        pipelineJob = null
        eventForwardJob?.cancel()
        eventForwardJob = null
        vadService.stop()
        keywordService.stopListening()
        releaseAudioRecord()
    }

    /** Change the operating mode. Restarts the pipeline if already running. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun setMode(newMode: PipelineMode, scope: CoroutineScope) {
        if (newMode == mode && isRunning) return
        if (isRunning) {
            stop()
        }
        start(newMode, scope)
    }

    // ── Mode implementations ───────────────────────────────────────────

    /**
     * KEYWORD_ONLY: open the mic, compute RMS on each frame, and when
     * a loud segment ends run keyword heuristic.  VAD state machine is
     * still used internally but at reduced overhead (no adaptive threshold).
     */
    private fun startKeywordOnly(scope: CoroutineScope) {
        pipelineJob = scope.launch(Dispatchers.IO) {
            val record = createAudioRecord() ?: return@launch
            audioRecord = record
            record.startRecording()

            val frame = ShortArray(VADService.FRAME_SIZE)
            val segmentBuffer = mutableListOf<ShortArray>()
            var inSpeech = false
            var silenceFrames = 0
            val silenceFrameLimit =
                (VADService.SILENCE_MIN_DURATION_MS / VADService.FRAME_SIZE_MS).toInt()

            try {
                while (isActive && isRunning) {
                    val read = record.read(frame, 0, VADService.FRAME_SIZE)
                    if (read <= 0) continue
                    val usable = if (read == VADService.FRAME_SIZE) frame.copyOf() else frame.copyOf(read)
                    val rms = AudioFeatures.rms(usable)
                    val loud = rms > VADService.SILENCE_THRESHOLD_DEFAULT

                    if (loud) {
                        inSpeech = true
                        silenceFrames = 0
                        segmentBuffer.add(usable)
                    } else if (inSpeech) {
                        silenceFrames++
                        if (silenceFrames >= silenceFrameLimit) {
                            // End of speech – analyse
                            val merged = mergeBuffers(segmentBuffer)
                            segmentBuffer.clear()
                            inSpeech = false
                            keywordService.processAudioSegment(merged)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // expected
            } finally {
                releaseAudioRecord()
            }
        }
    }

    /**
     * VAD_KEYWORD: full VAD with adaptive threshold; speech segments are
     * analysed for keywords.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startVadKeyword(scope: CoroutineScope) {
        keywordService.startListening(scope, vadService)

        pipelineJob = scope.launch(Dispatchers.IO) {
            val record = createAudioRecord() ?: return@launch
            audioRecord = record
            record.startRecording()

            val frame = ShortArray(VADService.FRAME_SIZE)
            try {
                while (isActive && isRunning) {
                    val read = record.read(frame, 0, VADService.FRAME_SIZE)
                    if (read <= 0) continue
                    val usable = if (read == VADService.FRAME_SIZE) frame.copyOf() else frame.copyOf(read)
                    vadService.processFrame(usable)
                    keywordService.feedAudio(usable)
                }
            } catch (_: CancellationException) {
                // expected
            } finally {
                releaseAudioRecord()
            }
        }
    }

    /**
     * FULL_PIPELINE: VAD + keywords + ASR transcription on every speech
     * segment.  The ASR transcriber must be set on [keywordService] before
     * starting this mode.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startFullPipeline(scope: CoroutineScope) {
        // Reuse VAD_KEYWORD path — the only difference is that an
        // asrTranscriber is expected to be set on keywordService.
        startVadKeyword(scope)

        // Additionally listen for complete speech segments and emit
        // transcription events.
        scope.launch {
            vadService.speechEvents.collect { event ->
                if (event is SpeechEvent.SpeechEnd && keywordService.asrTranscriber != null) {
                    // Transcription is already handled inside
                    // keywordService.processAudioSegment; any additional
                    // full-text transcription could be done here.
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            VADService.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Cannot query AudioRecord min buffer size")
            return null
        }
        val bufSize = max(minBuf, VADService.FRAME_SIZE * 2 * 2)
        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                VADService.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                Log.e(TAG, "AudioRecord failed to initialise")
                null
            } else rec
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            null
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        audioRecord?.release()
        audioRecord = null
    }

    private fun mergeBuffers(buffers: List<ShortArray>): ShortArray {
        val total = buffers.sumOf { it.size }
        val merged = ShortArray(total)
        var offset = 0
        for (buf in buffers) {
            buf.copyInto(merged, offset)
            offset += buf.size
        }
        return merged
    }
}

// ── Pipeline events ────────────────────────────────────────────────────

sealed class PipelineEvent {
    data class KeywordDetected(val detection: KeywordDetection) : PipelineEvent()
    data class Transcription(val text: String, val confidence: Float) : PipelineEvent()
    data class SpeechActivity(val isSpeaking: Boolean) : PipelineEvent()
    data class DistressAlert(val confidence: Float) : PipelineEvent()
}
