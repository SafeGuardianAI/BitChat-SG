package com.bitchat.android.asr

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Microphone audio recorder for ASR.
 * Records to a temp WAV file. Handles permission and recorder lifecycle safely.
 */
class AsrAudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AsrAudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var outputFile: File? = null

    /**
     * Check if RECORD_AUDIO permission is granted.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording to a temp WAV file.
     * @return Path to the recording file, or null if failed (e.g. no permission).
     */
    suspend fun startRecording(): File? = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return@withContext null
        }
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return@withContext null
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return@withContext null
            }

            outputFile = File(context.cacheDir, "asr_record_${System.currentTimeMillis()}.wav")
            isRecording = true

            val fos = FileOutputStream(outputFile!!)
            writeWavHeader(fos, SAMPLE_RATE, 1, 16)

            recordingThread = Thread {
                try {
                    val buffer = ShortArray(bufferSize / 2)
                    audioRecord?.startRecording()

                    while (isRecording) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until read) byteBuffer.putShort(buffer[i])
                            fos.write(byteBuffer.array())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recording error", e)
                } finally {
                    try {
                        fos.close()
                    } catch (_: Exception) { }
                    try {
                        audioRecord?.stop()
                    } catch (_: Exception) { }
                }
            }
            recordingThread?.start()

            Log.d(TAG, "Recording started: ${outputFile!!.absolutePath}")
            outputFile!!
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording = false
            outputFile = null
            null
        }
    }

    /**
     * Stop recording and return the recorded file.
     */
    fun stopRecording(): File? {
        if (!isRecording) {
            Log.w(TAG, "Not recording")
            return null
        }
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null

        val file = outputFile
        outputFile = null
        if (file != null && file.exists()) {
            try {
                val dataSize = (file.length() - 44).toInt().coerceAtLeast(0)
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(4)
                    raf.write(intToLittleEndian(36 + dataSize))
                    raf.seek(40)
                    raf.write(intToLittleEndian(dataSize))
                }
            } catch (_: Exception) { }
        }
        Log.d(TAG, "Recording stopped: ${file?.absolutePath}")
        return file
    }

    fun isRecording(): Boolean = isRecording

    private fun writeWavHeader(fos: FileOutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        fos.write("RIFF".toByteArray())
        fos.write(intToLittleEndian(36))
        fos.write("WAVE".toByteArray())
        fos.write("fmt ".toByteArray())
        fos.write(intToLittleEndian(16))
        fos.write(shortToLittleEndian(1))
        fos.write(shortToLittleEndian(channels.toShort()))
        fos.write(intToLittleEndian(sampleRate))
        fos.write(intToLittleEndian(byteRate))
        fos.write(shortToLittleEndian(blockAlign))
        fos.write(shortToLittleEndian(bitsPerSample.toShort()))
        fos.write("data".toByteArray())
        fos.write(intToLittleEndian(0))
    }

    private fun intToLittleEndian(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun shortToLittleEndian(v: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}
