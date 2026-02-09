package com.bitchat.android.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.round

/**
 * Time Sensor
 */
class TimeSensor : BaseSensor(SensorID.TIME, "time", 100L) {
    
    private var timeData: TimeData? = null
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        timeData = null
    }
    
    override fun updateData() {
        timeData = TimeData(utc = System.currentTimeMillis() / 1000)
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = timeData
    
    override fun packData(data: Any): ByteArray {
        val timeData = data as TimeData
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timeData.utc).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val utc = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).long
        timeData = TimeData(utc = utc)
        lastUpdate = System.currentTimeMillis()
        return timeData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = timeData ?: return null
        return mapOf(
            "icon" to "clock-time-ten-outline",
            "name" to "Timestamp",
            "values" to mapOf("UTC" to data.utc)
        )
    }
    
    data class TimeData(val utc: Long)
}

/**
 * Battery Sensor
 */
class BatterySensor(private val context: Context) : BaseSensor(SensorID.BATTERY, "battery", 10000L) {
    
    private var batteryData: BatteryData? = null
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        batteryData = null
    }
    
    override fun updateData() {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val chargePercent = if (level >= 0 && scale > 0) {
                (level * 100 / scale).toFloat()
            } else {
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
            }
            
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            
            batteryData = BatteryData(
                chargePercent = (round(chargePercent * 10) / 10).toFloat(),
                charging = isCharging,
                temperature = null
            )
            lastUpdate = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e("BatterySensor", "Error updating battery data", e)
            batteryData = null
        }
    }
    
    override fun getSensorData(): Any? = batteryData
    
    override fun packData(data: Any): ByteArray {
        val batteryData = data as BatteryData
        val output = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(output)
        dos.writeFloat(batteryData.chargePercent)
        dos.writeBoolean(batteryData.charging)
        dos.writeFloat(batteryData.temperature ?: 0f)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = java.io.DataInputStream(packed.inputStream())
        val chargePercent = dis.readFloat()
        val charging = dis.readBoolean()
        val temperature = dis.readFloat()
        batteryData = BatteryData(
            chargePercent = chargePercent,
            charging = charging,
            temperature = if (temperature != 0f) temperature else null
        )
        lastUpdate = System.currentTimeMillis()
        return batteryData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = batteryData ?: return null
        val icon = when {
            data.charging -> when {
                data.chargePercent >= 90 -> "battery-charging-90"
                data.chargePercent >= 80 -> "battery-charging-80"
                data.chargePercent >= 70 -> "battery-charging-70"
                data.chargePercent >= 60 -> "battery-charging-60"
                data.chargePercent >= 50 -> "battery-charging-50"
                data.chargePercent >= 40 -> "battery-charging-40"
                data.chargePercent >= 30 -> "battery-charging-30"
                data.chargePercent >= 20 -> "battery-charging-20"
                data.chargePercent >= 10 -> "battery-charging-10"
                else -> "battery-outline"
            }
            else -> when {
                data.chargePercent >= 97 -> "battery"
                data.chargePercent >= 90 -> "battery-90"
                data.chargePercent >= 80 -> "battery-80"
                data.chargePercent >= 70 -> "battery-70"
                data.chargePercent >= 60 -> "battery-60"
                data.chargePercent >= 50 -> "battery-50"
                data.chargePercent >= 40 -> "battery-40"
                data.chargePercent >= 30 -> "battery-30"
                data.chargePercent >= 20 -> "battery-20"
                data.chargePercent >= 10 -> "battery-10"
                else -> "battery-outline"
            }
        }
        
        return mapOf(
            "icon" to icon,
            "name" to "Battery",
            "values" to mapOf(
                "percent" to data.chargePercent,
                "temperature" to data.temperature,
                "_meta" to if (data.charging) "charging" else "discharging"
            )
        )
    }
    
    data class BatteryData(
        val chargePercent: Float,
        val charging: Boolean,
        val temperature: Float?
    )
}

/**
 * Location Sensor
 */
class LocationSensor(
    private val context: Context,
    private val locationTelemeter: Telemeter
) : BaseSensor(SensorID.LOCATION, "location", 15000L), LocationListener {
    
    companion object {
        private const val MIN_DISTANCE = 4.0f // meters
        private const val ACCURACY_TARGET = 250.0f // meters
    }
    
    private var locationManager: LocationManager? = null
    private var locationData: LocationData? = null
    private var lastRawLocation: Location? = null
    
    override fun setupSensor() {
        if (!checkPermissions()) {
            Log.w("LocationSensor", "Location permissions not granted")
            return
        }
        
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        try {
            val providers = locationManager?.getProviders(true) ?: emptyList()
            val bestProvider = locationManager?.getBestProvider(
                android.location.Criteria().apply {
                    accuracy = android.location.Criteria.ACCURACY_FINE
                    isAltitudeRequired = true
                },
                true
            )
            
            if (bestProvider != null) {
                locationManager?.requestLocationUpdates(
                    bestProvider,
                    staleTime,
                    MIN_DISTANCE,
                    this
                )
            }
        } catch (e: SecurityException) {
            Log.e("LocationSensor", "Error requesting location updates", e)
        }
        
        updateData()
    }
    
    override fun teardownSensor() {
        locationManager?.removeUpdates(this)
        locationManager = null
        locationData = null
        lastRawLocation = null
    }
    
    override fun updateData() {
        if (synthesized) {
            // Handle synthesized location data
            if (locationData != null) {
                lastUpdate = System.currentTimeMillis()
                return
            }
        }
        
        if (lastRawLocation != null) {
            val location = lastRawLocation!!
            if (location.accuracy <= ACCURACY_TARGET) {
                locationData = LocationData(
                    latitude = round(location.latitude * 1e6) / 1e6,
                    longitude = round(location.longitude * 1e6) / 1e6,
                    altitude = (round(location.altitude * 100) / 100).toFloat(),
                    speed = (round(location.speed * 3.6f * 100) / 100).toFloat(), // Convert m/s to km/h
                    bearing = (round(location.bearing * 100) / 100).toFloat(),
                    accuracy = (round(location.accuracy * 100) / 100).toFloat(),
                    lastUpdate = location.time / 1000
                )
                lastUpdate = System.currentTimeMillis()
            }
        }
    }
    
    override fun getSensorData(): Any? = locationData
    
    override fun onLocationChanged(location: Location) {
        lastRawLocation = location
        updateData()
    }
    
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    
    private fun checkPermissions(): Boolean {
        return locationTelemeter.checkPermission("ACCESS_COARSE_LOCATION") &&
               locationTelemeter.checkPermission("ACCESS_FINE_LOCATION")
    }
    
    override fun packData(data: Any): ByteArray {
        val locationData = data as LocationData
        val output = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(output)
        dos.writeInt((locationData.latitude * 1e6).toInt())
        dos.writeInt((locationData.longitude * 1e6).toInt())
        dos.writeInt((locationData.altitude * 1e2).toInt())
        dos.writeInt((locationData.speed * 1e2).toInt())
        dos.writeInt((locationData.bearing * 1e2).toInt())
        dos.writeShort((locationData.accuracy * 1e2).toInt())
        dos.writeLong(locationData.lastUpdate)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = java.io.DataInputStream(packed.inputStream())
        val latitudeVal = dis.readInt() / 1e6
        val longitudeVal = dis.readInt() / 1e6
        val altitudeVal = (dis.readInt() / 1e2).toFloat()
        val speedVal = (dis.readInt() / 1e2).toFloat()
        val bearingVal = (dis.readInt() / 1e2).toFloat()
        val accuracyVal = (dis.readShort() / 1e2).toFloat()
        val lastUpdateVal = dis.readLong()
        locationData = LocationData(
            latitude = latitudeVal,
            longitude = longitudeVal,
            altitude = altitudeVal,
            speed = speedVal,
            bearing = bearingVal,
            accuracy = accuracyVal,
            lastUpdate = lastUpdateVal
        )
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return locationData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = locationData ?: return null
        
        val values = mutableMapOf<String, Any>(
            "latitude" to data.latitude,
            "longitude" to data.longitude,
            "altitude" to data.altitude,
            "speed" to data.speed,
            "heading" to data.bearing,
            "accuracy" to data.accuracy,
            "updated" to data.lastUpdate
        )
        
        // Add relative calculations if relativeTo is provided
        if (relativeTo != null) {
            val relativeLocation = relativeTo.read("location") as? LocationData
            if (relativeLocation != null) {
                val distance = calculateDistance(
                    data.latitude, data.longitude,
                    relativeLocation.latitude, relativeLocation.longitude
                )
                values["distance"] = mapOf(
                    "orthodromic" to distance,
                    "euclidian" to distance // Simplified
                )
            }
        }
        
        return mapOf(
            "icon" to "map-marker",
            "name" to "Location",
            "values" to values
        )
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Haversine formula for great circle distance
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
    
    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val altitude: Float,
        val speed: Float,
        val bearing: Float,
        val accuracy: Float,
        val lastUpdate: Long
    )
}
