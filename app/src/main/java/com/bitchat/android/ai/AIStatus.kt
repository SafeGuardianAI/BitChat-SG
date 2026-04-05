package com.bitchat.android.ai

data class AIStatus(
    val isReady: Boolean = false,
    val modelLoaded: Boolean = false,
    val modelName: String? = null,
    val ragReady: Boolean = false,
    val asrReady: Boolean = false,
    val ttsEnabled: Boolean = false
)
