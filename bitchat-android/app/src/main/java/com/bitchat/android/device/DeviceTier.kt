package com.bitchat.android.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.Serializable

/**
 * Capability tier used to gate AI features and pick a UI shell.
 *
 * - [FULL]:     2020+ flagship — local LLM/ASR/TTS, full chat UI.
 * - [STANDARD]: 2018–2020 mid-range — local mesh + Nostr, AI delegated to peers.
 * - [LITE]:     ≤2018 / elder phones — rule-based UI, sensor + FM emergency only,
 *               AI strictly delegated via [AI_REQ] mesh protocol.
 */
enum class DeviceTier { FULL, STANDARD, LITE }

@Serializable
data class DeviceCapabilities(
    val tier: DeviceTier,
    val totalRamMB: Long,
    val availableRamMB: Long,
    val storageFreeMB: Long,
    val sdkInt: Int,
    val cpuAbi: String,
    val cores: Int,
    val isLowRamDevice: Boolean,
    val hasGyroscope: Boolean,
    val hasBarometer: Boolean,
    val hasStepCounter: Boolean,
    val hasFmRadio: Boolean,
    val supportsBleAdvertising: Boolean
)

object DeviceTierDetector {

    private const val FULL_RAM_MB = 5_500L
    private const val FULL_FREE_STORAGE_MB = 4_096L
    private const val STANDARD_RAM_MB = 2_800L

    fun detect(context: Context): DeviceCapabilities {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalRamMB = memInfo.totalMem / (1024 * 1024)
        val availableRamMB = memInfo.availMem / (1024 * 1024)
        val isLowRam = am?.isLowRamDevice ?: false

        val storageFreeMB = try {
            @Suppress("DEPRECATION")
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.blockSizeLong * stat.availableBlocksLong) / (1024 * 1024)
        } catch (_: Exception) { 0L }

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasGyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val hasBaro = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        val hasSteps = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val cores = Runtime.getRuntime().availableProcessors()
        val hasFm = detectFmRadio(context)
        val canAdvertise = canBleAdvertise(context)

        val tier = when {
            isLowRam || totalRamMB < STANDARD_RAM_MB || storageFreeMB < 1_500L -> DeviceTier.LITE
            totalRamMB < FULL_RAM_MB || storageFreeMB < FULL_FREE_STORAGE_MB || cores < 6 -> DeviceTier.STANDARD
            else -> DeviceTier.FULL
        }

        return DeviceCapabilities(
            tier = tier,
            totalRamMB = totalRamMB,
            availableRamMB = availableRamMB,
            storageFreeMB = storageFreeMB,
            sdkInt = Build.VERSION.SDK_INT,
            cpuAbi = abi,
            cores = cores,
            isLowRamDevice = isLowRam,
            hasGyroscope = hasGyro,
            hasBarometer = hasBaro,
            hasStepCounter = hasSteps,
            hasFmRadio = hasFm,
            supportsBleAdvertising = canAdvertise
        )
    }

    // OEM-specific package check; no public Android FM API exists.
    private fun detectFmRadio(context: Context): Boolean {
        val pm = context.packageManager
        val candidates = listOf(
            "com.android.fmradio",
            "com.samsung.android.app.fm",
            "com.lge.fmradio",
            "com.motorola.fmplayer",
            "com.qualcomm.fmradio",
            "com.htc.fm"
        )
        return candidates.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
    }

    private fun canBleAdvertise(context: Context): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager ?: return false
        val adapter = mgr.adapter ?: return false
        return adapter.bluetoothLeAdvertiser != null
    }
}
