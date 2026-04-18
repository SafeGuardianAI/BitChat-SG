package com.bitchat.android.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ai.AIChatService
import com.bitchat.android.ai.AIManager
import com.bitchat.android.ai.ASRService
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import androidx.compose.material3.ColorScheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat

private sealed interface ScribeState {
    object Idle : ScribeState
    object Transcribing : ScribeState
    data class Done(val report: String) : ScribeState
}

@Composable
fun AudioMessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val path = message.content.trim()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Derive sending progress if applicable
    val (overrideProgress, overrideColor) = when (val st = message.deliveryStatus) {
        is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered -> {
            if (st.total > 0 && st.reached < st.total) {
                (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5)
            } else null to null
        }
        else -> null to null
    }

    var scribeState by remember { mutableStateOf<ScribeState>(ScribeState.Idle) }
    val asrAvailable = remember { ASRService.isModelDownloaded(context) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Header: nickname + timestamp
        val headerText = com.bitchat.android.ui.formatMessageHeaderAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = headerText,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onSurface,
            modifier = Modifier.pointerInput(message.id) {
                detectTapGestures(onTap = { pos ->
                    val layout = headerLayout ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(pos)
                    val ann = headerText.getStringAnnotations("nickname_click", offset, offset)
                    if (ann.isNotEmpty() && onNicknameClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNicknameClick.invoke(ann.first().item)
                    }
                }, onLongPress = { onMessageLongPress?.invoke(message) })
            },
            onTextLayout = { headerLayout = it }
        )

        // Player row + scribe button
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                VoiceNotePlayer(
                    path = path,
                    progressOverride = overrideProgress,
                    progressColor = overrideColor
                )
            }

            // Scribe button — only when not currently sending
            val isDelivering = message.deliveryStatus is
                com.bitchat.android.model.DeliveryStatus.PartiallyDelivered
            if (!isDelivering) {
                Spacer(Modifier.width(6.dp))
                when (val state = scribeState) {
                    is ScribeState.Transcribing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = colorScheme.primary
                        )
                    }
                    is ScribeState.Done -> {
                        // Tap again to collapse/re-run
                        Text(
                            text = "✦",
                            fontSize = 14.sp,
                            color = colorScheme.primary,
                            modifier = Modifier.clickable {
                                scribeState = ScribeState.Idle
                            }
                        )
                    }
                    is ScribeState.Idle -> {
                        Text(
                            text = if (asrAvailable) "⊙ scribe" else "⊙",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier
                                .clickable {
                                    scribeState = ScribeState.Transcribing
                                    scope.launch {
                                        val aiChatService = AIChatService(
                                            context,
                                            AIManager.getInstance(context)
                                        )
                                        val result = aiChatService.processAudioNote(path)
                                        scribeState = ScribeState.Done(result)
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Cancel button while sending
            val showCancel = message.sender == currentUserNickname &&
                (message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered)
            if (showCancel) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                        .clickable { onCancelTransfer?.invoke(message) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Scribe result panel — animated slide-in below the player
        AnimatedVisibility(
            visible = scribeState is ScribeState.Done,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            (scribeState as? ScribeState.Done)?.let { done ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⊙ scribe",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary
                            )
                            Text(
                                text = "✕",
                                fontSize = 11.sp,
                                color = colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.clickable { scribeState = ScribeState.Idle }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = done.report,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.85f),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}
