package com.bitchat.android.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ai.*
import kotlinx.coroutines.launch

/**
 * Battery-efficient Model Manager UI
 *
 * Design principles:
 * - LazyColumn with stable keys
 * - Simple icons, no animations
 * - Clear visual hierarchy
 * - Minimal recomposition
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerSheet(
    aiManager: AIManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val downloadStates by aiManager.downloadStates.collectAsState()
    val storageInfo = remember { aiManager.modelManager.getStorageInfo() }

    // Group visible models by type
    val allModels = remember { ModelCatalog.getAllModels(includeHidden = false) }
    val llmModels = remember { allModels.filter { it.type == ModelType.LLM } }
    val npuModels = remember { allModels.filter { it.type == ModelType.NPU } }
    val embeddingModels = remember { allModels.filter { it.type == ModelType.EMBEDDING } }
    val asrModels = remember { allModels.filter { it.type == ModelType.ASR } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "AI Models",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Storage info
            StorageInfoCard(storageInfo)

            Spacer(modifier = Modifier.height(16.dp))

            // Model list grouped by type
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LLM Models
                if (llmModels.isNotEmpty()) {
                    item(key = "header_llm") {
                        SectionHeader("LLM Models")
                    }
                    items(items = llmModels, key = { it.id }) { model ->
                        ModelCardItem(model, downloadStates, aiManager, scope)
                    }
                }

                // NPU Models
                if (npuModels.isNotEmpty()) {
                    item(key = "header_npu") {
                        SectionHeader("NPU Models (Qualcomm)")
                    }
                    items(items = npuModels, key = { it.id }) { model ->
                        ModelCardItem(model, downloadStates, aiManager, scope)
                    }
                }

                // Embedding Models
                if (embeddingModels.isNotEmpty()) {
                    item(key = "header_embedding") {
                        SectionHeader("Embedding Models")
                    }
                    items(items = embeddingModels, key = { it.id }) { model ->
                        ModelCardItem(model, downloadStates, aiManager, scope)
                    }
                }

                // ASR Models
                if (asrModels.isNotEmpty()) {
                    item(key = "header_asr") {
                        SectionHeader("Speech Recognition")
                    }
                    items(items = asrModels, key = { it.id }) { model ->
                        ModelCardItem(model, downloadStates, aiManager, scope)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Close button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ModelCardItem(
    model: AIModel,
    downloadStates: Map<String, DownloadState>,
    aiManager: AIManager,
    scope: kotlinx.coroutines.CoroutineScope
) {
    ModelCard(
        model = model,
        downloadState = downloadStates[model.id] ?: DownloadState.NotStarted,
        isDownloaded = aiManager.modelManager.isModelDownloaded(model),
        onDownload = {
            scope.launch {
                aiManager.modelManager.downloadModel(model)
            }
        },
        onDelete = {
            aiManager.modelManager.deleteModel(model)
        },
        onLoad = {
            scope.launch {
                aiManager.aiService.loadModel(model)
            }
        }
    )
}

@Composable
private fun StorageInfoCard(storageInfo: ModelManager.StorageInfo) {
    val amber = Color(0xFFFFB300)
    val darkBg = Color(0xFF1A1A1A)
    val pct = storageInfo.usedPercentage.toInt().coerceIn(0, 100)
    val filled = pct / 5
    val empty = 20 - filled
    val bar = "\u2588".repeat(filled) + "\u2591".repeat(empty)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = darkBg),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "STORAGE [$bar] $pct%",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = amber
            )
            Text(
                text = "${storageInfo.usedByModelsMB}MB used / ${storageInfo.availableSpaceMB}MB free",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = amber.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: AIModel,
    downloadState: DownloadState,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onLoad: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Model name and size
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${model.fileSizeMB}MB • ${model.parameterCount} params",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Battery impact badge
                BatteryImpactBadge(model.batteryImpact)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Download state / Actions
            when {
                downloadState is DownloadState.Downloading -> {
                    DownloadProgress(downloadState)
                }
                isDownloaded -> {
                    DownloadedActions(onLoad, onDelete)
                }
                else -> {
                    DownloadButton(onDownload)
                }
            }
        }
    }
}

@Composable
private fun BatteryImpactBadge(impact: BatteryImpact) {
    val color = when (impact) {
        BatteryImpact.LOW -> MaterialTheme.colorScheme.primary
        BatteryImpact.MEDIUM -> MaterialTheme.colorScheme.tertiary
        BatteryImpact.HIGH -> MaterialTheme.colorScheme.error
    }

    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = when (impact) {
                BatteryImpact.LOW -> "Low"
                BatteryImpact.MEDIUM -> "Med"
                BatteryImpact.HIGH -> "High"
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun DownloadProgress(state: DownloadState.Downloading) {
    val amber = Color(0xFFFFB300)
    val darkBg = Color(0xFF1A1A1A)
    val pct = state.progressPercent.toInt()
    val filled = pct / 5         // 0-20 blocks
    val empty = 20 - filled
    val bar = "\u2588".repeat(filled) + "\u2591".repeat(empty)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(darkBg)
            .border(1.dp, amber.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Text(
            text = "DOWNLOADING...",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = amber.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "[$bar] $pct%",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = amber,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "${state.downloadedMB}MB / ${state.totalMB}MB",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = amber.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun DownloadedActions(
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = onLoad,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Load")
        }

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Delete")
        }
    }
}

@Composable
private fun DownloadButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Download")
    }
}
