package com.bitchat.android.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice Input Service
 * 
 * Handles audio recording for ASR (Automatic Speech Recognition)
 */
class VoiceInputService(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputService"
        private const val SAMPLE_RATE = 16000 // 16kHz for ASR
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var isCurrentlyRecording = false
    private var recordingThread: Thread? = null

    // Recording state
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    // Audio buffer for real-time processing
    private val audioBuffer = mutableListOf<Short>()

    /**
     * Start recording audio
     * 
     * @param maxDurationMs Maximum recording duration in milliseconds (0 = unlimited)
     * @param onAudioData Callback for real-time audio data
     * @return File path of recorded audio or null if failed
     */
    suspend fun startRecording(
        maxDurationMs: Long = 0,
        onAudioData: ((ShortArray) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        
        if (isCurrentlyRecording) {
            Log.w(TAG, "Already recording")
            return@withContext null
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                return@withContext null
            }

            // Create output file
            val outputFile = File(context.cacheDir, "voice_input_${System.currentTimeMillis()}.wav")
            
            isCurrentlyRecording = true
            _isRecording.value = true
            _recordingDuration.value = 0L
            audioBuffer.clear()

            Log.d(TAG, "Starting recording to: ${outputFile.absolutePath}")

            recordingThread = Thread {
                var fos: FileOutputStream? = null
                try {
                    val buffer = ShortArray(bufferSize / 2)
                    val startTime = System.currentTimeMillis()

                    fos = FileOutputStream(outputFile)
                    // Write WAV header
                    writeWavHeader(fos, SAMPLE_RATE, 1, 16)

                    audioRecord?.startRecording()

                    while (isCurrentlyRecording) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        
                        if (bytesRead > 0) {
                            // Convert to bytes and write to file
                            val audioBytes = ShortArray(bytesRead)
                            System.arraycopy(buffer, 0, audioBytes, 0, bytesRead)
                            
                            // Write to file
                            val byteBuffer = ByteBuffer.allocate(bytesRead * 2)
                            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            for (sample in audioBytes) {
                                byteBuffer.putShort(sample)
                            }
                            fos?.write(byteBuffer.array())

                            // Add to buffer for real-time processing
                            audioBuffer.addAll(audioBytes.toList())
                            
                            // Callback for real-time processing
                            onAudioData?.invoke(audioBytes)

                            // Check duration limit
                            if (maxDurationMs > 0) {
                                val elapsed = System.currentTimeMillis() - startTime
                                _recordingDuration.value = elapsed
                                if (elapsed >= maxDurationMs) {
                                    Log.d(TAG, "Recording duration limit reached")
                                    break
                                }
                            } else {
                                _recordingDuration.value = System.currentTimeMillis() - startTime
                            }
                        }
                    }

                    Log.d(TAG, "Recording completed: ${outputFile.absolutePath}")

                } catch (e: Exception) {
                    Log.e(TAG, "Recording error", e)
                } finally {
                    try {
                        fos?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing file output stream", e)
                    }
                    try {
                        audioRecord?.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping audio record in thread", e)
                    }
                }
            }

            recordingThread?.start()
            return@withContext outputFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isCurrentlyRecording = false
            _isRecording.value = false
            return@withContext null
        }
    }

    /**
     * Stop recording
     */
    fun stopRecording() {
        if (!isCurrentlyRecording) {
            Log.w(TAG, "Not currently recording")
            return
        }

        Log.d(TAG, "Stopping recording")
        isCurrentlyRecording = false
        _isRecording.value = false

        try {
            // Stop the audio record
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }

        try {
            // Release the audio record
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio record", e)
        } finally {
            audioRecord = null
        }

        try {
            // Wait for the recording thread to finish (with timeout)
            recordingThread?.join(2000) // Wait up to 2 seconds
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for recording thread", e)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "Error joining recording thread", e)
        } finally {
            recordingThread = null
        }
    }

    /**
     * Get current audio buffer (for real-time processing)
     */
    fun getAudioBuffer(): ShortArray {
        return audioBuffer.toShortArray()
    }

    /**
     * Clear audio buffer
     */
    fun clearAudioBuffer() {
        audioBuffer.clear()
    }

    /**
     * Check if microphone permission is available
     */
    fun hasMicrophonePermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 
               android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get recommended buffer size for real-time processing
     */
    fun getRecommendedBufferSize(): Int {
        return AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
    }

    /**
     * Write WAV file header
     */
    private fun writeWavHeader(fos: FileOutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = 0 // Will be updated when recording stops

        // RIFF header
        fos.write("RIFF".toByteArray())
        fos.write(intToLittleEndian(36 + dataSize)) // File size - 8
        fos.write("WAVE".toByteArray())

        // fmt chunk
        fos.write("fmt ".toByteArray())
        fos.write(intToLittleEndian(16)) // fmt chunk size
        fos.write(shortToLittleEndian(1)) // Audio format (PCM)
        fos.write(shortToLittleEndian(channels.toShort()))
        fos.write(intToLittleEndian(sampleRate))
        fos.write(intToLittleEndian(byteRate))
        fos.write(shortToLittleEndian(blockAlign.toShort()))
        fos.write(shortToLittleEndian(bitsPerSample.toShort()))

        // data chunk
        fos.write("data".toByteArray())
        fos.write(intToLittleEndian(dataSize)) // Data size (will be updated)
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToLittleEndian(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    /**
     * Release resources
     */
    fun release() {
        stopRecording()
        audioBuffer.clear()
        Log.d(TAG, "VoiceInputService released")
    }
}
