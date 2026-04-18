package com.bitchat.android.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Detects device hardware capabilities to guide model selection and feature gating.
 *
 * Logic ported from off-grid-mobile-ai's HardwareService (hardware.ts) — adapted
 * for native Android (no React Native DeviceInfo bridge; uses Android APIs directly).
 *
 * Key outputs:
 * - SoC vendor + Qualcomm QNN tier (for NPU plugin selection in AIService)
 * - RAM-based max parameter budget (for ModelSelectionScreen filtering)
 * - Per-quantization bit-width estimation (for accurate RAM display)
 * - Device tier (LOW/MEDIUM/HIGH/FLAGSHIP) for graceful degradation
 */
class DeviceCapabilityService(private val context: Context) {

    companion object {
        private const val TAG = "DeviceCapabilityService"

        // Qualcomm SM numbers mapped to QNN NPU tiers.
        // Source: local-dream Model.kt getChipsetSuffix(), mirrored in off-grid hardware.ts
        private val FLAGSHIP_8GEN2 = setOf(8550, 8650, 8735, 8750, 8845, 8850)
        private val FLAGSHIP_8GEN1 = setOf(8450, 8475)

        // Bits-per-weight for each quantization scheme.
        // Source: off-grid hardware.ts getQuantizationBits()
        val QUANT_BITS = mapOf(
            "Q2_K"   to 2.625f,
            "Q3_K_S" to 3.4375f,
            "Q3_K_M" to 3.4375f,
            "Q4_0"   to 4.0f,
            "Q4_K_S" to 4.5f,
            "Q4_K_M" to 4.5f,
            "IQ4_NL" to 4.5f,
            "Q5_K_S" to 5.5f,
            "Q5_K_M" to 5.5f,
            "Q6_K"   to 6.5f,
            "Q8_0"   to 8.0f,
            "F16"    to 16.0f
        )

        // RAM thresholds → max model parameter count (billions).
        // Source: off-grid constants/models.ts MODEL_RECOMMENDATIONS
        private val RAM_TO_PARAMS = listOf(
            Triple(3f,  4f,  1.5f),
            Triple(4f,  6f,  3.0f),
            Triple(6f,  8f,  4.0f),
            Triple(8f,  12f, 8.0f),
            Triple(12f, 16f, 13.0f),
            Triple(16f, Float.MAX_VALUE, 30.0f)
        )

        @Volatile
        private var instance: DeviceCapabilityService? = null

        fun getInstance(context: Context): DeviceCapabilityService =
            instance ?: synchronized(this) {
                instance ?: DeviceCapabilityService(context.applicationContext).also { instance = it }
            }
    }

    // ─── Public types ────────────────────────────────────────────────────────

    enum class QnnTier { GEN2, GEN1, MIN }

    enum class SoCVendor { QUALCOMM, MEDIATEK, EXYNOS, TENSOR, UNKNOWN }

    enum class DeviceTier {
        LOW,       // < 4 GB RAM — only sub-2B models
        MEDIUM,    // 4–6 GB RAM — up to 3B models
        HIGH,      // 6–8 GB RAM — up to 4B models
        FLAGSHIP   // 8+ GB RAM — up to 8B+ models
    }

    data class SoCInfo(
        val vendor: SoCVendor,
        val socModel: String,
        val qnnTier: QnnTier?   // non-null only for Qualcomm
    )

    data class DeviceCapability(
        val totalRamGB: Float,
        val availableRamGB: Float,
        val tier: DeviceTier,
        val socInfo: SoCInfo,
        val supportsNPU: Boolean,       // Qualcomm QNN NPU available
        val maxModelParamsBillion: Float,
        val recommendedQuantization: String
    )

    // ─── Internal state ───────────────────────────────────────────────────────

    @Volatile private var cached: DeviceCapability? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns device capability, caching the result after the first call.
     * Suspend because RAM reads and cpuinfo parsing may block.
     */
    suspend fun getCapability(): DeviceCapability {
        cached?.let { return it }
        return withContext(Dispatchers.IO) { buildCapability() }
    }

    /** Non-suspending version — returns cached value or a safe default if not yet loaded. */
    fun getCapabilityCached(): DeviceCapability? = cached

    /**
     * True if [model] will fit in available RAM (requires 1.5× file size free).
     * Mirrors off-grid's canRunModel() safety factor.
     */
    suspend fun canRunModel(model: ModelInfo): Boolean {
        val cap = getCapability()
        val neededGB = model.fileSizeMB / 1024f * 1.5f
        return cap.availableRamGB >= neededGB
    }

    /**
     * UI compatibility label for a model — matches off-grid tier UX.
     * "Works on this device" / "May be slow" / "Not recommended"
     */
    suspend fun compatibilityLabel(model: ModelInfo): CompatibilityLabel {
        val cap = getCapability()
        val neededGB = model.fileSizeMB / 1024f * 1.5f
        return when {
            neededGB > cap.totalRamGB        -> CompatibilityLabel.NOT_RECOMMENDED
            neededGB > cap.availableRamGB    -> CompatibilityLabel.MAY_BE_SLOW
            model.fileSizeMB / 1024f > cap.maxModelParamsBillion * 0.6f -> CompatibilityLabel.MAY_BE_SLOW
            else                             -> CompatibilityLabel.WORKS
        }
    }

    enum class CompatibilityLabel(val display: String, val color: Long) {
        WORKS(          "Works on this device", 0xFF30D158),   // SGColors.Safe
        MAY_BE_SLOW(    "May be slow",          0xFFFFD60A),   // SGColors.Warning
        NOT_RECOMMENDED("Not recommended",      0xFFFF3B30)    // SGColors.Critical
    }

    /**
     * Estimate model RAM usage in GB using the quantization bits-per-weight formula.
     * Matches off-grid's estimateModelMemoryGB().
     *
     * @param paramsBillions  Model parameter count
     * @param quantization    Quantization string, e.g. "Q4_K_M", "Q8_0"
     */
    fun estimateModelRamGB(paramsBillions: Float, quantization: String): Float {
        val bits = QUANT_BITS.entries
            .firstOrNull { (k, _) -> quantization.uppercase().contains(k) }?.value ?: 4.5f
        return (paramsBillions * bits) / 8f
    }

    fun invalidateCache() { cached = null }

    // ─── Implementation ───────────────────────────────────────────────────────

    private fun buildCapability(): DeviceCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalRamGB = memInfo.totalMem  / (1024f * 1024f * 1024f)
        val availRamGB = memInfo.availMem  / (1024f * 1024f * 1024f)

        val socInfo = detectSoC()

        val tier = when {
            totalRamGB < 4f -> DeviceTier.LOW
            totalRamGB < 6f -> DeviceTier.MEDIUM
            totalRamGB < 8f -> DeviceTier.HIGH
            else            -> DeviceTier.FLAGSHIP
        }

        val (maxParams, quant) = paramBudget(totalRamGB)

        return DeviceCapability(
            totalRamGB           = totalRamGB,
            availableRamGB       = availRamGB,
            tier                 = tier,
            socInfo              = socInfo,
            supportsNPU          = socInfo.qnnTier != null,
            maxModelParamsBillion = maxParams,
            recommendedQuantization = quant
        ).also { cached = it }
    }

    private fun detectSoC(): SoCInfo {
        val hardware  = Build.HARDWARE.lowercase()
        val socModel  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.uppercase()
        } else {
            readSoCModelFromCpuInfo()
        }

        val vendor = when {
            hardware.contains("qcom") || socModel.startsWith("SM") -> SoCVendor.QUALCOMM
            hardware.contains("mt") && !hardware.contains("matrix") -> SoCVendor.MEDIATEK
            hardware.contains("exynos")                             -> SoCVendor.EXYNOS
            Build.MODEL.startsWith("Pixel")                        -> SoCVendor.TENSOR
            else                                                    -> SoCVendor.UNKNOWN
        }

        val qnnTier = if (vendor == SoCVendor.QUALCOMM) classifySmNumber(socModel) else null
        Log.d(TAG, "SoC detected: vendor=$vendor model=$socModel qnnTier=$qnnTier")
        return SoCInfo(vendor, socModel, qnnTier)
    }

    private fun readSoCModelFromCpuInfo(): String {
        return try {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware", ignoreCase = true) }
                ?.substringAfter(":").orEmpty().trim().uppercase()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cpuinfo", e)
            ""
        }
    }

    private fun classifySmNumber(socModel: String): QnnTier? {
        val base = socModel.split("-")[0].uppercase()
        if (!base.startsWith("SM")) return null
        val num  = base.removePrefix("SM").filter { it.isDigit() }.toIntOrNull() ?: return null
        return when {
            FLAGSHIP_8GEN2.contains(num) -> QnnTier.GEN2
            FLAGSHIP_8GEN1.contains(num) -> QnnTier.GEN1
            else                         -> QnnTier.MIN
        }
    }

    private fun paramBudget(totalRamGB: Float): Pair<Float, String> {
        val tier = RAM_TO_PARAMS.firstOrNull { (min, max, _) ->
            totalRamGB >= min && totalRamGB < max
        }
        return Pair(tier?.third ?: 1.5f, "Q4_K_M")
    }
}
