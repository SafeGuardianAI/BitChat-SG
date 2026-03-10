package com.bitchat.android.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitchat.android.ai.AIManager
import com.bitchat.android.ai.AIStatus

/**
 * Battery-efficient AI status indicator
 *
 * Simple dot + text, no animations
 * Minimal recomposition
 */
@Composable
fun AIStatusIndicator(
    aiManager: AIManager,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = remember { derivedStateOf { aiManager.getStatus() } }

    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status icon
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = "AI",
            modifier = Modifier.size(20.dp),
            tint = when (status.value) {
                is AIStatus.Ready -> MaterialTheme.colorScheme.primary
                is AIStatus.NoModelLoaded -> MaterialTheme.colorScheme.onSurfaceVariant
                AIStatus.Disabled -> MaterialTheme.colorScheme.outline
            }
        )

        // Status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (status.value) {
                        is AIStatus.Ready -> MaterialTheme.colorScheme.primary
                        is AIStatus.NoModelLoaded -> MaterialTheme.colorScheme.tertiary
                        AIStatus.Disabled -> MaterialTheme.colorScheme.outline
                    }
                )
        )

        // Status text
        Text(
            text = when (val s = status.value) {
                is AIStatus.Ready -> "AI Ready"
                is AIStatus.NoModelLoaded -> "No Model"
                AIStatus.Disabled -> "AI Off"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (status.value) {
                is AIStatus.Ready -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * Compact badge version (just dot + icon)
 */
@Composable
fun AIStatusBadge(
    aiManager: AIManager,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = remember { derivedStateOf { aiManager.getStatus() } }

    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Badge(
            containerColor = when (status.value) {
                is AIStatus.Ready -> MaterialTheme.colorScheme.primary
                is AIStatus.NoModelLoaded -> MaterialTheme.colorScheme.tertiary
                AIStatus.Disabled -> MaterialTheme.colorScheme.outline
            }
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "AI Status",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
