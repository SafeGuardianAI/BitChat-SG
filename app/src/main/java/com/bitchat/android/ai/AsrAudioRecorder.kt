package com.bitchat.android.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records 16 kHz mono PCM to a WAV file — the exact format ASRService.readWavPcm16 expects.
 * Uses AudioRecord directly (not MediaRecorder) so we get raw samples without a container.
 */
class AsrAudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AsrAudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile var isRecording = false
        private set

    private var audioRecord: AudioRecord? = null
    private var outFile: File? = null
    private var recordThread: Thread? = null
    private val chunks = mutableListOf<ShortArray>()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startRecording(): File? {
        if (isRecording) return outFile
        if (!hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return null
        }
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(2048)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord: ${e.message}")
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            Log.e(TAG, "AudioRecord not initialized")
            return null
        }
        val dir = File(context.cacheDir, "asr").also { it.mkdirs() }
        val file = File(dir, "asr_${System.currentTimeMillis()}.wav")
        outFile = file
        audioRecord = rec
        chunks.clear()
        isRecording = true
        rec.startRecording()

        val chunkSize = bufSize / 2
        recordThread = Thread {
            val buf = ShortArray(chunkSize)
            while (isRecording) {
                val n = rec.read(buf, 0, chunkSize)
                if (n > 0) synchronized(chunks) { chunks.add(buf.copyOf(n)) }
            }
        }.also { it.isDaemon = true; it.start() }

        Log.d(TAG, "ASR recording started → ${file.name}")
        return file
    }

    fun stopRecording(): File? {
        if (!isRecording) return null
        isRecording = false
        recordThread?.join(2000)
        recordThread = null
        val rec = audioRecord ?: return null
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        audioRecord = null
        val file = outFile ?: return null
        outFile = null
        val allSamples: ShortArray
        synchronized(chunks) {
            allSamples = ShortArray(chunks.sumOf { it.size })
            var pos = 0
            chunks.forEach { c -> c.copyInto(allSamples, pos); pos += c.size }
            chunks.clear()
        }
        Log.d(TAG, "ASR recording stopped: ${allSamples.size} samples")
        return if (writeWav(file, allSamples)) file else null
    }

    private fun writeWav(file: File, samples: ShortArray): Boolean = try {
        val pcmBytes = samples.size * 2
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + pcmBytes)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)                   // PCM subchunk size
            header.putShort(1)                  // audio format: PCM
            header.putShort(1)                  // mono
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2)      // byte rate
            header.putShort(2)                  // block align
            header.putShort(16)                 // bits per sample
            header.put("data".toByteArray())
            header.putInt(pcmBytes)
            fos.write(header.array())
            val pcm = ByteBuffer.allocate(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { pcm.putShort(it) }
            fos.write(pcm.array())
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to write WAV: ${e.message}")
        false
    }
}
