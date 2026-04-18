package com.bitchat.android.features.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes an M4A (or any MediaExtractor-compatible) audio file to a FloatArray
 * suitable for Sherpa-ONNX ASR. Output is normalized PCM in [-1, 1] at
 * [targetSampleRate] Hz, downmixed to mono.
 */
object M4ADecoder {

    suspend fun decodeToFloat(path: String, targetSampleRate: Int = 16000): FloatArray? =
        withContext(Dispatchers.IO) {
            runCatching { decode(path, targetSampleRate) }.getOrNull()
        }

    private fun decode(path: String, targetSampleRate: Int): FloatArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
            extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        // Pre-allocate for up to 60 seconds of mono audio at source rate
        val rawSamples = ArrayList<Short>(sourceSampleRate * 60)
        val outInfo = MediaCodec.BufferInfo()
        var sawEOS = false

        while (!sawEOS) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outIndex = codec.dequeueOutputBuffer(outInfo, 10_000)
            while (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)
                if (outBuf != null && outInfo.size > 0) {
                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                    val shortCount = outInfo.size / 2
                    val shorts = ShortArray(shortCount)
                    outBuf.asShortBuffer().get(shorts)
                    // Downmix multi-channel to mono by averaging
                    if (channelCount > 1) {
                        var i = 0
                        while (i + channelCount <= shortCount) {
                            var sum = 0L
                            for (ch in 0 until channelCount) sum += shorts[i + ch]
                            rawSamples.add((sum / channelCount).toShort())
                            i += channelCount
                        }
                    } else {
                        for (s in shorts) rawSamples.add(s)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(outInfo, 0)
            }

            if (outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEOS = true
        }

        codec.stop()
        codec.release()
        extractor.release()

        if (rawSamples.isEmpty()) return null

        val monoShorts = ShortArray(rawSamples.size) { rawSamples[it] }

        // Resample to targetSampleRate using linear interpolation
        val resampled = if (sourceSampleRate != targetSampleRate) {
            resample(monoShorts, sourceSampleRate, targetSampleRate)
        } else {
            monoShorts
        }

        // Normalize to [-1, 1] floats as expected by Sherpa-ONNX acceptWaveform()
        return FloatArray(resampled.size) { resampled[it] / 32768f }
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputSize = (input.size / ratio).roundToInt()
        return ShortArray(outputSize) { i ->
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()
            val a = input[srcIdx].toFloat()
            val b = input[min(input.size - 1, srcIdx + 1)].toFloat()
            (a + (b - a) * frac).roundToInt().toShort()
        }
    }
}
