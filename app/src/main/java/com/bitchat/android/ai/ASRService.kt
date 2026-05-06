package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import com.bitchat.android.features.voice.M4ADecoder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline ASR via Sherpa-ONNX Whisper Tiny (multilingual).
 *
 * Model bundled in assets at `models/sherpa-onnx-whisper-tiny/` — no download required.
 * Supports 99 languages; call [initialize] or [setLanguage] to switch. Rebuilds the
 * recognizer config on language change but keeps the same model weights in memory.
 *
 * [transcribeFile] accepts both WAV (from AsrAudioRecorder) and M4A/other formats
 * (from AIChatService audio-note flow).
 */
class ASRService(private val context: Context) {

    companion object {
        private const val TAG = "ASRService"
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80

        private const val ASSET_DIR = "models/sherpa-onnx-whisper-tiny"
        private const val ENCODER   = "$ASSET_DIR/tiny-encoder.int8.onnx"
        private const val DECODER   = "$ASSET_DIR/tiny-decoder.int8.onnx"
        private const val TOKENS    = "$ASSET_DIR/tiny-tokens.txt"

        fun isModelAvailable(context: Context): Boolean = try {
            context.assets.open(TOKENS).use { true }
        } catch (_: Exception) { false }
    }

    // Separate mutexes so initialization and decoding can't deadlock each other.
    private val initMutex   = Mutex()
    private val decodeMutex = Mutex()

    private var recognizer: OfflineRecognizer? = null
    var isInitialized: Boolean = false
        private set
    private var currentModelId: String? = null
    private var currentLanguage: String = "en"

    /**
     * Build (or rebuild) the recognizer for [language].
     * No-ops if already initialized with the same model+language.
     */
    suspend fun initialize(
        modelId: String = AIModelCatalog.SHERPA_ONNX_WHISPER_TINY.id,
        language: String = "en",
    ): Boolean = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isInitialized && currentLanguage == language && currentModelId == modelId) {
                return@withLock true
            }
            try {
                recognizer?.release()
                recognizer = OfflineRecognizer(context.assets, buildConfig(language))
                currentModelId = modelId
                currentLanguage = language
                isInitialized = true
                Log.i(TAG, "ASR ready — model=$modelId lang=$language")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize Sherpa-ONNX", e)
                recognizer = null
                isInitialized = false
                false
            }
        }
    }

    private fun buildConfig(language: String): OfflineRecognizerConfig {
        val whisper = OfflineWhisperModelConfig(
            /* encoder      = */ ENCODER,
            /* decoder      = */ DECODER,
            /* language     = */ language,
            /* task         = */ "transcribe",
            /* tailPaddings = */ -1
        )
        val model = OfflineModelConfig().apply {
            this.whisper    = whisper
            this.tokens     = TOKENS
            this.numThreads = 2
            this.debug      = false
            this.provider   = "cpu"
        }
        return OfflineRecognizerConfig(
            featConfig  = FeatureConfig(SAMPLE_RATE, FEATURE_DIM, 0f),
            modelConfig = model
        )
    }

    /** Transcribe 16-bit PCM shorts (16 kHz mono) from AsrAudioRecorder. */
    suspend fun transcribe(
        audioData: ShortArray,
        language: String = currentLanguage,
    ): String? = withContext(Dispatchers.IO) {
        if (audioData.size < SAMPLE_RATE / 10) {
            Log.w(TAG, "Audio too short (<100 ms), skipping")
            return@withContext null
        }
        if (!initialize(language = language)) return@withContext null
        val floats = FloatArray(audioData.size) { audioData[it] / 32768.0f }
        decodeSamples(floats)
    }

    /**
     * Transcribe an audio file.
     * WAV (from AsrAudioRecorder) → readWavPcm16 path (fast, no JNI decoder).
     * M4A / other       → M4ADecoder path (MediaExtractor, keeps AIChatService working).
     */
    suspend fun transcribeFile(
        file: File,
        language: String = currentLanguage,
    ): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            Log.e(TAG, "Audio file missing: ${file.absolutePath}")
            return@withContext null
        }
        if (!initialize(language = language)) return@withContext null
        val samples = when {
            file.extension.equals("wav", ignoreCase = true) -> readWavPcm16(file)
            else -> M4ADecoder.decodeToFloat(file.absolutePath, SAMPLE_RATE)
        } ?: return@withContext null
        val text = decodeSamples(samples) ?: return@withContext null
        Log.d(TAG, "Transcribed ${file.name} [$language]: \"$text\"")
        text.ifBlank { null }
    }

    private suspend fun decodeSamples(samples: FloatArray): String? = decodeMutex.withLock {
        val rec = recognizer ?: run {
            Log.w(TAG, "Recognizer not initialized")
            return@withLock null
        }
        try {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim().ifBlank { null }
            } finally {
                stream.release()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Decode failed", e)
            null
        }
    }

    /** Switch output language. Rebuilds the recognizer config (weights stay loaded). */
    suspend fun setLanguage(language: String): Boolean =
        initialize(currentModelId ?: AIModelCatalog.SHERPA_ONNX_WHISPER_TINY.id, language)

    fun getCurrentModel(): String? = currentModelId
    fun getCurrentLanguage(): String = currentLanguage
    fun isReady(): Boolean = isInitialized && recognizer != null

    fun release() {
        try { recognizer?.release() } catch (e: Throwable) { Log.e(TAG, "Release error", e) }
        recognizer = null
        isInitialized = false
        currentModelId = null
        Log.d(TAG, "ASR released")
    }

    /** Read 16-bit PCM from a 44-byte-header WAV file into normalized floats. */
    private fun readWavPcm16(file: File): FloatArray? = try {
        file.inputStream().use { input ->
            val header = ByteArray(44)
            if (input.read(header) != 44) return@use null
            val pcm = input.readBytes()
            val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(pcm.size / 2) { buf.short / 32768.0f }
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to read WAV: ${e.message}")
        null
    }
}

data class ASRConfig(
    val modelId: String,
    val language: String = "en",
) {
    companion object {
        fun default() = ASRConfig(AIModelCatalog.SHERPA_ONNX_WHISPER_TINY.id)
        fun forLanguage(language: String) = ASRConfig(AIModelCatalog.SHERPA_ONNX_WHISPER_TINY.id, language)
    }
}
