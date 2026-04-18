package com.bitchat.android.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Centralized permission management for bitchat app
 * Handles all Bluetooth and notification permissions required for the app to function
 */
class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionManager"
        private const val PREFS_NAME = "bitchat_permissions"
        private const val KEY_FIRST_TIME_COMPLETE = "first_time_onboarding_complete"
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if this is the first time the user is launching the app
     */
    fun isFirstTimeLaunch(): Boolean {
        return !sharedPrefs.getBoolean(KEY_FIRST_TIME_COMPLETE, false)
    }

    /**
     * Mark the first-time onboarding as complete
     */
    fun markOnboardingComplete() {
        sharedPrefs.edit()
            .putBoolean(KEY_FIRST_TIME_COMPLETE, true)
            .apply()
        Log.d(TAG, "First-time onboarding marked as complete")
    }

    /**
     * Get all permissions required by the app
     * Note: Notification permission is optional and not included here,
     * so the app works without notification access.
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Bluetooth permissions (API level dependent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }

        // Location permissions (required for Bluetooth LE scanning)
        permissions.addAll(listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))

        // Microphone
        permissions.add(Manifest.permission.RECORD_AUDIO)

        // Camera
        permissions.add(Manifest.permission.CAMERA)

        // Media access (API level dependent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.addAll(listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ))
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Wi-Fi control for P2P mesh
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // Emergency telephony & SMS
        permissions.addAll(listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        ))

        // Body sensors & activity recognition
        permissions.add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        return permissions
    }

    /**
     * Get optional permissions that improve the experience but aren't required.
     * Currently includes POST_NOTIFICATIONS on Android 13+.
     */
    fun getOptionalPermissions(): List<String> {
        val optional = mutableListOf<String>()
        // Notifications on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            optional.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return optional
    }

    /**
     * Check if a specific permission is granted
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if all required permissions are granted
     */
    fun areAllPermissionsGranted(): Boolean {
        return getRequiredPermissions().all { isPermissionGranted(it) }
    }

    /**
     * Check if the app can draw overlays (display over other apps)
     */
    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true
    }

    /**
     * Check if the app can modify system settings (volume, brightness)
     */
    fun canWriteSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.System.canWrite(context)
        } else true
    }

    /**
     * Check if Do Not Disturb policy access is granted for this app
     */
    fun isDndAccessGranted(): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.isNotificationPolicyAccessGranted
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DND access", e)
            false
        }
    }

    /**
     * Check if battery optimization is disabled for this app
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery optimization status", e)
                false
            }
        } else {
            // Battery optimization doesn't exist on Android < 6.0
            true
        }
    }

    /**
     * Check if battery optimization is supported on this device
     */
    fun isBatteryOptimizationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    /**
     * Get the list of permissions that are missing
     */
    fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { !isPermissionGranted(it) }
    }

    /**
     * Get categorized permission information for display
     */
    fun getCategorizedPermissions(): List<PermissionCategory> {
        val categories = mutableListOf<PermissionCategory>()

        // Bluetooth/Nearby Devices category
        val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        categories.add(
            PermissionCategory(
                type = PermissionType.NEARBY_DEVICES,
                description = "Required to discover bitchat users via Bluetooth",
                permissions = bluetoothPermissions,
                isGranted = bluetoothPermissions.all { isPermissionGranted(it) },
                systemDescription = "Allow bitchat to connect to nearby devices"
            )
        )

        // Location category
        val locationPermissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        categories.add(
            PermissionCategory(
                type = PermissionType.PRECISE_LOCATION,
                description = "Required by Android to discover nearby bitchat users via Bluetooth",
                permissions = locationPermissions,
                isGranted = locationPermissions.all { isPermissionGranted(it) },
                systemDescription = "bitchat needs this to scan for nearby devices"
            )
        )

        // Notifications category (if applicable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.NOTIFICATIONS,
                    description = "Receive notifications when you receive private messages",
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    isGranted = isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS),
                    systemDescription = "Allow bitchat to send you notifications"
                )
            )
        }

        // Microphone category
        val micPermissions = listOf(Manifest.permission.RECORD_AUDIO)
        categories.add(
            PermissionCategory(
                type = PermissionType.MICROPHONE,
                description = "Voice notes, speech-to-text commands, and on-device speech recognition",
                permissions = micPermissions,
                isGranted = micPermissions.all { isPermissionGranted(it) },
                systemDescription = "Allow bitchat to record audio"
            )
        )

        // Camera category
        val cameraPermissions = listOf(Manifest.permission.CAMERA)
        categories.add(
            PermissionCategory(
                type = PermissionType.CAMERA,
                description = "Capture photos for damage documentation, survivor identification, and visual mesh sharing",
                permissions = cameraPermissions,
                isGranted = cameraPermissions.all { isPermissionGranted(it) },
                systemDescription = "Allow bitchat to take photos and video"
            )
        )

        // Media access category
        val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            emptyList()
        }
        if (mediaPermissions.isNotEmpty()) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.MEDIA_ACCESS,
                    description = "Share photos, videos, and audio files over the mesh network",
                    permissions = mediaPermissions,
                    isGranted = mediaPermissions.all { isPermissionGranted(it) },
                    systemDescription = "Allow bitchat to access your photos and media"
                )
            )
        }

        // Wi-Fi control category
        val wifiPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        categories.add(
            PermissionCategory(
                type = PermissionType.WIFI_CONTROL,
                description = "Wi-Fi Direct and Wi-Fi Aware for high-bandwidth mesh relay when Bluetooth range is insufficient",
                permissions = wifiPermissions.ifEmpty { listOf("WIFI_CONTROL") },
                isGranted = wifiPermissions.isEmpty() || wifiPermissions.all { isPermissionGranted(it) },
                systemDescription = "Allow bitchat to control Wi-Fi for P2P mesh"
            )
        )

        // Emergency communications category
        val emergencyPermissions = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        categories.add(
            PermissionCategory(
                type = PermissionType.EMERGENCY_COMMS,
                description = "Emergency dialing, SMS fallback when mesh is unavailable, and phone state detection for call priority",
                permissions = emergencyPermissions,
                isGranted = emergencyPermissions.all { isPermissionGranted(it) },
                systemDescription = "Allow bitchat to make emergency calls and send SMS"
            )
        )

        // Body sensors & activity category
        val sensorPermissions = mutableListOf(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sensorPermissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        categories.add(
            PermissionCategory(
                type = PermissionType.BODY_SENSORS,
                description = "Wearable heart rate monitoring and motion detection for fall/impact alerts and survivor status",
                permissions = sensorPermissions,
                isGranted = sensorPermissions.all { isPermissionGranted(it) },
                systemDescription = "Allow bitchat to access body sensors and activity data"
            )
        )

        // Screen activation & alarms
        categories.add(
            PermissionCategory(
                type = PermissionType.SCREEN_ALERTS,
                description = "Wake screen for incoming SOS alerts, schedule timed check-in alarms, and full-screen emergency notifications",
                permissions = listOf("SCREEN_ALERTS"),
                isGranted = true, // WAKE_LOCK, USE_FULL_SCREEN_INTENT are normal permissions
                systemDescription = "Allow bitchat to wake screen and set alarms"
            )
        )

        // Display over other apps (special permission)
        categories.add(
            PermissionCategory(
                type = PermissionType.OVERLAY,
                description = "Show emergency alert overlay on top of any app so SOS signals are never missed",
                permissions = listOf("SYSTEM_ALERT_WINDOW"),
                isGranted = canDrawOverlays(),
                systemDescription = "Find \"bitchat\" and toggle it ON"
            )
        )

        // Modify system settings (special permission)
        categories.add(
            PermissionCategory(
                type = PermissionType.SYSTEM_SETTINGS,
                description = "Adjust volume to max for emergency sirens and screen brightness for flashlight/SOS signaling",
                permissions = listOf("WRITE_SETTINGS"),
                isGranted = canWriteSettings(),
                systemDescription = "Find \"bitchat\" and toggle it ON"
            )
        )

        // Battery optimization category (if applicable)
        if (isBatteryOptimizationSupported()) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.BATTERY_OPTIMIZATION,
                    description = "Disable battery optimization to ensure bitchat runs reliably in the background and maintains mesh network connections",
                    permissions = listOf("BATTERY_OPTIMIZATION"),
                    isGranted = isBatteryOptimizationDisabled(),
                    systemDescription = "Allow bitchat to run without battery restrictions"
                )
            )
        }

        // DND access category
        categories.add(
            PermissionCategory(
                type = PermissionType.DND_ACCESS,
                description = "Allow bitchat to bypass Do Not Disturb for emergency mesh alerts and SOS signals",
                permissions = listOf("DND_ACCESS"),
                isGranted = isDndAccessGranted(),
                systemDescription = "Find \"bitchat\" in the list and toggle it ON"
            )
        )

        // Boot & auto-start
        categories.add(
            PermissionCategory(
                type = PermissionType.BOOT_BACKGROUND,
                description = "Automatically restart mesh network after device reboot so you stay connected without manual launch",
                permissions = listOf("BOOT_COMPLETED"),
                isGranted = true, // RECEIVE_BOOT_COMPLETED is a normal permission
                systemDescription = "bitchat will auto-start on boot"
            )
        )

        return categories
    }

    /**
     * Get detailed diagnostic information about permission status
     */
    fun getPermissionDiagnostics(): String {
        return buildString {
            appendLine("Permission Diagnostics:")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("First time launch: ${isFirstTimeLaunch()}")
            appendLine("All permissions granted: ${areAllPermissionsGranted()}")
            appendLine()
            
            getCategorizedPermissions().forEach { category ->
                appendLine("${category.type.nameValue}: ${if (category.isGranted) "✅ GRANTED" else "❌ MISSING"}")
                category.permissions.forEach { permission ->
                    val granted = isPermissionGranted(permission)
                    appendLine("  - ${permission.substringAfterLast(".")}: ${if (granted) "✅" else "❌"}")
                }
                appendLine()
            }
            
            val missing = getMissingPermissions()
            if (missing.isNotEmpty()) {
                appendLine("Missing permissions:")
                missing.forEach { permission ->
                    appendLine("- $permission")
                }
            }
        }
    }

    /**
     * Log permission status for debugging
     */
    fun logPermissionStatus() {
        Log.d(TAG, getPermissionDiagnostics())
    }
}

/**
 * Data class representing a category of related permissions
 */
data class PermissionCategory(
    val type: PermissionType,
    val description: String,
    val permissions: List<String>,
    val isGranted: Boolean,
    val systemDescription: String
)

enum class PermissionType(val nameValue: String) {
    NEARBY_DEVICES("Nearby Devices"),
    PRECISE_LOCATION("Precise Location"),
    MICROPHONE("Microphone"),
    CAMERA("Camera"),
    MEDIA_ACCESS("Photos & Media"),
    WIFI_CONTROL("Wi-Fi Control"),
    EMERGENCY_COMMS("Emergency Communications"),
    BODY_SENSORS("Body Sensors & Activity"),
    SCREEN_ALERTS("Screen Activation & Alarms"),
    OVERLAY("Display Over Other Apps"),
    SYSTEM_SETTINGS("Modify System Settings"),
    NOTIFICATIONS("Notifications"),
    BATTERY_OPTIMIZATION("Battery Optimization"),
    DND_ACCESS("Do Not Disturb Access"),
    BOOT_BACKGROUND("Auto-Start on Boot"),
    OTHER("Other")
}
