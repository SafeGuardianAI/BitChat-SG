package com.bitchat.android.ai

/**
 * Modes for structured output generation
 */
enum class StructuredOutputMode {
    /** No structured output */
    OFF,
    /** Use prompt-based structured output */
    PROMPT,
    /** Use grammar-based structured output (GBNF) */
    GRAMMAR
}
