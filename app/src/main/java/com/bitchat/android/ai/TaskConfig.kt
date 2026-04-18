package com.bitchat.android.ai

/**
 * Task-specific inference configuration, separating prompt templates from
 * inference engine parameters.
 *
 * Inspired by Google AI Edge Gallery's per-task config pattern:
 * triage needs 50–100 tokens and constrained stop words; general chat
 * needs 512–1024 tokens and no stop words.
 *
 * Each [TaskConfig] can be passed directly to [AIService.generateResponse]
 * so inference code never needs to know which task it's serving.
 */
data class TaskConfig(
    /** Task identifier for logging and analytics. */
    val taskId: String,
    /** System prompt injected into the LLM context. */
    val systemPrompt: String,
    /** Maximum tokens to generate. Shorter = faster + less battery. */
    val maxTokens: Int,
    /**
     * Stop word tokens.  Generation halts when any of these appear.
     * Use for constrained structured output (triage tags, JSON keys).
     */
    val stopWords: List<String> = emptyList(),
    /** Sampling temperature. Lower = more deterministic (better for structured output). */
    val temperature: Float = 0.7f,
    /**
     * Output schema hint for structured tasks.
     * Null = free-form text; non-null = JSON schema description passed in system prompt.
     */
    val outputSchemaHint: String? = null
) {
    companion object {

        // ── General chat ─────────────────────────────────────────────────────
        val GENERAL_CHAT = TaskConfig(
            taskId = "general_chat",
            systemPrompt = """
                You are SafeGuardian AI, an emergency response assistant running entirely on-device.
                You help first responders and civilians during disaster situations.
                Be concise, accurate, and prioritize life safety information.
                If unsure, say so — never fabricate medical or safety information.
            """.trimIndent(),
            maxTokens = 1024,
            temperature = 0.7f
        )

        // ── START Triage ─────────────────────────────────────────────────────
        val TRIAGE = TaskConfig(
            taskId = "triage",
            systemPrompt = """
                You are a START triage assistant. Classify patients as:
                - RED (immediate): life-threatening but survivable
                - YELLOW (delayed): serious but not immediately life-threatening
                - GREEN (minor): walking wounded, minor injuries
                - BLACK (expectant): deceased or unsurvivable injuries

                Respond with ONLY the tag word (RED, YELLOW, GREEN, or BLACK),
                followed by a single brief reason sentence. No other text.
                Format: TAG — reason
            """.trimIndent(),
            maxTokens = 80,
            stopWords = listOf("RED", "YELLOW", "GREEN", "BLACK", "\n\n"),
            temperature = 0.2f,   // Low temperature for deterministic classification
            outputSchemaHint = """{"tag": "RED|YELLOW|GREEN|BLACK", "reason": "string"}"""
        )

        // ── Damage assessment ────────────────────────────────────────────────
        val DAMAGE_REPORT = TaskConfig(
            taskId = "damage_report",
            systemPrompt = """
                You are a structural damage assessment assistant.
                Describe damage severity in one of three levels: MINOR, MODERATE, SEVERE.
                Then provide a 2–3 sentence assessment covering:
                1. Visible structural damage
                2. Estimated victim risk
                3. Recommended immediate action
                Keep your total response under 100 words.
            """.trimIndent(),
            maxTokens = 200,
            stopWords = listOf("\n\n\n"),
            temperature = 0.4f
        )

        // ── Resource request ─────────────────────────────────────────────────
        val RESOURCE_REQUEST = TaskConfig(
            taskId = "resource_request",
            systemPrompt = """
                You are a disaster resource coordination assistant.
                Generate a structured resource request message in plain text.
                Include: location, resource type, quantity, priority (HIGH/MEDIUM/LOW).
                Keep it under 60 words. Use radio-friendly language.
            """.trimIndent(),
            maxTokens = 120,
            temperature = 0.3f
        )

        // ── Situation summary ────────────────────────────────────────────────
        val SITUATION_SUMMARY = TaskConfig(
            taskId = "situation_summary",
            systemPrompt = """
                Summarize the provided situation report for radio broadcast.
                Use plain language, no jargon.
                Structure: (1) What happened. (2) Current status. (3) Immediate needs.
                Keep the summary under 80 words.
            """.trimIndent(),
            maxTokens = 160,
            temperature = 0.4f
        )

        // ── Emergency translation ────────────────────────────────────────────
        val TRANSLATION = TaskConfig(
            taskId = "translation",
            systemPrompt = """
                You are an emergency interpreter. Translate the provided text accurately.
                Preserve all medical and safety terminology precisely.
                Provide only the translation with no commentary or explanation.
            """.trimIndent(),
            maxTokens = 512,
            temperature = 0.1f   // Very low temperature for faithful translation
        )

        // ── Telemetry interpretation ──────────────────────────────────────────
        val TELEMETRY_ANALYSIS = TaskConfig(
            taskId = "telemetry_analysis",
            systemPrompt = """
                You are analyzing on-device sensor telemetry from a SafeGuardian node.
                Given the sensor data, identify any anomalies or emergency indicators.
                Respond in 1–2 sentences only. If no anomalies, respond with "NOMINAL".
            """.trimIndent(),
            maxTokens = 100,
            stopWords = listOf("NOMINAL", "\n\n"),
            temperature = 0.3f
        )

        // ── Audio Scribe ─────────────────────────────────────────────────────
        val AUDIO_SCRIBE = TaskConfig(
            taskId = "audio_scribe",
            systemPrompt = """
                You are a field scribe for emergency responders. You receive raw speech
                transcripts from personnel in the field — expect noise, fragments, and
                radio-style clipped speech.

                Produce a concise structured report with three sections:

                TRANSCRIPT — clean up the raw text (fix obvious errors, punctuate),
                             preserve the original meaning exactly.
                KEY FACTS  — bullet list of actionable information: locations, casualty
                             counts, resource needs, hazards.
                NEXT STEPS — 1–3 immediate actions based on what was reported.

                Use plain radio-friendly language. Mark unclear words as [?].
                Keep the total response under 200 words.
            """.trimIndent(),
            maxTokens = 400,
            temperature = 0.3f
        )

        /** Returns the appropriate [TaskConfig] for a given command keyword. */
        fun forCommand(command: String): TaskConfig = when (command.lowercase()) {
            "triage", "/triage"           -> TRIAGE
            "damage", "/damage"           -> DAMAGE_REPORT
            "resource", "/resource"       -> RESOURCE_REQUEST
            "summary", "/summary"         -> SITUATION_SUMMARY
            "translate", "/translate"     -> TRANSLATION
            "telemetry", "/telemetry"     -> TELEMETRY_ANALYSIS
            else                          -> GENERAL_CHAT
        }
    }
}
