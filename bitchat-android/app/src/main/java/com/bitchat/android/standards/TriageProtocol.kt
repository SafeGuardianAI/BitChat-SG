package com.bitchat.android.standards

import kotlinx.serialization.Serializable

/**
 * START (Simple Triage and Rapid Treatment) Protocol
 *
 * Digital implementation of the field triage algorithm used globally.
 * No formal FHIR IG exists for START (identified gap in SAFE-NET report).
 *
 * Decision tree:
 * 1. Can walk? -> MINIMAL (green)
 * 2. Breathing?
 *    - No -> Reposition airway -> Still no? -> EXPECTANT (black)
 *    - Yes -> Check respiratory rate
 * 3. Respiratory rate > 30? -> IMMEDIATE (red)
 * 4. Perfusion: capillary refill > 2s? -> IMMEDIATE (red)
 * 5. Mental status: follows commands?
 *    - No -> IMMEDIATE (red)
 *    - Yes -> DELAYED (yellow)
 */
object TriageProtocol {

    /**
     * Assess triage level using START protocol.
     *
     * Returns the triage level based on the assessment data provided.
     * Null fields are treated as "not yet assessed" and the algorithm
     * proceeds conservatively (upgrades toward IMMEDIATE when ambiguous).
     */
    fun assessSTART(assessment: TriageAssessment): TriageLevel {
        // Step 1: Can the patient walk?
        if (assessment.canWalk == true) {
            return TriageLevel.MINIMAL
        }

        // Step 2: Is the patient breathing?
        if (assessment.isBreathing == false) {
            // Airway repositioned and still not breathing -> EXPECTANT
            return TriageLevel.EXPECTANT
        }

        // Step 3: Check respiratory rate
        if (assessment.respiratoryRate != null && assessment.respiratoryRate > 30) {
            return TriageLevel.IMMEDIATE
        }

        // Step 4: Check perfusion (capillary refill)
        if (assessment.capillaryRefill != null && assessment.capillaryRefill > 2) {
            return TriageLevel.IMMEDIATE
        }

        // Step 5: Mental status - follows commands?
        if (assessment.followsCommands == false) {
            return TriageLevel.IMMEDIATE
        }

        // If breathing, rate <= 30, good perfusion, follows commands -> DELAYED
        if (assessment.followsCommands == true) {
            return TriageLevel.DELAYED
        }

        // Insufficient data but patient is not walking and is breathing:
        // default to IMMEDIATE (conservative)
        return TriageLevel.IMMEDIATE
    }

    /**
     * Assess triage level using SALT (Sort, Assess, Lifesaving interventions, Treatment/transport)
     * protocol. SALT is an enhanced version of START used by some jurisdictions.
     *
     * SALT adds a "Sort" step and considers lifesaving interventions.
     */
    fun assessSALT(assessment: TriageAssessment): TriageLevel {
        // Sort phase: can the patient walk?
        if (assessment.canWalk == true) {
            return TriageLevel.MINIMAL
        }

        // Assess phase: check for life threats
        if (assessment.isBreathing == false && assessment.hasPulse == false) {
            return TriageLevel.DEAD
        }

        if (assessment.isBreathing == false) {
            // Attempt lifesaving intervention (airway repositioning)
            // If still not breathing after intervention -> EXPECTANT
            return TriageLevel.EXPECTANT
        }

        // Check consciousness via AVPU
        if (assessment.consciousness != null) {
            when (assessment.consciousness.uppercase()) {
                "UNRESPONSIVE", "U" -> return TriageLevel.IMMEDIATE
                "PAIN", "P" -> return TriageLevel.IMMEDIATE
                "VERBAL", "V" -> {
                    // Continue to further assessment
                }
                "ALERT", "A" -> {
                    // Continue to further assessment
                }
            }
        }

        // Respiratory rate check
        if (assessment.respiratoryRate != null && assessment.respiratoryRate > 30) {
            return TriageLevel.IMMEDIATE
        }

        // Perfusion check
        if (assessment.capillaryRefill != null && assessment.capillaryRefill > 2) {
            return TriageLevel.IMMEDIATE
        }

        // Mental status
        if (assessment.followsCommands == false) {
            return TriageLevel.IMMEDIATE
        }

        if (assessment.followsCommands == true) {
            return TriageLevel.DELAYED
        }

        // Insufficient data with breathing patient -> IMMEDIATE (conservative)
        return TriageLevel.IMMEDIATE
    }

    /**
     * Interactive triage: returns the next question that should be asked
     * based on the current assessment state, following START protocol order.
     *
     * Returns null if assessment is complete and a triage level can be determined.
     */
    fun getNextQuestion(currentAssessment: TriageAssessment): TriageQuestion? {
        if (currentAssessment.canWalk == null) {
            return TriageQuestion(
                id = "can_walk",
                question = "Can the patient walk?",
                options = listOf("Yes", "No"),
                fieldName = "canWalk"
            )
        }

        if (currentAssessment.canWalk == true) {
            return null // MINIMAL, done
        }

        if (currentAssessment.isBreathing == null) {
            return TriageQuestion(
                id = "is_breathing",
                question = "Is the patient breathing (after repositioning airway if needed)?",
                options = listOf("Yes", "No"),
                fieldName = "isBreathing"
            )
        }

        if (currentAssessment.isBreathing == false) {
            return null // EXPECTANT, done
        }

        if (currentAssessment.respiratoryRate == null) {
            return TriageQuestion(
                id = "respiratory_rate",
                question = "What is the patient's respiratory rate (breaths per minute)?",
                options = listOf("Under 10", "10-30", "Over 30"),
                fieldName = "respiratoryRate"
            )
        }

        if (currentAssessment.respiratoryRate > 30) {
            return null // IMMEDIATE, done
        }

        if (currentAssessment.capillaryRefill == null) {
            return TriageQuestion(
                id = "capillary_refill",
                question = "What is the capillary refill time (press fingernail, count seconds)?",
                options = listOf("Under 2 seconds", "Over 2 seconds"),
                fieldName = "capillaryRefill"
            )
        }

        if (currentAssessment.capillaryRefill > 2) {
            return null // IMMEDIATE, done
        }

        if (currentAssessment.followsCommands == null) {
            return TriageQuestion(
                id = "follows_commands",
                question = "Can the patient follow simple commands (e.g., 'squeeze my hand')?",
                options = listOf("Yes", "No"),
                fieldName = "followsCommands"
            )
        }

        // Assessment complete
        return null
    }

    /**
     * Generate a triage assessment prompt suitable for AI-assisted triage.
     */
    fun generateTriagePrompt(): String {
        return buildString {
            appendLine("You are assisting with emergency medical triage using the START protocol.")
            appendLine("Ask the following questions in order and determine the triage category:")
            appendLine()
            appendLine("1. Can the patient walk? If YES -> GREEN (Minimal/T3)")
            appendLine("2. Is the patient breathing? If NO after repositioning airway -> BLACK (Expectant/T4)")
            appendLine("3. Is respiratory rate > 30/min? If YES -> RED (Immediate/T1)")
            appendLine("4. Is capillary refill > 2 seconds? If YES -> RED (Immediate/T1)")
            appendLine("5. Can the patient follow simple commands? If NO -> RED (Immediate/T1)")
            appendLine("6. If all clear -> YELLOW (Delayed/T2)")
            appendLine()
            appendLine("Triage categories:")
            for (level in TriageLevel.entries) {
                appendLine("  ${level.code} (${level.color}): ${level.description}")
            }
            appendLine()
            appendLine("Always err on the side of a higher triage level when uncertain.")
            appendLine("Record all observations including vital signs when possible.")
        }
    }
}

/**
 * Triage levels following START/SALT protocol color coding.
 * Maps to EDXL-TEP triage status and FHIR triage observations.
 */
enum class TriageLevel(val code: String, val color: String, val description: String) {
    IMMEDIATE("T1", "red", "Life-threatening, needs immediate treatment"),
    DELAYED("T2", "yellow", "Serious but can wait 1-2 hours"),
    MINIMAL("T3", "green", "Walking wounded, minor injuries"),
    EXPECTANT("T4", "black", "Unlikely to survive given available resources"),
    DEAD("T0", "gray", "No signs of life")
}

/**
 * Represents a triage question in the interactive assessment flow.
 */
data class TriageQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val fieldName: String
)
