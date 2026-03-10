package com.bitchat.android.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Audio feature extraction utilities for keyword matching and VAD.
 *
 * Provides signal-processing primitives that operate on 16-bit PCM audio
 * frames (ShortArray). All functions are pure and thread-safe.
 */
object AudioFeatures {

    // ── RMS Energy ──────────────────────────────────────────────────────

    /**
     * Root-Mean-Square energy of a PCM frame.
     * Returns 0.0 for an empty frame.
     */
    fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in frame) {
            val s = sample.toDouble()
            sum += s * s
        }
        return sqrt(sum / frame.size)
    }

    // ── Zero-Crossing Rate ──────────────────────────────────────────────

    /**
     * Fraction of adjacent sample pairs that cross zero.
     * Range: 0.0 – 1.0.  Higher values indicate noisy / unvoiced content.
     */
    fun zeroCrossingRate(frame: ShortArray): Double {
        if (frame.size < 2) return 0.0
        var crossings = 0
        for (i in 1 until frame.size) {
            if ((frame[i].toInt() >= 0) != (frame[i - 1].toInt() >= 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / (frame.size - 1)
    }

    // ── Short-Time Energy Ratio ─────────────────────────────────────────

    /**
     * Ratio of the energy in the first half of the frame to the total energy.
     * A value far from 0.5 indicates a transient or onset.
     */
    fun shortTimeEnergyRatio(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        val mid = frame.size / 2
        var firstHalf = 0.0
        var total = 0.0
        for (i in frame.indices) {
            val s = frame[i].toDouble()
            val e = s * s
            total += e
            if (i < mid) firstHalf += e
        }
        return if (total == 0.0) 0.5 else firstHalf / total
    }

    // ── Spectral Centroid (simple real-valued DFT) ──────────────────────

    /**
     * Spectral centroid in Hz, computed via a straightforward DFT on the frame.
     *
     * For production use on longer frames consider replacing with an FFT.
     * Here we keep it dependency-free.
     */
    fun spectralCentroid(frame: ShortArray, sampleRate: Int = 16000): Double {
        val n = frame.size
        if (n == 0) return 0.0

        // Number of unique frequency bins (Nyquist included)
        val numBins = n / 2 + 1
        val magnitudes = DoubleArray(numBins)

        for (k in 0 until numBins) {
            var real = 0.0
            var imag = 0.0
            val angularConst = 2.0 * Math.PI * k / n
            for (i in 0 until n) {
                val sample = frame[i].toDouble()
                real += sample * cos(angularConst * i)
                imag -= sample * kotlin.math.sin(angularConst * i)
            }
            magnitudes[k] = sqrt(real * real + imag * imag)
        }

        var weightedSum = 0.0
        var magnitudeSum = 0.0
        val freqResolution = sampleRate.toDouble() / n
        for (k in 0 until numBins) {
            val freq = k * freqResolution
            weightedSum += freq * magnitudes[k]
            magnitudeSum += magnitudes[k]
        }

        return if (magnitudeSum == 0.0) 0.0 else weightedSum / magnitudeSum
    }

    // ── Simple MFCC-like features ───────────────────────────────────────

    /**
     * Compute a simplified log-filter-bank energy vector (MFCC-like).
     *
     * Returns [numFilters] values representing log energies in mel-spaced
     * triangular filter bands.  This is not a full MFCC (no DCT) but is
     * sufficient for lightweight template matching.
     *
     * @param frame      PCM audio frame.
     * @param sampleRate Sample rate in Hz.
     * @param numFilters Number of triangular mel filters (default 13).
     */
    fun logMelEnergies(
        frame: ShortArray,
        sampleRate: Int = 16000,
        numFilters: Int = 13
    ): DoubleArray {
        val n = frame.size
        if (n == 0) return DoubleArray(numFilters)

        val numBins = n / 2 + 1
        val powerSpectrum = DoubleArray(numBins)

        // Power spectrum via DFT
        for (k in 0 until numBins) {
            var real = 0.0
            var imag = 0.0
            val angularConst = 2.0 * Math.PI * k / n
            for (i in 0 until n) {
                val s = frame[i].toDouble()
                real += s * cos(angularConst * i)
                imag -= s * kotlin.math.sin(angularConst * i)
            }
            powerSpectrum[k] = (real * real + imag * imag) / n
        }

        // Mel scale helpers
        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(sampleRate / 2.0)
        val melPoints = DoubleArray(numFilters + 2) { i ->
            melToHz(lowMel + (highMel - lowMel) * i / (numFilters + 1))
        }
        val binPoints = IntArray(melPoints.size) { i ->
            ((melPoints[i] / sampleRate) * n).toInt().coerceIn(0, numBins - 1)
        }

        val energies = DoubleArray(numFilters)
        for (m in 0 until numFilters) {
            val startBin = binPoints[m]
            val centerBin = binPoints[m + 1]
            val endBin = binPoints[m + 2]
            var filterEnergy = 0.0

            for (k in startBin until centerBin) {
                val weight = if (centerBin == startBin) 1.0
                else (k - startBin).toDouble() / (centerBin - startBin)
                filterEnergy += powerSpectrum[k] * weight
            }
            for (k in centerBin until endBin) {
                val weight = if (endBin == centerBin) 1.0
                else (endBin - k).toDouble() / (endBin - centerBin)
                filterEnergy += powerSpectrum[k] * weight
            }

            energies[m] = if (filterEnergy > 1e-10) ln(filterEnergy) else ln(1e-10)
        }

        return energies
    }

    // ── Utility: peak amplitude ─────────────────────────────────────────

    /**
     * Maximum absolute amplitude in the frame (0 – 32767).
     */
    fun peakAmplitude(frame: ShortArray): Int {
        var peak = 0
        for (s in frame) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        return peak
    }

    /**
     * Energy in dB relative to full-scale (dBFS).
     * Silence returns -infinity.
     */
    fun rmsDbfs(frame: ShortArray): Double {
        val r = rms(frame)
        return if (r <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(r / 32767.0)
    }
}
