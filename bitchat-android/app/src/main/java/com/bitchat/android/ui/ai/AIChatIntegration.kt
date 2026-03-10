package com.bitchat.android.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitchat.android.ai.AIChatService
import com.bitchat.android.ai.AIStatus
import kotlinx.coroutines.launch

/**
 * AI Chat Integration Component
 * 
 * Provides a complete AI chat interface with text and voice input
 */
@Composable
fun AIChatIntegration(
    aiChatService: AIChatService,
    channelId: String? = null,
    modifier: Modifier = Modifier
) {
    var messageText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var aiResponse by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val colorScheme = MaterialTheme.colorScheme

    // AI Status
    val aiStatus = remember { aiChatService.getAIStatus() }
    val isAIReady = aiStatus is AIStatus.Ready

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // AI Status Indicator
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (aiStatus) {
                    is AIStatus.Ready -> colorScheme.primaryContainer
                    is AIStatus.NoModelLoaded -> colorScheme.errorContainer
                    is AIStatus.Disabled -> colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when (aiStatus) {
                        is AIStatus.Ready -> "🤖 AI Ready (${aiStatus.modelId})"
                        is AIStatus.NoModelLoaded -> "⚠️ AI Model Not Loaded"
                        is AIStatus.Disabled -> "🚫 AI Disabled"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Chat Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (aiResponse.isNotEmpty()) {
                item {
                    AIChatMessage(
                        message = aiResponse,
                        isFromAI = true
                    )
                }
            }
            
            if (errorMessage.isNotEmpty()) {
                item {
                    AIChatMessage(
                        message = "Error: $errorMessage",
                        isFromAI = false,
                        isError = true
                    )
                }
            }
        }

        // Input Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Text Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask AI anything...") },
                        enabled = isAIReady && !isProcessing,
                        maxLines = 3
                    )
                    
                    // Send Button
                    Button(
                        onClick = {
                            if (messageText.isNotBlank() && isAIReady && !isProcessing) {
                                scope.launch {
                                    isProcessing = true
                                    errorMessage = ""
                                    aiResponse = ""
                                    
                                    try {
                                        // Use streaming for progressive token generation
                                        aiChatService.streamResponse(
                                            messageText,
                                            channelId,
                                            useRAG = true
                                        ).collect { token ->
                                            aiResponse += token
                                        }
                                        messageText = ""
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Unknown error"
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            }
                        },
                        enabled = isAIReady && !isProcessing && messageText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }

                // Voice Input Section
                if (aiChatService.isVoiceInputAvailable()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VoiceInputButton(
                            aiChatService = aiChatService,
                            onTranscriptionResult = { result ->
                                scope.launch {
                                    isProcessing = true
                                    errorMessage = ""
                                    aiResponse = ""
                                    
                                    try {
                                        aiResponse = result
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Voice processing error"
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            onError = { error ->
                                errorMessage = error
                            },
                            enabled = isAIReady && !isProcessing
                        )
                        
                        Text(
                            text = "Tap to speak",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Text(
                        text = "Voice input not available (check permissions and ASR settings)",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * AI Chat Message Component
 */
@Composable
fun AIChatMessage(
    message: String,
    isFromAI: Boolean,
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromAI) Arrangement.Start else Arrangement.End
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isError -> colorScheme.errorContainer
                    isFromAI -> colorScheme.primaryContainer
                    else -> colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = if (isFromAI) "🤖 AI" else "👤 You",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isError -> colorScheme.onErrorContainer
                        isFromAI -> colorScheme.onPrimaryContainer
                        else -> colorScheme.onSurfaceVariant
                    }
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isError -> colorScheme.onErrorContainer
                        isFromAI -> colorScheme.onPrimaryContainer
                        else -> colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}








