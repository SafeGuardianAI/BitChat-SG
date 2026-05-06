package com.bitchat.android.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.geohash.Geohash
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Location Service using Google Play Services FusedLocationProvider.
 *
 * Features:
 * - Battery-efficient location updates (passive + balanced)
 * - Last known location (instant, no GPS activation)
 * - Emergency mode: high-accuracy continuous tracking
 * - Geohash computation for mesh channels
 *
 * Requires ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION permissions
 * (already declared in AndroidManifest.xml).
 */
class LocationService(private val context: Context) {

    companion object {
        private const val TAG = "LocationService"
        private const val PASSIVE_INTERVAL_MS = 60_000L
        private const val BALANCED_INTERVAL_MS = 30_000L
        private const val HIGH_ACCURACY_INTERVAL_MS = 5_000L
    }

    enum class LocationMode {
        /** Minimal battery -- piggybacks on other apps' location requests */
        PASSIVE,
        /** ~100 m accuracy, reasonable battery usage */
        BALANCED,
        /** GPS, maximum accuracy for emergency situations */
        HIGH_ACCURACY
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _lastLocation = MutableStateFlow<LocationState?>(null)
    val lastLocation: StateFlow<LocationState?> = _lastLocation.asStateFlow()

    private var locationCallback: LocationCallback? = null

    // ---- Permission check ----

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ---- Last known (battery-free, instant) ----

    /**
     * Returns the most recent cached location without activating GPS.
     * Returns null if no cached location exists or permission is missing.
     */
    fun getLastKnownLocation(): LocationState? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted")
            return _lastLocation.value
        }

        try {
            @Suppress("MissingPermission")
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    _lastLocation.value = location.toLocationState()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting last location", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last location", e)
        }

        return _lastLocation.value
    }

    // ---- Continuous tracking ----

    /**
     * Start receiving location updates at the cadence determined by [mode].
     * [callback] is invoked on each new fix.
     */
    fun startTracking(mode: LocationMode, callback: (LocationState) -> Unit) {
        stopTracking()

        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted, cannot start tracking")
            return
        }

        val request = buildLocationRequest(mode)

        val locCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val state = location.toLocationState()
                _lastLocation.value = state
                callback(state)
            }
        }

        try {
            @Suppress("MissingPermission")
            fusedClient.requestLocationUpdates(request, locCallback, Looper.getMainLooper())
            locationCallback = locCallback
            Log.d(TAG, "Location tracking started in $mode mode")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting location tracking", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location tracking", e)
        }
    }

    /**
     * Stop all location updates.
     */
    fun stopTracking() {
        locationCallback?.let { cb ->
            try {
                fusedClient.removeLocationUpdates(cb)
                Log.d(TAG, "Location tracking stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping location tracking", e)
            }
        }
        locationCallback = null
    }

    // ---- Geohash ----

    /**
     * Compute the geohash of the last known position.
     * @param precision geohash character length (default 6 ~ 1.2 km cell)
     * @return geohash string or null if no location available
     */
    fun computeGeohash(precision: Int = 6): String? {
        val loc = _lastLocation.value ?: return null
        return Geohash.encode(loc.latitude, loc.longitude, precision)
    }

    // ---- Helpers ----

    private fun buildLocationRequest(mode: LocationMode): LocationRequest {
        return when (mode) {
            LocationMode.PASSIVE -> LocationRequest.Builder(
                Priority.PRIORITY_PASSIVE, PASSIVE_INTERVAL_MS
            ).setMinUpdateIntervalMillis(PASSIVE_INTERVAL_MS / 2).build()

            LocationMode.BALANCED -> LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, BALANCED_INTERVAL_MS
            ).setMinUpdateIntervalMillis(BALANCED_INTERVAL_MS / 2).build()

            LocationMode.HIGH_ACCURACY -> LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, HIGH_ACCURACY_INTERVAL_MS
            ).setMinUpdateIntervalMillis(HIGH_ACCURACY_INTERVAL_MS / 2).build()
        }
    }

    private fun android.location.Location.toLocationState(): LocationState {
        return LocationState(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            speed = speed,
            bearing = bearing,
            timestamp = time,
            provider = provider ?: "unknown"
        )
    }
}
