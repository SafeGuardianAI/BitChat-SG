package com.bitchat.android.ai.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tool Executors
 *
 * Maps tool names to their actual implementations using Android APIs.
 * Each executor returns a [ToolResult] with a JSON string payload.
 */
class ToolExecutors(private val context: Context) {

    companion object {
        private const val TAG = "ToolExecutors"
        private const val VITAL_PREFS = "safeguardian_vital_data"
        private const val SYNC_PREFS = "safeguardian_sync_queue"
        private const val AGENT_PREFS = "safeguardian_agent"
    }

    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val vitalPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(VITAL_PREFS, Context.MODE_PRIVATE)
    }

    private val syncPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
    }

    private val agentPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(AGENT_PREFS, Context.MODE_PRIVATE)
    }

    // Cached sensor data from last reading
    @Volatile private var lastAccelerometer: FloatArray? = null
    @Volatile private var lastBarometer: Float? = null
    @Volatile private var lastLight: Float? = null
    @Volatile private var lastProximity: Float? = null

    /**
     * Dispatch a tool call by name.
     */
    fun execute(toolName: String, args: Map<String, Any>): ToolResult {
        return try {
            when (toolName) {
                "get_battery_status" -> getBatteryStatus()
                "get_location" -> getLocation()
                "get_network_status" -> getNetworkStatus()
                "get_sensor_data" -> getSensorData()
                "get_mesh_peers" -> getMeshPeers()
                "get_device_state" -> getDeviceState()
                "search_disaster_info" -> searchDisasterInfo(args)
                "get_vital_data" -> getVitalData()
                "get_pending_sync" -> getPendingSync()
                "set_power_mode" -> setPowerMode(args)
                "enable_emergency_mode" -> enableEmergencyMode(args)
                "speak_aloud" -> speakAloud(args)
                "save_vital_data" -> saveVitalData(args)
                "start_location_tracking" -> startLocationTracking(args)
                "broadcast_emergency" -> broadcastEmergency(args)
                "request_rescue" -> requestRescue(args)
                "generate_cap_alert" -> generateCAPAlert(args)
                "offload_data" -> offloadData(args)
                else -> ToolResult(false, "", "Unknown tool: $toolName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool $toolName failed", e)
            ToolResult(false, "", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Quick device context summary for the agent prompt (no tool call needed).
     */
    fun getDeviceContextSummary(): String {
        val battery = getBatteryLevelSimple()
        val charging = isCharging()
        val hasNetwork = hasNetworkConnectivity()
        val powerMode = agentPrefs.getString("power_mode", "balanced") ?: "balanced"
        val emergencyMode = agentPrefs.getBoolean("emergency_mode", false)

        return buildString {
            appendLine("Battery: ${battery}%${if (charging) " (charging)" else ""}")
            appendLine("Network: ${if (hasNetwork) "connected" else "offline"}")
            appendLine("Power mode: $powerMode")
            appendLine("Emergency mode: ${if (emergencyMode) "ON" else "OFF"}")
        }
    }

    // ========================================================================
    // QUERY TOOLS
    // ========================================================================

    private fun getBatteryStatus(): ToolResult {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent: Intent? = context.registerReceiver(null, intentFilter)

        if (batteryIntent == null) {
            return ToolResult(false, "", "Could not read battery status")
        }

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val chargingState = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }

        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val chargeSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }

        val temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempCelsius = if (temperature > 0) temperature / 10.0 else -1.0

        val health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val healthStr = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }

        val technology = batteryIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"

        val json = JSONObject().apply {
            put("level_percent", percentage)
            put("charging_state", chargingState)
            put("charge_source", chargeSource)
            put("temperature_celsius", tempCelsius)
            put("health", healthStr)
            put("technology", technology)
        }

        return ToolResult(true, json.toString())
    }

    private fun getLocation(): ToolResult {
        // Try to get last known location from available providers
        var bestLocation: Location? = null

        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                try {
                    @Suppress("MissingPermission")
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy) {
                            bestLocation = loc
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission for provider: $provider")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location", e)
        }

        if (bestLocation == null) {
            val json = JSONObject().apply {
                put("available", false)
                put("reason", "No location available. GPS may be disabled or permissions not granted.")
            }
            return ToolResult(true, json.toString())
        }

        val json = JSONObject().apply {
            put("available", true)
            put("latitude", bestLocation.latitude)
            put("longitude", bestLocation.longitude)
            put("accuracy_meters", bestLocation.accuracy)
            put("altitude_meters", if (bestLocation.hasAltitude()) bestLocation.altitude else JSONObject.NULL)
            put("speed_mps", if (bestLocation.hasSpeed()) bestLocation.speed else JSONObject.NULL)
            put("bearing", if (bestLocation.hasBearing()) bestLocation.bearing else JSONObject.NULL)
            put("provider", bestLocation.provider)
            put("timestamp_ms", bestLocation.time)
            put("age_seconds", (System.currentTimeMillis() - bestLocation.time) / 1000)
        }

        return ToolResult(true, json.toString())
    }

    private fun getNetworkStatus(): ToolResult {
        val json = JSONObject()

        try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = if (activeNetwork != null) {
                connectivityManager.getNetworkCapabilities(activeNetwork)
            } else null

            json.put("connected", activeNetwork != null)

            if (capabilities != null) {
                json.put("has_wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                json.put("has_cellular", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                json.put("has_bluetooth", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))
                json.put("has_internet", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                json.put("has_validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    json.put("signal_strength", capabilities.signalStrength)
                }

                val downBandwidth = capabilities.linkDownstreamBandwidthKbps
                val upBandwidth = capabilities.linkUpstreamBandwidthKbps
                json.put("downstream_bandwidth_kbps", downBandwidth)
                json.put("upstream_bandwidth_kbps", upBandwidth)
            } else {
                json.put("has_wifi", false)
                json.put("has_cellular", false)
                json.put("has_bluetooth", false)
                json.put("has_internet", false)
            }

            // BLE mesh peer count (read from shared prefs set by mesh service)
            val meshPrefs = context.getSharedPreferences("ble_mesh_state", Context.MODE_PRIVATE)
            json.put("mesh_peer_count", meshPrefs.getInt("connected_peers", 0))

        } catch (e: Exception) {
            Log.e(TAG, "Error reading network status", e)
            json.put("error", e.message)
        }

        return ToolResult(true, json.toString())
    }

    private fun getSensorData(): ToolResult {
        // Register short-lived listeners to get current readings
        readSensorsSync()

        val json = JSONObject()

        // Accelerometer
        val accel = lastAccelerometer
        if (accel != null && accel.size >= 3) {
            json.put("accelerometer", JSONObject().apply {
                put("x", accel[0].toDouble())
                put("y", accel[1].toDouble())
                put("z", accel[2].toDouble())
                val magnitude = Math.sqrt(
                    (accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]).toDouble()
                )
                put("magnitude", magnitude)
                put("is_shaking", magnitude > 15.0) // Rough threshold for strong motion
            })
        } else {
            json.put("accelerometer", JSONObject.NULL)
        }

        // Barometer (pressure)
        val pressure = lastBarometer
        if (pressure != null) {
            json.put("barometer", JSONObject().apply {
                put("pressure_hpa", pressure.toDouble())
                // Rough altitude estimate from pressure (ISA standard atmosphere)
                val altitudeEstimate = 44330.0 * (1.0 - Math.pow((pressure / 1013.25).toDouble(), 1.0 / 5.255))
                put("estimated_altitude_m", altitudeEstimate)
            })
        } else {
            json.put("barometer", JSONObject.NULL)
        }

        // Light
        val light = lastLight
        if (light != null) {
            json.put("light", JSONObject().apply {
                put("lux", light.toDouble())
                val condition = when {
                    light < 10 -> "dark"
                    light < 50 -> "dim"
                    light < 500 -> "indoor"
                    light < 10000 -> "outdoor_shade"
                    else -> "direct_sunlight"
                }
                put("condition", condition)
            })
        } else {
            json.put("light", JSONObject.NULL)
        }

        // Proximity
        val proximity = lastProximity
        if (proximity != null) {
            json.put("proximity", JSONObject().apply {
                put("distance_cm", proximity.toDouble())
                put("is_near", proximity < 5.0f)
            })
        } else {
            json.put("proximity", JSONObject.NULL)
        }

        return ToolResult(true, json.toString())
    }

    private fun getMeshPeers(): ToolResult {
        // Read mesh peer state from the BLE mesh service shared preferences
        val meshPrefs = context.getSharedPreferences("ble_mesh_state", Context.MODE_PRIVATE)
        val peersJson = meshPrefs.getString("peers_json", null)

        if (peersJson != null) {
            return ToolResult(true, peersJson)
        }

        // Return empty peer list if mesh service hasn't written anything
        val json = JSONObject().apply {
            put("peer_count", 0)
            put("peers", JSONArray())
            put("mesh_active", meshPrefs.getBoolean("mesh_active", false))
        }
        return ToolResult(true, json.toString())
    }

    private fun getDeviceState(): ToolResult {
        // Aggregate multiple sources into a single snapshot
        val battery = getBatteryStatus()
        val network = getNetworkStatus()
        val location = getLocation()

        // Storage info
        val statFs = StatFs(Environment.getDataDirectory().path)
        val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val totalBytes = statFs.totalBlocksLong * statFs.blockSizeLong
        val usedPercent = ((totalBytes - availableBytes) * 100) / totalBytes

        val powerMode = agentPrefs.getString("power_mode", "balanced") ?: "balanced"
        val emergencyMode = agentPrefs.getBoolean("emergency_mode", false)

        val json = JSONObject().apply {
            put("battery", JSONObject(battery.data))
            put("network", JSONObject(network.data))
            put("location", JSONObject(location.data))
            put("storage", JSONObject().apply {
                put("available_mb", availableBytes / (1024 * 1024))
                put("total_mb", totalBytes / (1024 * 1024))
                put("used_percent", usedPercent)
            })
            put("power_mode", powerMode)
            put("emergency_mode", emergencyMode)
            put("android_version", Build.VERSION.SDK_INT)
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
        }

        return ToolResult(true, json.toString())
    }

    private fun searchDisasterInfo(args: Map<String, Any>): ToolResult {
        val query = args["query"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: query")

        // Delegate to the existing RAG service via shared preferences / intent
        // In a full integration, this would call DisasterRAGService.search(query)
        // For now, we use the bundled knowledge base
        val results = searchLocalKnowledgeBase(query)

        val json = JSONObject().apply {
            put("query", query)
            put("results_count", results.size)
            put("results", JSONArray().apply {
                results.forEach { result ->
                    put(JSONObject().apply {
                        put("text", result.first)
                        put("relevance", result.second)
                    })
                }
            })
        }

        return ToolResult(true, json.toString())
    }

    private fun getVitalData(): ToolResult {
        val dataJson = vitalPrefs.getString("vital_data", null)
        if (dataJson != null) {
            return ToolResult(true, dataJson)
        }

        val json = JSONObject().apply {
            put("has_data", false)
            put("message", "No vital data stored. Use save_vital_data to store medical information.")
        }
        return ToolResult(true, json.toString())
    }

    private fun getPendingSync(): ToolResult {
        val pendingCount = syncPrefs.getInt("pending_count", 0)
        val lastSyncTime = syncPrefs.getLong("last_sync_time", 0)
        val pendingTypes = syncPrefs.getString("pending_types", "") ?: ""

        val json = JSONObject().apply {
            put("pending_count", pendingCount)
            put("last_sync_time_ms", lastSyncTime)
            put("last_sync_age_seconds",
                if (lastSyncTime > 0) (System.currentTimeMillis() - lastSyncTime) / 1000 else -1)
            put("pending_types", pendingTypes)
        }

        return ToolResult(true, json.toString())
    }

    // ========================================================================
    // ACTION TOOLS
    // ========================================================================

    private fun setPowerMode(args: Map<String, Any>): ToolResult {
        val mode = args["mode"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: mode")

        val validModes = listOf("ultra_saver", "balanced", "performance")
        if (mode !in validModes) {
            return ToolResult(false, "", "Invalid mode: $mode. Must be one of: $validModes")
        }

        agentPrefs.edit().putString("power_mode", mode).apply()
        Log.i(TAG, "Power mode set to: $mode")

        // Broadcast the power mode change so other components can react
        val intent = Intent("com.bitchat.android.POWER_MODE_CHANGED").apply {
            putExtra("mode", mode)
        }
        context.sendBroadcast(intent)

        val json = JSONObject().apply {
            put("success", true)
            put("power_mode", mode)
            put("message", "Power mode set to $mode")
        }

        return ToolResult(true, json.toString())
    }

    private fun enableEmergencyMode(args: Map<String, Any>): ToolResult {
        val enabled = when (val v = args["enabled"]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: true
            else -> true
        }

        agentPrefs.edit().putBoolean("emergency_mode", enabled).apply()
        Log.i(TAG, "Emergency mode: $enabled")

        // Broadcast emergency mode change
        val intent = Intent("com.bitchat.android.EMERGENCY_MODE_CHANGED").apply {
            putExtra("enabled", enabled)
        }
        context.sendBroadcast(intent)

        val json = JSONObject().apply {
            put("success", true)
            put("emergency_mode", enabled)
            put("message", if (enabled) {
                "Emergency mode ENABLED. Auto-TTS, high-priority mesh, and GPS tracking activated."
            } else {
                "Emergency mode DISABLED. Returning to normal operation."
            })
        }

        return ToolResult(true, json.toString())
    }

    private fun speakAloud(args: Map<String, Any>): ToolResult {
        val text = args["text"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: text")
        val priority = args["priority"]?.toString() ?: "normal"

        // Broadcast TTS request -- the TTS service (TTSQueueManager) picks this up
        val intent = Intent("com.bitchat.android.TTS_REQUEST").apply {
            putExtra("text", text)
            putExtra("priority", priority)
        }
        context.sendBroadcast(intent)

        Log.i(TAG, "TTS request sent: priority=$priority, text=${text.take(100)}")

        val json = JSONObject().apply {
            put("success", true)
            put("text_length", text.length)
            put("priority", priority)
        }

        return ToolResult(true, json.toString())
    }

    private fun saveVitalData(args: Map<String, Any>): ToolResult {
        val dataJson = args["data_json"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: data_json")

        // Validate it's valid JSON
        try {
            JSONObject(dataJson)
        } catch (e: Exception) {
            try {
                JSONArray(dataJson)
            } catch (e2: Exception) {
                return ToolResult(false, "", "data_json is not valid JSON: ${e.message}")
            }
        }

        vitalPrefs.edit()
            .putString("vital_data", dataJson)
            .putLong("vital_data_updated", System.currentTimeMillis())
            .apply()

        Log.i(TAG, "Vital data saved (${dataJson.length} bytes)")

        val json = JSONObject().apply {
            put("success", true)
            put("bytes_saved", dataJson.length)
            put("timestamp", System.currentTimeMillis())
        }

        return ToolResult(true, json.toString())
    }

    private fun startLocationTracking(args: Map<String, Any>): ToolResult {
        val mode = args["mode"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: mode")

        val validModes = listOf("passive", "balanced", "high_accuracy")
        if (mode !in validModes) {
            return ToolResult(false, "", "Invalid mode: $mode. Must be one of: $validModes")
        }

        agentPrefs.edit().putString("location_tracking_mode", mode).apply()

        // Broadcast to location service
        val intent = Intent("com.bitchat.android.LOCATION_TRACKING_REQUEST").apply {
            putExtra("mode", mode)
        }
        context.sendBroadcast(intent)

        Log.i(TAG, "Location tracking started: mode=$mode")

        val json = JSONObject().apply {
            put("success", true)
            put("tracking_mode", mode)
            put("message", "Location tracking started in $mode mode")
        }

        return ToolResult(true, json.toString())
    }

    // ========================================================================
    // EMERGENCY TOOLS
    // ========================================================================

    private fun broadcastEmergency(args: Map<String, Any>): ToolResult {
        val message = args["message"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: message")

        val includeLocation = when (val v = args["include_location"]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: true
            else -> true
        }

        // Get location for inclusion
        var locationStr = ""
        if (includeLocation) {
            val locResult = getLocation()
            try {
                val locJson = JSONObject(locResult.data)
                if (locJson.optBoolean("available", false)) {
                    val lat = locJson.getDouble("latitude")
                    val lon = locJson.getDouble("longitude")
                    locationStr = "Location: $lat, $lon"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse location for broadcast", e)
            }
        }

        val fullMessage = if (locationStr.isNotBlank()) {
            "$message\n$locationStr"
        } else {
            message
        }

        // Broadcast via the mesh network
        val intent = Intent("com.bitchat.android.EMERGENCY_BROADCAST").apply {
            putExtra("message", fullMessage)
            putExtra("priority", "emergency")
            putExtra("timestamp", System.currentTimeMillis())
        }
        context.sendBroadcast(intent)

        Log.i(TAG, "Emergency broadcast sent: ${fullMessage.take(200)}")

        val json = JSONObject().apply {
            put("success", true)
            put("message_sent", fullMessage)
            put("include_location", includeLocation)
            put("timestamp", System.currentTimeMillis())
        }

        return ToolResult(true, json.toString())
    }

    private fun requestRescue(args: Map<String, Any>): ToolResult {
        val triageLevel = args["triage_level"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: triage_level")
        val description = args["description"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: description")
        val injuries = args["injuries"]?.toString() ?: "Not specified"

        val validLevels = listOf("immediate", "delayed", "minimal", "expectant")
        if (triageLevel !in validLevels) {
            return ToolResult(false, "", "Invalid triage_level: $triageLevel")
        }

        // Get current location
        val locResult = getLocation()
        var latitude: Double? = null
        var longitude: Double? = null
        try {
            val locJson = JSONObject(locResult.data)
            if (locJson.optBoolean("available", false)) {
                latitude = locJson.getDouble("latitude")
                longitude = locJson.getDouble("longitude")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get location for rescue request", e)
        }

        // Get vital data if available
        val vitalData = vitalPrefs.getString("vital_data", null)

        val rescuePayload = JSONObject().apply {
            put("type", "RESCUE_REQUEST")
            put("id", UUID.randomUUID().toString())
            put("timestamp", System.currentTimeMillis())
            put("triage", JSONObject().apply {
                put("level", triageLevel)
                put("color", when (triageLevel) {
                    "immediate" -> "RED"
                    "delayed" -> "YELLOW"
                    "minimal" -> "GREEN"
                    "expectant" -> "BLACK"
                    else -> "UNKNOWN"
                })
            })
            put("description", description)
            put("injuries", injuries)
            if (latitude != null && longitude != null) {
                put("location", JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                })
            }
            if (vitalData != null) {
                put("vital_data", JSONObject(vitalData))
            }
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        }

        // Send via mesh and any available network
        val meshIntent = Intent("com.bitchat.android.RESCUE_REQUEST").apply {
            putExtra("payload", rescuePayload.toString())
            putExtra("priority", "critical")
        }
        context.sendBroadcast(meshIntent)

        Log.i(TAG, "Rescue request sent: triage=$triageLevel")

        return ToolResult(true, rescuePayload.toString())
    }

    private fun generateCAPAlert(args: Map<String, Any>): ToolResult {
        val eventType = args["event_type"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: event_type")
        val severity = args["severity"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: severity")
        val description = args["description"]?.toString()
            ?: return ToolResult(false, "", "Missing required parameter: description")

        // Get location for the alert area
        val locResult = getLocation()
        var lat: Double? = null
        var lon: Double? = null
        try {
            val locJson = JSONObject(locResult.data)
            if (locJson.optBoolean("available", false)) {
                lat = locJson.getDouble("latitude")
                lon = locJson.getDouble("longitude")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get location for CAP alert", e)
        }

        val identifier = "safeguardian-${UUID.randomUUID()}"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val now = dateFormat.format(Date())

        val urgency = when (severity) {
            "extreme" -> "Immediate"
            "severe" -> "Expected"
            "moderate" -> "Future"
            "minor" -> "Past"
            else -> "Unknown"
        }

        val certainty = when (severity) {
            "extreme", "severe" -> "Observed"
            "moderate" -> "Likely"
            "minor" -> "Possible"
            else -> "Unknown"
        }

        val capCategory = when (eventType) {
            "earthquake" -> "Geo"
            "fire" -> "Fire"
            "flood" -> "Met"
            "tsunami" -> "Geo"
            "landslide" -> "Geo"
            "collapse" -> "Infra"
            "medical" -> "Health"
            else -> "Other"
        }

        // Build CAP v1.2 XML
        val capXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">""")
            appendLine("  <identifier>$identifier</identifier>")
            appendLine("  <sender>SafeGuardian-Device</sender>")
            appendLine("  <sent>$now</sent>")
            appendLine("  <status>Actual</status>")
            appendLine("  <msgType>Alert</msgType>")
            appendLine("  <scope>Public</scope>")
            appendLine("  <info>")
            appendLine("    <category>$capCategory</category>")
            appendLine("    <event>${eventType.replaceFirstChar { it.uppercase() }}</event>")
            appendLine("    <urgency>$urgency</urgency>")
            appendLine("    <severity>${severity.replaceFirstChar { it.uppercase() }}</severity>")
            appendLine("    <certainty>$certainty</certainty>")
            appendLine("    <headline>${eventType.replaceFirstChar { it.uppercase() }} Alert - $severity</headline>")
            appendLine("    <description>$description</description>")
            if (lat != null && lon != null) {
                appendLine("    <area>")
                appendLine("      <areaDesc>Reporter location</areaDesc>")
                appendLine("      <circle>$lat,$lon 1.0</circle>")
                appendLine("    </area>")
            }
            appendLine("  </info>")
            appendLine("</alert>")
        }

        // Also create a JSON representation for mesh transmission
        val json = JSONObject().apply {
            put("cap_xml", capXml)
            put("identifier", identifier)
            put("event_type", eventType)
            put("severity", severity)
            put("description", description)
            put("sent", now)
            if (lat != null && lon != null) {
                put("location", JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lon)
                })
            }
        }

        // Broadcast the alert
        val intent = Intent("com.bitchat.android.CAP_ALERT").apply {
            putExtra("cap_xml", capXml)
            putExtra("cap_json", json.toString())
            putExtra("severity", severity)
        }
        context.sendBroadcast(intent)

        Log.i(TAG, "CAP alert generated: $identifier")

        return ToolResult(true, json.toString())
    }

    private fun offloadData(args: Map<String, Any>): ToolResult {
        // Broadcast offload request to the DistributedMemoryManager
        val intent = Intent("com.bitchat.android.OFFLOAD_DATA").apply {
            putExtra("reason", "agent_triggered")
            putExtra("timestamp", System.currentTimeMillis())
        }
        context.sendBroadcast(intent)

        // Read current battery to include in report
        val battery = getBatteryLevelSimple()

        val json = JSONObject().apply {
            put("success", true)
            put("battery_at_offload", battery)
            put("message", "Data offload request sent to distributed memory manager.")
            put("timestamp", System.currentTimeMillis())
        }

        Log.i(TAG, "Data offload triggered at battery=$battery%")

        return ToolResult(true, json.toString())
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private fun getBatteryLevelSimple(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent: Intent? = context.registerReceiver(null, intentFilter)
        if (batteryIntent == null) return -1

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    private fun isCharging(): Boolean {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent: Intent? = context.registerReceiver(null, intentFilter)
        if (batteryIntent == null) return false

        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun hasNetworkConnectivity(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Read sensor values synchronously with a short timeout.
     * Registers listeners, waits up to 500ms, then unregisters.
     */
    private fun readSensorsSync() {
        val latch = CountDownLatch(1)
        var sensorsRead = 0
        val targetSensors = 4 // accel, baro, light, proximity

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccelerometer = event.values.clone()
                        sensorsRead++
                    }
                    Sensor.TYPE_PRESSURE -> {
                        lastBarometer = event.values[0]
                        sensorsRead++
                    }
                    Sensor.TYPE_LIGHT -> {
                        lastLight = event.values[0]
                        sensorsRead++
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        lastProximity = event.values[0]
                        sensorsRead++
                    }
                }
                if (sensorsRead >= targetSensors) {
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // Register all sensors
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val baroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val proxSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        accelSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        baroSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        lightSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        proxSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }

        // Wait up to 500ms for readings
        try {
            latch.await(500, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Sensor read interrupted", e)
        } finally {
            sensorManager.unregisterListener(listener)
        }
    }

    /**
     * Simple keyword-based search of a local disaster knowledge base.
     * Returns list of (text, relevance_score) pairs.
     *
     * In a full integration this delegates to DisasterRAGService.
     */
    private fun searchLocalKnowledgeBase(query: String): List<Pair<String, Double>> {
        // Built-in emergency knowledge snippets
        val knowledgeBase = listOf(
            "During an earthquake, DROP to the ground, take COVER under a sturdy desk or table, and HOLD ON until shaking stops. Stay away from windows, outside walls, and heavy objects that could fall.",
            "If trapped after a building collapse, tap on pipes or walls to signal rescuers. Use a whistle if available. Cover your nose and mouth with cloth to filter dust. Conserve energy and ration water.",
            "For severe bleeding, apply direct pressure with a clean cloth. If blood soaks through, add more cloth on top. Elevate the injured limb above heart level. Apply a tourniquet only as a last resort for life-threatening limb bleeding.",
            "Signs of shock: pale/cool/clammy skin, rapid pulse, rapid breathing, confusion, weakness. Lay the person flat, elevate legs 12 inches, keep warm with blankets, do NOT give food or drink.",
            "In a flood, move to higher ground immediately. Never walk through moving water -- 6 inches can knock you down. Never drive through flooded roads. If trapped in a building, go to the highest floor.",
            "Fire evacuation: crawl low under smoke, feel doors before opening (hot door = fire on other side). Cover mouth with wet cloth. Once out, never go back inside. Meet at your designated assembly point.",
            "CPR for adults: push hard and fast on the center of the chest, 2 inches deep, 100-120 compressions per minute. After 30 compressions, give 2 rescue breaths. Continue until help arrives or the person recovers.",
            "Tsunami warning signs: strong earthquake near coast, ocean receding unusually far, loud roar from ocean. Move to high ground (100+ feet elevation) or inland immediately. Do not wait for official warnings.",
            "Hypothermia signs: shivering, confusion, slurred speech, drowsiness. Move to warm shelter, remove wet clothing, warm the center of body first (chest, neck, head, groin) with skin-to-skin contact or warm blankets.",
            "Water purification: boil water for at least 1 minute (3 minutes above 6500 feet). If boiling is not possible, use water purification tablets or 8 drops of household bleach per gallon, wait 30 minutes.",
            "START Triage: Assess walking (MINIMAL/green). Check breathing (not breathing after airway cleared = EXPECTANT/black). Breathing rate >30 = IMMEDIATE/red. Check circulation: capillary refill >2s or no radial pulse = IMMEDIATE/red. Otherwise DELAYED/yellow.",
            "To conserve phone battery in emergencies: enable airplane mode when not actively communicating, reduce screen brightness to minimum, disable unnecessary apps and services, use power saving mode, turn off location services when not needed."
        )

        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        if (queryWords.isEmpty()) return emptyList()

        return knowledgeBase
            .map { entry ->
                val entryLower = entry.lowercase()
                val matchCount = queryWords.count { word -> entryLower.contains(word) }
                val score = matchCount.toDouble() / queryWords.size.toDouble()
                Pair(entry, score)
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(3)
    }
}
