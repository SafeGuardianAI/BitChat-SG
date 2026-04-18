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
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline speech-to-text using Sherpa-ONNX with Whisper Tiny.
 *
 * Model directory: filesDir/models/sherpa-onnx-whisper-tiny.en/
 * Files required:
 *   tiny.en-encoder.int8.onnx
 *   tiny.en-decoder.int8.onnx
 *   tiny.en-tokens.txt
 *
 * Download via AIModelCatalog.SHERPA_ONNX_SMALL_EN through the existing ModelManager.
 */
class ASRService(private val context: Context) {

    companion object {
        private const val TAG = "ASRService"
        private const val TARGET_SAMPLE_RATE = 16000

        // Standard file names inside the sherpa-onnx-whisper-tiny.en archive
        private const val ENCODER_FILE = "tiny.en-encoder.int8.onnx"
        private const val DECODER_FILE = "tiny.en-decoder.int8.onnx"
        private const val TOKENS_FILE  = "tiny.en-tokens.txt"

        fun modelDir(context: Context): File =
            File(context.filesDir, "models/${AIModelCatalog.SHERPA_ONNX_SMALL_EN.modelFileName}")

        fun isModelDownloaded(context: Context): Boolean {
            val dir = modelDir(context)
            return File(dir, ENCODER_FILE).exists() &&
                   File(dir, DECODER_FILE).exists() &&
                   File(dir, TOKENS_FILE).exists()
        }
    }

    private var recognizer: OfflineRecognizer? = null

    val isInitialized: Boolean get() = recognizer != null

    /** Call once before transcribing. Returns false if model files are missing. */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext true
        try {
            val dir = modelDir(context)
            val encoderFile = File(dir, ENCODER_FILE)
            val decoderFile = File(dir, DECODER_FILE)
            val tokensFile  = File(dir, TOKENS_FILE)

            if (!encoderFile.exists() || !decoderFile.exists() || !tokensFile.exists()) {
                Log.w(TAG, "Whisper Tiny model not downloaded — expected in ${dir.absolutePath}")
                return@withContext false
            }

            val whisperConfig = OfflineWhisperModelConfig(
                /* encoder     = */ encoderFile.absolutePath,
                /* decoder     = */ decoderFile.absolutePath,
                /* language    = */ "en",
                /* task        = */ "transcribe",
                /* tailPaddings= */ -1
            )

            val modelConfig = OfflineModelConfig().apply {
                whisper    = whisperConfig
                tokens     = tokensFile.absolutePath
                numThreads = 2
                debug      = false
                provider   = "cpu"
            }

            val config = OfflineRecognizerConfig(
                featConfig    = FeatureConfig(TARGET_SAMPLE_RATE, 80, 0f),
                modelConfig   = modelConfig
            )

            recognizer = OfflineRecognizer(context.assets, config)
            Log.d(TAG, "ASR initialized — Whisper Tiny EN")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ASR", e)
            false
        }
    }

    /** Transcribe any MediaExtractor-compatible audio file (M4A, WAV, etc.). */
    suspend fun transcribeFile(file: File): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            Log.w(TAG, "Audio file not found: ${file.absolutePath}")
            return@withContext null
        }
        if (recognizer == null) {
            if (!initialize()) return@withContext null
        }
        try {
            val samples = M4ADecoder.decodeToFloat(file.absolutePath, TARGET_SAMPLE_RATE)
                ?: return@withContext null

            val stream = recognizer!!.createStream()
            stream.acceptWaveform(samples, TARGET_SAMPLE_RATE)
            recognizer!!.decode(stream)
            val text = recognizer!!.getResult(stream).text.trim()
            stream.release()

            Log.d(TAG, "Transcribed ${file.name}: \"$text\"")
            text.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed for ${file.name}", e)
            null
        }
    }

    /** Transcribe raw PCM float samples already at [TARGET_SAMPLE_RATE] Hz. */
    suspend fun transcribeFloat(samples: FloatArray): String? = withContext(Dispatchers.IO) {
        if (recognizer == null) {
            if (!initialize()) return@withContext null
        }
        try {
            val stream = recognizer!!.createStream()
            stream.acceptWaveform(samples, TARGET_SAMPLE_RATE)
            recognizer!!.decode(stream)
            val text = recognizer!!.getResult(stream).text.trim()
            stream.release()
            text.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            null
        }
    }

    fun isReady(): Boolean = recognizer != null

    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        Log.d(TAG, "ASR released")
    }
}
