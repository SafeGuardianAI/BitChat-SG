package com.bitchat.android.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Unit tests for [VADService] signal-processing logic.
 *
 * These tests exercise the pure functions (RMS, ZCR, threshold, frame
 * classification) without requiring an Android device or microphone.
 */
class VADServiceTest {

    private lateinit var vad: VADService

    @Before
    fun setUp() {
        vad = VADService()
    }

    // ── calculateRMS ───────────────────────────────────────────────────

    @Test
    fun `calculateRMS returns zero for silent frame`() {
        val silence = ShortArray(480) { 0 }
        assertEquals(0.0, vad.calculateRMS(silence), 0.001)
    }

    @Test
    fun `calculateRMS returns correct value for constant signal`() {
        // All samples = 1000 → RMS = 1000.0
        val frame = ShortArray(480) { 1000 }
        assertEquals(1000.0, vad.calculateRMS(frame), 0.1)
    }

    @Test
    fun `calculateRMS returns correct value for alternating signal`() {
        // +5000 / -5000 alternating → RMS = 5000.0
        val frame = ShortArray(480) { if (it % 2 == 0) 5000 else -5000 }
        assertEquals(5000.0, vad.calculateRMS(frame), 0.1)
    }

    @Test
    fun `calculateRMS handles single sample`() {
        val frame = shortArrayOf(3000)
        assertEquals(3000.0, vad.calculateRMS(frame), 0.1)
    }

    @Test
    fun `calculateRMS handles empty frame`() {
        assertEquals(0.0, vad.calculateRMS(ShortArray(0)), 0.001)
    }

    // ── processFrame: speech vs silence ────────────────────────────────

    @Test
    fun `processFrame detects speech for loud audio`() {
        // Frame well above the default threshold (500 RMS)
        val loud = ShortArray(480) { 10000 }
        val result = vad.processFrame(loud)
        assertTrue("Loud frame should be classified as speech", result)
    }

    @Test
    fun `processFrame detects silence for quiet audio`() {
        val quiet = ShortArray(480) { 5 }
        val result = vad.processFrame(quiet)
        assertFalse("Quiet frame should be classified as silence", result)
    }

    @Test
    fun `processFrame detects silence for all-zero frame`() {
        val silence = ShortArray(480) { 0 }
        assertFalse(vad.processFrame(silence))
    }

    @Test
    fun `processFrame threshold boundary`() {
        // Exactly at threshold should still be classified as speech (> threshold)
        // Default threshold is 500, so RMS of 501 should be speech
        // RMS = value for constant signal, so value ~501
        val borderline = ShortArray(480) { 501 }
        assertTrue(vad.processFrame(borderline))

        // Just below threshold
        val vad2 = VADService()
        val belowThreshold = ShortArray(480) { 100 }
        assertFalse(vad2.processFrame(belowThreshold))
    }

    // ── Zero-crossing rate ─────────────────────────────────────────────

    @Test
    fun `zeroCrossingRate is zero for constant signal`() {
        val constant = ShortArray(480) { 1000 }
        assertEquals(0.0, vad.calculateZeroCrossingRate(constant), 0.001)
    }

    @Test
    fun `zeroCrossingRate is 1 for alternating sign signal`() {
        // Every adjacent pair crosses zero → ZCR = 1.0
        val alternating = ShortArray(480) { if (it % 2 == 0) 1000 else -1000 }
        assertEquals(1.0, vad.calculateZeroCrossingRate(alternating), 0.01)
    }

    @Test
    fun `zeroCrossingRate is approximately half for half-period signal`() {
        // First half positive, second half negative → 1 crossing out of 479 pairs
        val frame = ShortArray(480) { if (it < 240) 1000 else -1000 }
        val zcr = vad.calculateZeroCrossingRate(frame)
        // Exactly 1 crossing / 479 ≈ 0.002
        assertTrue("ZCR should be very low for single crossing", zcr < 0.01)
    }

    @Test
    fun `zeroCrossingRate handles short frames`() {
        assertEquals(0.0, vad.calculateZeroCrossingRate(shortArrayOf(100)), 0.001)
        assertEquals(1.0, vad.calculateZeroCrossingRate(shortArrayOf(100, -100)), 0.001)
        assertEquals(0.0, vad.calculateZeroCrossingRate(shortArrayOf(100, 200)), 0.001)
    }

    // ── Adaptive threshold ─────────────────────────────────────────────

    @Test
    fun `adaptive threshold decreases in quiet environment`() {
        val initialThreshold = vad.silenceThreshold

        // Feed many silence frames to drive the noise floor down
        val quiet = ShortArray(480) { 10 }
        repeat(200) {
            vad.updateThreshold(AudioFeatures.rms(quiet), false)
        }

        // Threshold should have adapted downward (but not below minimum)
        assertTrue(
            "Threshold should adapt to quiet environment",
            vad.silenceThreshold <= initialThreshold
        )
    }

    @Test
    fun `adaptive threshold does not update during speech`() {
        // Initialise noise floor with some quiet frames first
        val quiet = ShortArray(480) { 10 }
        repeat(50) { vad.updateThreshold(AudioFeatures.rms(quiet), false) }
        val thresholdBeforeSpeech = vad.silenceThreshold

        // Loud frames marked as speech should NOT move the noise floor
        val loud = ShortArray(480) { 20000 }
        repeat(50) { vad.updateThreshold(AudioFeatures.rms(loud), true) }

        assertEquals(
            "Threshold should not change during speech",
            thresholdBeforeSpeech,
            vad.silenceThreshold,
            0.001
        )
    }

    @Test
    fun `adaptive threshold rises in noisy environment`() {
        // Start with quiet
        val quiet = ShortArray(480) { 10 }
        repeat(100) { vad.updateThreshold(AudioFeatures.rms(quiet), false) }
        val quietThreshold = vad.silenceThreshold

        // Increase ambient noise (but below speech levels)
        val noisy = ShortArray(480) { 400 }
        repeat(200) { vad.updateThreshold(AudioFeatures.rms(noisy), false) }

        assertTrue(
            "Threshold should rise in noisier environment",
            vad.silenceThreshold > quietThreshold
        )
    }

    // ── Distress detection ─────────────────────────────────────────────

    @Test
    fun `detectDistress returns true for very high energy with low ZCR`() {
        // Energy well above 4× threshold, low ZCR (voiced shout)
        assertTrue(vad.detectDistress(5000.0, 0.01))
    }

    @Test
    fun `detectDistress returns true for very high energy with high ZCR`() {
        // Energy well above 4× threshold, high ZCR (scream)
        assertTrue(vad.detectDistress(5000.0, 0.40))
    }

    @Test
    fun `detectDistress returns false for moderate energy`() {
        // Energy below 4× threshold
        assertFalse(vad.detectDistress(600.0, 0.01))
    }

    @Test
    fun `detectDistress returns false for mid-range ZCR`() {
        // High energy but ZCR in the normal speech range
        assertFalse(vad.detectDistress(5000.0, 0.15))
    }

    // ── AudioFeatures (additional coverage) ────────────────────────────

    @Test
    fun `AudioFeatures rmsDbfs returns negative infinity for silence`() {
        val silence = ShortArray(480) { 0 }
        assertEquals(Double.NEGATIVE_INFINITY, AudioFeatures.rmsDbfs(silence), 0.0)
    }

    @Test
    fun `AudioFeatures peakAmplitude returns correct value`() {
        val frame = ShortArray(100) { (it * 100).toShort() }
        assertEquals(9900, AudioFeatures.peakAmplitude(frame))
    }

    @Test
    fun `AudioFeatures shortTimeEnergyRatio is 0_5 for uniform signal`() {
        val uniform = ShortArray(480) { 1000 }
        assertEquals(0.5, AudioFeatures.shortTimeEnergyRatio(uniform), 0.02)
    }
}
