package com.bitchat.android.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ai.AIManager
import com.bitchat.android.ai.DeviceCapabilityService
import com.bitchat.android.ai.ModelCatalog
import com.bitchat.android.ai.ModelInfo
import kotlinx.coroutines.launch

@Composable
fun ModelSelectionScreen(
    modifier: Modifier,
    selectedModelId: String?,
    downloadedModelIds: Set<String>,
    downloadingModelId: String?,
    downloadProgress: Float,
    onSelectAndDownload: (ModelInfo) -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // Load device capability once; null while loading
    var deviceCap by remember { mutableStateOf<DeviceCapabilityService.DeviceCapability?>(null) }
    var featureSet by remember { mutableStateOf<AIManager.FeatureSet?>(null) }
    var benchmarkResult by remember { mutableStateOf<String?>(null) }
    var benchmarking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        deviceCap = DeviceCapabilityService.getInstance(context).getCapability()
        featureSet = AIManager.getInstance(context).getAvailableFeatures()
    }

    // Sort models: compatible first, then may-be-slow, then not-recommended;
    // within each group maintain catalog order.
    val allModels = ModelCatalog.getAllModels()
    val sortedModels = remember(deviceCap) {
        val cap = deviceCap ?: return@remember allModels
        allModels.sortedBy { model ->
            val neededGB = model.fileSizeMB / 1024f * 1.5f
            when {
                neededGB > cap.totalRamGB     -> 2  // not recommended — last
                neededGB > cap.availableRamGB -> 1  // may be slow — middle
                else                          -> 0  // works — first
            }
        }
    }

    val hasDownloadedModel = downloadedModelIds.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "safeguardian",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            ),
            color = colorScheme.onBackground
        )
        Text(
            text = "on-device AI model",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Device capability banner
        deviceCap?.let { cap ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val ramText = "%.1f GB RAM  ·  %.1f GB free".format(cap.totalRamGB, cap.availableRamGB)
                        val tierText = cap.tier.name.lowercase().replaceFirstChar { it.uppercase() }
                        val npuText = if (cap.supportsNPU) " · NPU ✓ (${cap.socInfo.socModel})" else ""
                        Text(
                            text = "$tierText device  ·  $ramText$npuText",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = "Max recommended: ${cap.maxModelParamsBillion}B params · ${cap.recommendedQuantization}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    // Quick benchmark button — only shown when a model is loaded
                    if (AIManager.getInstance(context).aiService.isModelLoaded()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        if (benchmarking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = {
                                    benchmarking = true
                                    scope.launch {
                                        val start = System.currentTimeMillis()
                                        val result = AIManager.getInstance(context).aiService.testModel()
                                        val elapsed = System.currentTimeMillis() - start
                                        benchmarkResult = if (result.isSuccess) {
                                            "${elapsed}ms for 32 tokens (~${32000 / elapsed.coerceAtLeast(1)} tok/s)"
                                        } else {
                                            "benchmark failed"
                                        }
                                        benchmarking = false
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "⚡ test",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }

            // Benchmark result line
            benchmarkResult?.let { bm ->
                Text(
                    text = "⚡ $bm",
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    ),
                    color = Color(0xFF30D158)
                )
            }

            // Degradation warning banner
            featureSet?.degradationReason?.let { reason ->
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFF3B30).copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFF3B30),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = Color(0xFFFF3B30)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        } ?: run {
            // Loading placeholder
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.25f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Detecting device capabilities...",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sortedModels.forEach { model ->
                val isDownloaded = downloadedModelIds.contains(model.id)
                val isDownloading = downloadingModelId == model.id
                val isSelected = selectedModelId == model.id

                val compatLabel = deviceCap?.let { cap ->
                    val neededGB = model.fileSizeMB / 1024f * 1.5f
                    when {
                        neededGB > cap.totalRamGB     -> DeviceCapabilityService.CompatibilityLabel.NOT_RECOMMENDED
                        neededGB > cap.availableRamGB -> DeviceCapabilityService.CompatibilityLabel.MAY_BE_SLOW
                        else                          -> DeviceCapabilityService.CompatibilityLabel.WORKS
                    }
                }

                ModelCard(
                    model = model,
                    isDownloaded = isDownloaded,
                    isDownloading = isDownloading,
                    isSelected = isSelected,
                    downloadProgress = if (isDownloading) downloadProgress else 0f,
                    compatLabel = compatLabel,
                    colorScheme = colorScheme,
                    onSelect = { onSelectAndDownload(model) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Bottom actions
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasDownloadedModel) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary
                        )
                    ) {
                        Text("Continue", fontFamily = FontFamily.Monospace)
                    }
                }

                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (hasDownloadedModel) "Skip model setup" else "Skip for now (no AI features)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    isSelected: Boolean,
    downloadProgress: Float,
    compatLabel: DeviceCapabilityService.CompatibilityLabel?,
    colorScheme: ColorScheme,
    onSelect: () -> Unit
) {
    val borderColor = when {
        isSelected && isDownloaded -> colorScheme.primary
        model.recommended          -> colorScheme.primary.copy(alpha = 0.4f)
        else                       -> colorScheme.outline.copy(alpha = 0.2f)
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (isSelected && isDownloaded) 2.dp else 1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Name row: badges
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = colorScheme.onSurface
                        )
                        if (model.recommended) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Recommended",
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFFFFD700)
                            )
                        }
                        if (model.isNew) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF0A84FF).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "NEW",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFF0A84FF)
                                )
                            }
                        }
                    }

                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Compatibility label
                    compatLabel?.let { label ->
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = label.display,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = Color(label.color)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (model.fileSizeMB >= 1000) "%.1f GB".format(model.fileSizeMB / 1000f)
                               else "${model.fileSizeMB} MB",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    when {
                        isDownloaded -> {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Downloaded",
                                modifier = Modifier.size(24.dp),
                                tint = Color(0xFF4CAF50)
                            )
                        }
                        isDownloading -> {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = colorScheme.primary
                            )
                        }
                        else -> {
                            FilledTonalIconButton(
                                onClick = onSelect,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = "Download",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = colorScheme.primary,
                    trackColor = colorScheme.surfaceVariant
                )
                Text(
                    text = "Downloading... ${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
