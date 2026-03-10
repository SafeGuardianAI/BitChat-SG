package com.bitchat.android.standards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for START/SALT triage protocol implementation.
 *
 * Validates the decision tree produces correct triage levels
 * for all branches of the START algorithm.
 */
class TriageProtocolTest {

    // --- START Protocol Tests ---

    @Test
    fun `walking patient should be MINIMAL`() {
        val assessment = TriageAssessment(canWalk = true)
        val result = TriageProtocol.assessSTART(assessment)
        assertEquals(TriageLevel.MINIMAL, result)
    }

    @Test
    fun `not breathing after repositioning should be EXPECTANT`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = false
        )
        val result = TriageProtocol.assessSTART(assessment)
        assertEquals(TriageLevel.EXPECTANT, result)
    }

    @Test
    fun `respiratory rate over 30 should be IMMEDIATE`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 35
        )
        val result = TriageProtocol.assessSTART(assessment)
        assertEquals(TriageLevel.IMMEDIATE, result)
    }

    @Test
    fun `capillary refill over 2 seconds should be IMMEDIATE`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 20,
            capillaryRefill = 4
        )
        val result = TriageProtocol.assessSTART(assessment)
        assertEquals(TriageLevel.IMMEDIATE, result)
    }

    @Test
    fun `cannot follow commands should be IMMEDIATE`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 20,
            capillaryRefill = 1,
            followsCommands = false
        )
        val result = TriageProtocol.assessSTART(assessment)
        assertEquals(TriageLevel.IMMEDIATE, result)
    }

    @Test
    fun `all clear should be DELAYED`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 20,
            capillaryRefill = 1,
            followsCommands = true
        )
        val result = TriageProtocol.assessSTART(assessment)
        assertEquals(TriageLevel.DELAYED, result)
    }

    @Test
    fun `respiratory rate exactly 30 should be DELAYED if all else clear`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 30,
            capillaryRefill = 2,
            followsCommands = true
        )
        val result = TriageProtocol.assessSTART(assessment)
        assertEquals(TriageLevel.DELAYED, result)
    }

    @Test
    fun `capillary refill exactly 2 should be DELAYED if all else clear`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 25,
            capillaryRefill = 2,
            followsCommands = true
        )
        val result = TriageProtocol.assessSTART(assessment)
        assertEquals(TriageLevel.DELAYED, result)
    }

    // --- SALT Protocol Tests ---

    @Test
    fun `SALT - walking patient should be MINIMAL`() {
        val assessment = TriageAssessment(canWalk = true)
        val result = TriageProtocol.assessSALT(assessment)
        assertEquals(TriageLevel.MINIMAL, result)
    }

    @Test
    fun `SALT - not breathing and no pulse should be DEAD`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = false,
            hasPulse = false
        )
        val result = TriageProtocol.assessSALT(assessment)
        assertEquals(TriageLevel.DEAD, result)
    }

    @Test
    fun `SALT - unresponsive patient should be IMMEDIATE`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            consciousness = "Unresponsive"
        )
        val result = TriageProtocol.assessSALT(assessment)
        assertEquals(TriageLevel.IMMEDIATE, result)
    }

    @Test
    fun `SALT - pain responsive should be IMMEDIATE`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            consciousness = "Pain"
        )
        val result = TriageProtocol.assessSALT(assessment)
        assertEquals(TriageLevel.IMMEDIATE, result)
    }

    // --- Interactive Triage Question Flow ---

    @Test
    fun `first question should ask about walking`() {
        val assessment = TriageAssessment()
        val question = TriageProtocol.getNextQuestion(assessment)
        assertNotNull(question)
        assertEquals("can_walk", question!!.id)
        assertEquals("canWalk", question.fieldName)
    }

    @Test
    fun `walking patient should have no more questions`() {
        val assessment = TriageAssessment(canWalk = true)
        val question = TriageProtocol.getNextQuestion(assessment)
        assertNull(question)
    }

    @Test
    fun `non-walking patient should be asked about breathing`() {
        val assessment = TriageAssessment(canWalk = false)
        val question = TriageProtocol.getNextQuestion(assessment)
        assertNotNull(question)
        assertEquals("is_breathing", question!!.id)
    }

    @Test
    fun `breathing patient should be asked about respiratory rate`() {
        val assessment = TriageAssessment(canWalk = false, isBreathing = true)
        val question = TriageProtocol.getNextQuestion(assessment)
        assertNotNull(question)
        assertEquals("respiratory_rate", question!!.id)
    }

    @Test
    fun `normal respiratory rate should lead to capillary refill question`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 20
        )
        val question = TriageProtocol.getNextQuestion(assessment)
        assertNotNull(question)
        assertEquals("capillary_refill", question!!.id)
    }

    @Test
    fun `normal perfusion should lead to commands question`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 20,
            capillaryRefill = 1
        )
        val question = TriageProtocol.getNextQuestion(assessment)
        assertNotNull(question)
        assertEquals("follows_commands", question!!.id)
    }

    @Test
    fun `complete assessment should have no more questions`() {
        val assessment = TriageAssessment(
            canWalk = false,
            isBreathing = true,
            respiratoryRate = 20,
            capillaryRefill = 1,
            followsCommands = true
        )
        val question = TriageProtocol.getNextQuestion(assessment)
        assertNull(question)
    }

    // --- Triage Level Properties ---

    @Test
    fun `triage levels should have correct codes`() {
        assertEquals("T1", TriageLevel.IMMEDIATE.code)
        assertEquals("T2", TriageLevel.DELAYED.code)
        assertEquals("T3", TriageLevel.MINIMAL.code)
        assertEquals("T4", TriageLevel.EXPECTANT.code)
        assertEquals("T0", TriageLevel.DEAD.code)
    }

    @Test
    fun `triage levels should have correct colors`() {
        assertEquals("red", TriageLevel.IMMEDIATE.color)
        assertEquals("yellow", TriageLevel.DELAYED.color)
        assertEquals("green", TriageLevel.MINIMAL.color)
        assertEquals("black", TriageLevel.EXPECTANT.color)
        assertEquals("gray", TriageLevel.DEAD.color)
    }

    // --- Triage Prompt ---

    @Test
    fun `triage prompt should contain all triage levels`() {
        val prompt = TriageProtocol.generateTriagePrompt()
        for (level in TriageLevel.entries) {
            assert(prompt.contains(level.code)) { "Prompt missing triage code ${level.code}" }
            assert(prompt.contains(level.color)) { "Prompt missing color ${level.color}" }
        }
    }
}
