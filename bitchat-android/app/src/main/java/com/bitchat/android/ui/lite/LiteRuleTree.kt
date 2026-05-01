package com.bitchat.android.ui.lite

import com.bitchat.android.standards.CAPCategory
import com.bitchat.android.standards.CAPSeverity
import com.bitchat.android.standards.CAPUrgency

/**
 * Rule-based decision tree used by Lite mode in place of free-text + LLM.
 *
 * A leaf is reached after at most [LiteRuleTree.MAX_DEPTH] taps and produces
 * an [Outcome] that maps cleanly to a CAP v1.2 alert.
 */
data class LiteNode(
    val id: String,
    val title: String,
    val icon: String,
    val children: List<LiteNode> = emptyList(),
    val outcome: Outcome? = null
) {
    val isLeaf: Boolean get() = outcome != null

    data class Outcome(
        val event: String,
        val headline: String,
        val description: String,
        val instruction: String,
        val category: CAPCategory,
        val severity: CAPSeverity,
        val urgency: CAPUrgency
    )
}

object LiteRuleTree {
    const val MAX_DEPTH = 3

    val root: LiteNode = LiteNode(
        id = "root",
        title = "What is happening?",
        icon = "help",
        children = listOf(
            // ---- I'M SAFE ----
            LiteNode(
                id = "safe",
                title = "I'm safe",
                icon = "check_circle",
                outcome = LiteNode.Outcome(
                    event = "Wellness Check",
                    headline = "I'm safe",
                    description = "User reports no emergency.",
                    instruction = "No action required.",
                    category = CAPCategory.SAFETY,
                    severity = CAPSeverity.MINOR,
                    urgency = CAPUrgency.PAST
                )
            ),

            // ---- NEED HELP ----
            LiteNode(
                id = "help",
                title = "I need help",
                icon = "support",
                children = listOf(
                    LiteNode(
                        id = "help_water",
                        title = "Need water",
                        icon = "water_drop",
                        outcome = LiteNode.Outcome(
                            event = "Resource Request",
                            headline = "Drinking water needed",
                            description = "User requests potable water at their location.",
                            instruction = "Bring drinking water.",
                            category = CAPCategory.SAFETY,
                            severity = CAPSeverity.MODERATE,
                            urgency = CAPUrgency.EXPECTED
                        )
                    ),
                    LiteNode(
                        id = "help_food",
                        title = "Need food",
                        icon = "restaurant",
                        outcome = LiteNode.Outcome(
                            event = "Resource Request",
                            headline = "Food needed",
                            description = "User requests food at their location.",
                            instruction = "Bring non-perishable food.",
                            category = CAPCategory.SAFETY,
                            severity = CAPSeverity.MODERATE,
                            urgency = CAPUrgency.EXPECTED
                        )
                    ),
                    LiteNode(
                        id = "help_warmth",
                        title = "Cold / shelter",
                        icon = "ac_unit",
                        outcome = LiteNode.Outcome(
                            event = "Shelter Request",
                            headline = "Shelter or warmth needed",
                            description = "User exposed to cold, requests shelter or blankets.",
                            instruction = "Provide shelter and warm clothing.",
                            category = CAPCategory.SAFETY,
                            severity = CAPSeverity.SEVERE,
                            urgency = CAPUrgency.IMMEDIATE
                        )
                    ),
                    LiteNode(
                        id = "help_lost",
                        title = "I'm lost",
                        icon = "explore_off",
                        outcome = LiteNode.Outcome(
                            event = "Lost Person",
                            headline = "User cannot find their way",
                            description = "User reports they are disoriented or lost.",
                            instruction = "Locate user and guide to safety.",
                            category = CAPCategory.RESCUE,
                            severity = CAPSeverity.MODERATE,
                            urgency = CAPUrgency.EXPECTED
                        )
                    )
                )
            ),

            // ---- INJURED ----
            LiteNode(
                id = "injured",
                title = "I'm injured",
                icon = "healing",
                children = listOf(
                    LiteNode(
                        id = "injured_walk_yes",
                        title = "I can walk",
                        icon = "directions_walk",
                        outcome = LiteNode.Outcome(
                            event = "Minor Injury",
                            headline = "Injured but ambulatory",
                            description = "User reports an injury and can still walk.",
                            instruction = "Provide first aid; transport may be needed.",
                            category = CAPCategory.HEALTH,
                            severity = CAPSeverity.MODERATE,
                            urgency = CAPUrgency.EXPECTED
                        )
                    ),
                    LiteNode(
                        id = "injured_walk_no",
                        title = "I cannot walk",
                        icon = "wheelchair_pickup",
                        outcome = LiteNode.Outcome(
                            event = "Serious Injury",
                            headline = "Injured and immobilised",
                            description = "User reports an injury and cannot walk.",
                            instruction = "Send rescue with stretcher or wheelchair.",
                            category = CAPCategory.RESCUE,
                            severity = CAPSeverity.SEVERE,
                            urgency = CAPUrgency.IMMEDIATE
                        )
                    ),
                    LiteNode(
                        id = "injured_bleeding",
                        title = "I'm bleeding",
                        icon = "bloodtype",
                        outcome = LiteNode.Outcome(
                            event = "Hemorrhage",
                            headline = "Active bleeding reported",
                            description = "User reports active bleeding requiring care.",
                            instruction = "Apply pressure; dispatch medical aid.",
                            category = CAPCategory.HEALTH,
                            severity = CAPSeverity.SEVERE,
                            urgency = CAPUrgency.IMMEDIATE
                        )
                    )
                )
            ),

            // ---- MEDICAL ----
            LiteNode(
                id = "medical",
                title = "Medical emergency",
                icon = "medical_services",
                children = listOf(
                    LiteNode(
                        id = "medical_chest",
                        title = "Chest pain",
                        icon = "favorite",
                        outcome = LiteNode.Outcome(
                            event = "Cardiac Event",
                            headline = "Possible cardiac emergency",
                            description = "User reports chest pain.",
                            instruction = "Dispatch EMS immediately; keep user still.",
                            category = CAPCategory.HEALTH,
                            severity = CAPSeverity.EXTREME,
                            urgency = CAPUrgency.IMMEDIATE
                        )
                    ),
                    LiteNode(
                        id = "medical_breath",
                        title = "Cannot breathe",
                        icon = "air",
                        outcome = LiteNode.Outcome(
                            event = "Respiratory Distress",
                            headline = "Respiratory distress",
                            description = "User reports difficulty breathing.",
                            instruction = "Dispatch EMS immediately; keep airway clear.",
                            category = CAPCategory.HEALTH,
                            severity = CAPSeverity.EXTREME,
                            urgency = CAPUrgency.IMMEDIATE
                        )
                    ),
                    LiteNode(
                        id = "medical_meds",
                        title = "Need my medicine",
                        icon = "medication",
                        outcome = LiteNode.Outcome(
                            event = "Medication Need",
                            headline = "Medication required",
                            description = "User cannot reach essential medication.",
                            instruction = "Bring user's medication or transport to pharmacy.",
                            category = CAPCategory.HEALTH,
                            severity = CAPSeverity.SEVERE,
                            urgency = CAPUrgency.IMMEDIATE
                        )
                    )
                )
            ),

            // ---- SOS ----
            LiteNode(
                id = "sos",
                title = "SOS — danger",
                icon = "sos",
                outcome = LiteNode.Outcome(
                    event = "SOS",
                    headline = "Immediate life-threatening danger",
                    description = "User triggered SOS.",
                    instruction = "Dispatch emergency response immediately.",
                    category = CAPCategory.RESCUE,
                    severity = CAPSeverity.EXTREME,
                    urgency = CAPUrgency.IMMEDIATE
                )
            )
        )
    )
}
