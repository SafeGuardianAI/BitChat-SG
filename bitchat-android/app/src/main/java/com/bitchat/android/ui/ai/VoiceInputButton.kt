package com.bitchat.android.ui.ai

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitchat.android.ai.AIChatService
import kotlinx.coroutines.launch

/**
 * Voice Input Button Component
 * 
 * Provides voice input functionality with visual feedback
 */
@Composable
fun VoiceInputButton(
    aiChatService: AIChatService,
    onTranscriptionResult: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Animation for recording state
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .size(56.dp)
            .scale(if (isRecording) scale else 1f)
            .background(
                color = when {
                    isProcessing -> colorScheme.tertiary
                    isRecording -> colorScheme.error
                    else -> colorScheme.primary
                },
                shape = CircleShape
            )
            .clickable(enabled = enabled && !isProcessing) {
                if (isRecording) {
                    // Stop recording
                    isRecording = false
                    isProcessing = true
                    
                    scope.launch {
                        try {
                            val result = aiChatService.processVoiceInput()
                            onTranscriptionResult(result)
                        } catch (e: Exception) {
                            onError("Voice input failed: ${e.message}")
                        } finally {
                            isProcessing = false
                        }
                    }
                } else {
                    // Start recording
                    isRecording = true
                    scope.launch {
                        try {
                            // Start voice input (this will handle the recording internally)
                            val result = aiChatService.processVoiceInput()
                            onTranscriptionResult(result)
                        } catch (e: Exception) {
                            onError("Voice input failed: ${e.message}")
                        } finally {
                            isRecording = false
                            isProcessing = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when {
                isProcessing -> Icons.Default.MicOff
                isRecording -> Icons.Default.MicOff
                else -> Icons.Default.Mic
            },
            contentDescription = when {
                isProcessing -> "Processing voice input"
                isRecording -> "Stop recording"
                else -> "Start voice input"
            },
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Voice Input Status Indicator
 */
@Composable
fun VoiceInputStatus(
    isRecording: Boolean,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    if (isRecording || isProcessing) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Animated dot
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (isRecording) colorScheme.error else colorScheme.tertiary,
                        shape = CircleShape
                    )
            )
            
            Text(
                text = if (isRecording) "Recording..." else "Processing...",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = alpha)
            )
        }
    }
}








