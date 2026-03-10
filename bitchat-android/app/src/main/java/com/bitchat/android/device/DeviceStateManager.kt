package com.bitchat.android.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified Device State Manager
 *
 * Monitors and exposes all device state via Android APIs:
 * - Battery level, charging state, temperature
 * - GPS location (via [LocationService])
 * - Network connectivity (WiFi, Cellular, none)
 * - Power saving mode
 * - Screen state
 * - Available storage
 * - Device info (model, OS version)
 *
 * Emits a unified [DeviceState] via [StateFlow], updated every [POLL_INTERVAL_MS].
 * Used by the AI agent for context-aware decision making.
 */
class DeviceStateManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceStateManager"
        private const val POLL_INTERVAL_MS = 10_000L
    }

    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private val gson = Gson()
    private val locationService = LocationService(context)

    private var monitorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ---- Lifecycle ----

    /**
     * Start monitoring all device state. Call from a long-lived [CoroutineScope]
     * (e.g. ViewModel or Application scope).
     */
    fun startMonitoring(scope: CoroutineScope) {
        stopMonitoring()
        registerNetworkCallback()
        monitorJob = scope.launch {
            while (isActive) {
                updateState()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        unregisterNetworkCallback()
    }

    /**
     * Get current state snapshot (synchronous, reads system APIs on calling thread).
     */
    fun getSnapshot(): DeviceState {
        updateStateSync()
        return _deviceState.value
    }

    /**
     * Get state as JSON string for AI context injection.
     */
    fun getStateAsJson(): String {
        return gson.toJson(getSnapshot())
    }

    // ---- Internal update ----

    private suspend fun updateState() = withContext(Dispatchers.IO) {
        updateStateSync()
    }

    private fun updateStateSync() {
        try {
            _deviceState.value = DeviceState(
                timestamp = System.currentTimeMillis(),
                battery = getBatteryState(),
                network = getNetworkState(),
                location = locationService.getLastKnownLocation(),
                storage = getStorageState(),
                power = getPowerState(),
                device = getDeviceInfo()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating device state", e)
        }
    }

    // ---- Battery ----

    private fun getBatteryState(): BatteryState {
        val intent: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        if (intent == null) return BatteryState()

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (scale > 0) (level * 100) / scale else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0 -> "none"
            else -> "unknown"
        }

        // Temperature is reported in tenths of a degree Celsius
        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempRaw / 10f

        // Voltage is reported in millivolts
        val voltageRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val volts = voltageRaw / 1000f

        val healthInt = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val health = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
            else -> "unknown"
        }

        return BatteryState(
            level = pct,
            isCharging = isCharging,
            chargingSource = source,
            temperature = tempC,
            voltage = volts,
            health = health
        )
    }

    // ---- Network ----

    private fun getNetworkState(): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkState()

        val network = cm.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null

        if (caps == null) {
            return NetworkState(isConnected = false, type = "none")
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val hasVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        val type = when {
            hasWifi -> "wifi"
            hasCellular -> "cellular"
            hasEthernet -> "ethernet"
            hasVpn -> "vpn"
            else -> "none"
        }

        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val bandwidth = caps.linkDownstreamBandwidthKbps

        return NetworkState(
            isConnected = hasInternet,
            type = type,
            isMetered = isMetered,
            signalStrength = -1, // requires TelephonyManager callback for cell; omit for simplicity
            hasWifi = hasWifi,
            hasCellular = hasCellular,
            downstreamBandwidthKbps = bandwidth
        )
    }

    // ---- Storage ----

    private fun getStorageState(): StorageState {
        return try {
            @Suppress("DEPRECATION")
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockSizeLong * stat.blockCountLong
            val availableBytes = stat.blockSizeLong * stat.availableBlocksLong
            val totalMB = totalBytes / (1024 * 1024)
            val availableMB = availableBytes / (1024 * 1024)
            val usedPercent = if (totalMB > 0) ((totalMB - availableMB) * 100 / totalMB).toInt() else 0
            StorageState(totalMB = totalMB, availableMB = availableMB, usedPercent = usedPercent)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading storage state", e)
            StorageState()
        }
    }

    // ---- Power ----

    private fun getPowerState(): PowerState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return PowerState()

        val isPowerSave = pm.isPowerSaveMode
        val isInteractive = pm.isInteractive
        val ignoringOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName)

        return PowerState(
            isPowerSaveMode = isPowerSave,
            isInteractive = isInteractive,
            isIgnoringBatteryOptimizations = ignoringOptimizations
        )
    }

    // ---- Device Info ----

    private fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo() // Uses Build defaults in the data class
    }

    // ---- Network Callback (real-time) ----

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                updateStateSync()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                updateStateSync()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateStateSync()
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = networkCallback
        if (cm != null && callback != null) {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }
}
