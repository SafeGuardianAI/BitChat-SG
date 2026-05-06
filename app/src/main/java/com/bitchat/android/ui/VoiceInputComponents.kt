package com.bitchat.android.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.bitchat.android.ai.AIManager
import com.bitchat.android.ai.ASRService
import com.bitchat.android.ai.AIModelCatalog
import com.bitchat.android.ai.AsrAudioRecorder
import com.bitchat.android.features.voice.VoiceRecorder
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VoiceRecordButton(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onStart: () -> Unit,
    onAmplitude: (amplitude: Int, elapsedMs: Long) -> Unit,
    onFinish: (filePath: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var recordedFilePath by remember { mutableStateOf<String?>(null) }
    var recordingStart by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()
    var ampJob by remember { mutableStateOf<Job?>(null) }

    // Ensure latest callbacks are used inside gesture coroutine
    val latestOnStart = rememberUpdatedState(onStart)
    val latestOnAmplitude = rememberUpdatedState(onAmplitude)
    val latestOnFinish = rememberUpdatedState(onFinish)

    Box(
        modifier = modifier
            .size(32.dp)
            .background(backgroundColor, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!isRecording) {
                            if (micPermission.status !is PermissionStatus.Granted) {
                                micPermission.launchPermissionRequest()
                                return@detectTapGestures
                            }
                            val rec = VoiceRecorder(context)
                            val f = rec.start()
                            recorder = rec
                            isRecording = f != null
                            recordedFilePath = f?.absolutePath
                            recordingStart = System.currentTimeMillis()
                            if (isRecording) {
                                latestOnStart.value()
                                // Haptic "knock" when recording starts
                                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                // Start amplitude polling loop
                                ampJob?.cancel()
                                ampJob = scope.launch {
                                    while (isActive && isRecording) {
                                        val amp = recorder?.pollAmplitude() ?: 0
                                        val elapsedMs = (System.currentTimeMillis() - recordingStart).coerceAtLeast(0L)
                                        latestOnAmplitude.value(amp, elapsedMs)
                                        // Auto-stop after 10 seconds
                                        if (elapsedMs >= 10_000 && isRecording) {
                                            val file = recorder?.stop()
                                            isRecording = false
                                            recorder = null
                                            val path = file?.absolutePath
                                            if (!path.isNullOrBlank()) {
                                                // Haptic "knock" on auto stop
                                                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                                latestOnFinish.value(path)
                                            }
                                            break
                                        }
                                        delay(80)
                                    }
                                }
                            }
                        }
                        try {
                            awaitRelease()
                        } finally {
                            if (isRecording) {
                                // Extend recording for 500ms after release to avoid clipping
                                delay(500)
                            }
                            if (isRecording) {
                                val file = recorder?.stop()
                                isRecording = false
                                recorder = null
                                val path = (file?.absolutePath ?: recordedFilePath)
                                recordedFilePath = null
                                if (!path.isNullOrBlank()) {
                                    // Haptic "knock" when recording stops
                                    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                    latestOnFinish.value(path)
                                }
                            }
                            ampJob?.cancel()
                            ampJob = null
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Record voice note",
            tint = Color.Black,
            modifier = Modifier.size(20.dp)
        )
    }
}

private enum class AsrButtonState { IDLE, RECORDING, TRANSCRIBING }

/**
 * Press-and-hold to record, release to transcribe.
 * Records 16 kHz WAV via [AsrAudioRecorder], transcribes with [ASRService] on release,
 * then calls [onTranscription] to populate the text field.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AsrRecordButton(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    language: String = "en",
    onTranscription: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(AsrButtonState.IDLE) }
    val recorder = remember { AsrAudioRecorder(context) }
    val asrService = remember { ASRService(context) }

    val latestOnTranscription = rememberUpdatedState(onTranscription)

    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecording) {
                recorder.stopRecording()
                AIManager.getInstance(context).setAsrActive(false)
            }
            asrService.release()
        }
    }

    val bg = when (state) {
        AsrButtonState.IDLE         -> backgroundColor
        AsrButtonState.RECORDING    -> Color(0xFFFF3B30).copy(alpha = 0.85f)
        AsrButtonState.TRANSCRIBING -> Color(0xFF8E8E93).copy(alpha = 0.85f)
    }

    Box(
        modifier = modifier
            .size(32.dp)
            .background(bg, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (state == AsrButtonState.TRANSCRIBING) return@detectTapGestures
                        if (micPermission.status !is PermissionStatus.Granted) {
                            micPermission.launchPermissionRequest()
                            return@detectTapGestures
                        }
                        val file = recorder.startRecording() ?: return@detectTapGestures
                        AIManager.getInstance(context).setAsrActive(true)
                        state = AsrButtonState.RECORDING
                        try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}

                        try { awaitRelease() } finally {
                            if (recorder.isRecording) {
                                // Brief tail to avoid clipping the last word
                                delay(300)
                                val recorded = recorder.stopRecording()
                                AIManager.getInstance(context).setAsrActive(false)
                                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                state = AsrButtonState.TRANSCRIBING
                                scope.launch {
                                    val text = recorded?.let { asrService.transcribeFile(it, language = language) }
                                    try { recorded?.delete() } catch (_: Exception) {}
                                    state = AsrButtonState.IDLE
                                    if (!text.isNullOrBlank()) latestOnTranscription.value(text)
                                }
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            AsrButtonState.IDLE -> Icon(
                imageVector = Icons.Filled.RecordVoiceOver,
                contentDescription = "Hold to transcribe speech",
                tint = Color.Black,
                modifier = Modifier.size(18.dp),
            )
            AsrButtonState.RECORDING -> Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Recording — release to transcribe",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            AsrButtonState.TRANSCRIBING -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }
    }
}
