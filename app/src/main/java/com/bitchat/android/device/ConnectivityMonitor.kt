package com.bitchat.android.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors network connectivity changes in real-time.
 *
 * Provides reactive [StateFlow] properties for online status and connection type
 * that the AI agent and sync services can observe. Triggers re-evaluation of
 * pending sync operations when connectivity is restored.
 *
 * Usage:
 * ```
 * val monitor = ConnectivityMonitor(context)
 * monitor.registerCallback()
 * // observe monitor.isOnline / monitor.connectionType
 * // ...
 * monitor.unregisterCallback()
 * ```
 */
class ConnectivityMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ConnectivityMonitor"
    }

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(false)
    /** True when the device has a validated internet connection. */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _connectionType = MutableStateFlow("none")
    /** Current connection type: "wifi", "cellular", "ethernet", "vpn", or "none". */
    val connectionType: StateFlow<String> = _connectionType.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val listeners = mutableListOf<ConnectivityListener>()

    /**
     * Optional listener for connectivity change events.
     */
    fun interface ConnectivityListener {
        fun onConnectivityChanged(isOnline: Boolean, type: String)
    }

    fun addListener(listener: ConnectivityListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConnectivityListener) {
        listeners.remove(listener)
    }

    // ---- Registration ----

    /**
     * Start listening for connectivity changes. Safe to call multiple times;
     * previous callback is unregistered first.
     */
    fun registerCallback() {
        unregisterCallback()

        val cm = connectivityManager ?: run {
            Log.w(TAG, "ConnectivityManager not available")
            return
        }

        // Seed with current state
        refreshState()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                refreshState()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                refreshState()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                refreshState()
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback)
            callback = networkCallback
            Log.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Stop listening for connectivity changes.
     */
    fun unregisterCallback() {
        val cm = connectivityManager
        val cb = callback
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        callback = null
    }

    // ---- Query methods ----

    /** Check if the active network has validated internet connectivity. */
    fun hasInternet(): Boolean {
        val caps = getActiveCapabilities() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Check if the device is connected via Wi-Fi. */
    fun hasWifi(): Boolean {
        val caps = getActiveCapabilities() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Check if the device is connected via cellular data. */
    fun hasCellular(): Boolean {
        val caps = getActiveCapabilities() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** Check if the current connection is metered (cellular or hotspot). */
    fun isMetered(): Boolean {
        val caps = getActiveCapabilities() ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---- Internal helpers ----

    private fun getActiveCapabilities(): NetworkCapabilities? {
        val cm = connectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(network)
    }

    private fun refreshState() {
        val caps = getActiveCapabilities()

        val online = caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "none"
        }

        val changed = _isOnline.value != online || _connectionType.value != type
        _isOnline.value = online
        _connectionType.value = type

        if (changed) {
            listeners.forEach { it.onConnectivityChanged(online, type) }
        }
    }
}
