package com.bitchat.android.device

import android.os.Build
import kotlinx.serialization.Serializable

/**
 * Unified device state data classes.
 *
 * All classes are serializable so they can be injected into AI agent
 * context as JSON. Default values allow incremental population when
 * individual sensors are unavailable.
 */

@Serializable
data class DeviceState(
    val timestamp: Long = 0L,
    val battery: BatteryState = BatteryState(),
    val network: NetworkState = NetworkState(),
    val location: LocationState? = null,
    val storage: StorageState = StorageState(),
    val power: PowerState = PowerState(),
    val device: DeviceInfo = DeviceInfo()
)

@Serializable
data class BatteryState(
    /** Battery level 0-100, or -1 if unknown */
    val level: Int = -1,
    val isCharging: Boolean = false,
    /** One of: usb, ac, wireless, none, unknown */
    val chargingSource: String = "unknown",
    /** Battery temperature in Celsius (from BatteryManager, tenths of degree) */
    val temperature: Float = 0f,
    /** Battery voltage in volts */
    val voltage: Float = 0f,
    /** One of: good, overheat, dead, over_voltage, cold, unspecified_failure, unknown */
    val health: String = "unknown"
)

@Serializable
data class NetworkState(
    val isConnected: Boolean = false,
    /** One of: wifi, cellular, ethernet, vpn, none */
    val type: String = "none",
    val isMetered: Boolean = true,
    /** Signal strength indicator 0-4, or -1 if unknown */
    val signalStrength: Int = -1,
    val hasWifi: Boolean = false,
    val hasCellular: Boolean = false,
    /** Downstream bandwidth estimate in Kbps, or -1 */
    val downstreamBandwidthKbps: Int = -1
)

@Serializable
data class LocationState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** Estimated horizontal accuracy in meters */
    val accuracy: Float = 0f,
    val altitude: Double = 0.0,
    /** Speed in m/s */
    val speed: Float = 0f,
    /** Bearing in degrees */
    val bearing: Float = 0f,
    val timestamp: Long = 0L,
    /** Provider name: gps, network, fused, passive */
    val provider: String = "unknown"
)

@Serializable
data class StorageState(
    val totalMB: Long = 0L,
    val availableMB: Long = 0L,
    /** 0-100 */
    val usedPercent: Int = 0
)

@Serializable
data class PowerState(
    val isPowerSaveMode: Boolean = false,
    /** True when screen is on / device is interactive */
    val isInteractive: Boolean = true,
    val isIgnoringBatteryOptimizations: Boolean = false
)

@Serializable
data class DeviceInfo(
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val osVersion: String = "Android ${Build.VERSION.RELEASE}",
    val sdkVersion: Int = Build.VERSION.SDK_INT,
    val isEmulator: Boolean = (Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.BRAND.startsWith("generic")
            || Build.DEVICE.startsWith("generic")
            || "google_sdk" == Build.PRODUCT)
)
