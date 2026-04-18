package com.bitchat.android.ai.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Placeholder interfaces for device state, matching the expected API from the
 * device package (which lives on a different branch). When the branches merge,
 * replace these with the real types from com.bitchat.android.device.
 */
data class DeviceState(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val hasNetwork: Boolean,
    val emergencyMode: Boolean,
    val powerMode: String
)

data class SensorSnapshot(
    val accelerometerMagnitude: Double,
    val pressure: Float?,
    val light: Float?,
    val proximity: Float?,
    val timestamp: Long
)

/**
 * Automation Rules Engine
 *
 * Monitors device state and sensor data, automatically triggering actions when
 * conditions are met. Rules have cooldown periods to prevent rapid re-triggering.
 *
 * Built-in rules:
 * 1. LOW_BATTERY_OFFLOAD: Battery < 10% -> offload data to mesh peers
 * 2. EARTHQUAKE_DETECT: Strong accelerometer shake -> broadcast emergency + enable emergency mode
 * 3. NETWORK_RESTORED_SYNC: Network comes back after being offline -> trigger sync
 * 4. POSSIBLE_TRAPPED: Dark + no motion for 5 minutes -> send SOS
 * 5. POWER_CONSERVATION: Battery < 20% -> switch to ultra_saver mode
 * 6. HIGH_TEMP_WARNING: Battery temperature > 45C -> alert and reduce activity
 */
class AutomationRules(
    private val context: Context,
    private val agentExecutor: AgentExecutor
) {

    companion object {
        private const val TAG = "AutomationRules"
        private const val PREFS_NAME = "automation_rules_prefs"
        private const val MONITOR_INTERVAL_MS = 10_000L  // 10 seconds
        private const val SENSOR_CHECK_INTERVAL_MS = 1_000L  // 1 second for sensors

        // Thresholds
        private const val LOW_BATTERY_THRESHOLD = 10
        private const val CONSERVATION_BATTERY_THRESHOLD = 20
        private const val EARTHQUAKE_MAGNITUDE_THRESHOLD = 20.0  // m/s^2, ~2g
        private const val EARTHQUAKE_SUSTAINED_COUNT = 3  // readings above threshold
        private const val TRAPPED_DARK_LUX = 5.0f
        private const val TRAPPED_STILL_MAGNITUDE = 10.5  // close to gravity only
        private const val TRAPPED_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
        private const val HIGH_TEMP_THRESHOLD = 45.0  // Celsius
    }

    data class Rule(
        val name: String,
        val description: String,
        val condition: (DeviceState, SensorSnapshot?) -> Boolean,
        val action: suspend () -> Unit,
        val cooldownMs: Long = 60_000L,
        val requiresDisasterMode: Boolean = false
    )

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val toolExecutors = ToolExecutors(context)
    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // Monitoring state
    private var monitorJob: Job? = null
    private var sensorJob: Job? = null
    private var sensorListener: SensorEventListener? = null

    // Rule state tracking
    private val lastTriggered = ConcurrentHashMap<String, Long>()
    private val disabledRules = ConcurrentHashMap.newKeySet<String>()

    // Sensor accumulation for earthquake detection
    @Volatile private var recentShakeCount = 0
    @Volatile private var lastShakeResetTime = System.currentTimeMillis()

    // Trapped detection state
    @Volatile private var darkAndStillSince: Long? = null

    // Network state tracking
    @Volatile private var wasOffline = false

    // Current sensor snapshot
    @Volatile private var currentSensorSnapshot: SensorSnapshot? = null

    // Observable state
    private val _monitoringActive = MutableStateFlow(false)
    val monitoringActive: StateFlow<Boolean> = _monitoringActive.asStateFlow()

    private val _lastTriggeredRule = MutableStateFlow<String?>(null)
    val lastTriggeredRule: StateFlow<String?> = _lastTriggeredRule.asStateFlow()

    /**
     * All automation rules.
     */
    val rules: List<Rule> = listOf(
        // Rule 1: Low battery data offload
        Rule(
            name = "LOW_BATTERY_OFFLOAD",
            description = "Offload critical data to mesh peers when battery drops below $LOW_BATTERY_THRESHOLD%",
            condition = { state, _ ->
                state.batteryLevel in 1 until LOW_BATTERY_THRESHOLD && !state.isCharging
            },
            action = {
                Log.w(TAG, "LOW_BATTERY_OFFLOAD triggered")
                toolExecutors.execute("offload_data", emptyMap())
                toolExecutors.execute("speak_aloud", mapOf(
                    "text" to "Warning: Battery critically low. Offloading data to nearby devices.",
                    "priority" to "high"
                ))
            },
            cooldownMs = 5 * 60_000L  // 5 minute cooldown
        ),

        // Rule 2: Earthquake detection
        Rule(
            name = "EARTHQUAKE_DETECT",
            description = "Detect strong shaking and auto-broadcast emergency",
            condition = { _, sensor ->
                sensor != null && sensor.accelerometerMagnitude > EARTHQUAKE_MAGNITUDE_THRESHOLD
                        && recentShakeCount >= EARTHQUAKE_SUSTAINED_COUNT
            },
            action = {
                Log.w(TAG, "EARTHQUAKE_DETECT triggered")
                // Enable emergency mode
                toolExecutors.execute("enable_emergency_mode", mapOf("enabled" to true))
                // Broadcast emergency
                toolExecutors.execute("broadcast_emergency", mapOf(
                    "message" to "EARTHQUAKE DETECTED - Strong shaking at this location. Automated alert.",
                    "include_location" to true
                ))
                // Generate CAP alert
                toolExecutors.execute("generate_cap_alert", mapOf(
                    "event_type" to "earthquake",
                    "severity" to "severe",
                    "description" to "Automated earthquake detection: sustained strong shaking detected by device accelerometer."
                ))
                // Speak warning
                toolExecutors.execute("speak_aloud", mapOf(
                    "text" to "Earthquake detected! Drop, cover, and hold on. Emergency broadcast sent.",
                    "priority" to "emergency"
                ))
                // Reset shake counter
                recentShakeCount = 0
            },
            cooldownMs = 2 * 60_000L  // 2 minute cooldown
        ),

        // Rule 3: Network restored sync
        Rule(
            name = "NETWORK_RESTORED_SYNC",
            description = "Trigger data sync when network connectivity is restored",
            condition = { state, _ ->
                val networkBack = state.hasNetwork && wasOffline
                if (!state.hasNetwork) {
                    wasOffline = true
                }
                if (networkBack) {
                    wasOffline = false
                }
                networkBack
            },
            action = {
                Log.i(TAG, "NETWORK_RESTORED_SYNC triggered")
                // Broadcast sync trigger
                val intent = Intent("com.bitchat.android.SYNC_TRIGGER").apply {
                    putExtra("reason", "network_restored")
                    putExtra("timestamp", System.currentTimeMillis())
                }
                context.sendBroadcast(intent)
                toolExecutors.execute("speak_aloud", mapOf(
                    "text" to "Network connection restored. Syncing data.",
                    "priority" to "normal"
                ))
            },
            cooldownMs = 30_000L  // 30 second cooldown
        ),

        // Rule 4: Possible trapped detection
        Rule(
            name = "POSSIBLE_TRAPPED",
            description = "Detect darkness + no motion for 5 minutes, suggest SOS",
            condition = { _, sensor ->
                if (sensor == null) {
                    false
                } else {
                    val isDark = sensor.light != null && sensor.light < TRAPPED_DARK_LUX
                    val isStill = sensor.accelerometerMagnitude < TRAPPED_STILL_MAGNITUDE

                    if (isDark && isStill) {
                        val since = darkAndStillSince
                        if (since == null) {
                            darkAndStillSince = System.currentTimeMillis()
                            false
                        } else {
                            System.currentTimeMillis() - since >= TRAPPED_DURATION_MS
                        }
                    } else {
                        darkAndStillSince = null
                        false
                    }
                }
            },
            action = {
                Log.w(TAG, "POSSIBLE_TRAPPED triggered")
                toolExecutors.execute("speak_aloud", mapOf(
                    "text" to "It seems you may be trapped. Sending SOS signal. Tap your screen if you can hear this.",
                    "priority" to "emergency"
                ))
                toolExecutors.execute("broadcast_emergency", mapOf(
                    "message" to "POSSIBLE TRAPPED PERSON - Device in dark and motionless for extended period. Automated SOS.",
                    "include_location" to true
                ))
                // Reset the timer so it doesn't keep firing every cooldown
                darkAndStillSince = null
            },
            cooldownMs = 10 * 60_000L,  // 10 minute cooldown
            requiresDisasterMode = true
        ),

        // Rule 5: Power conservation
        Rule(
            name = "POWER_CONSERVATION",
            description = "Switch to ultra_saver mode when battery drops below $CONSERVATION_BATTERY_THRESHOLD%",
            condition = { state, _ ->
                state.batteryLevel in 1 until CONSERVATION_BATTERY_THRESHOLD
                        && !state.isCharging
                        && state.powerMode != "ultra_saver"
            },
            action = {
                Log.i(TAG, "POWER_CONSERVATION triggered")
                toolExecutors.execute("set_power_mode", mapOf("mode" to "ultra_saver"))
                toolExecutors.execute("speak_aloud", mapOf(
                    "text" to "Battery below twenty percent. Switching to ultra power saver mode.",
                    "priority" to "normal"
                ))
            },
            cooldownMs = 5 * 60_000L  // 5 minute cooldown
        ),

        // Rule 6: High temperature warning
        Rule(
            name = "HIGH_TEMP_WARNING",
            description = "Alert when battery temperature exceeds ${HIGH_TEMP_THRESHOLD}C",
            condition = { _, _ ->
                val temp = getBatteryTemperature()
                temp > HIGH_TEMP_THRESHOLD
            },
            action = {
                val temp = getBatteryTemperature()
                Log.w(TAG, "HIGH_TEMP_WARNING triggered: ${temp}C")
                toolExecutors.execute("set_power_mode", mapOf("mode" to "ultra_saver"))
                toolExecutors.execute("speak_aloud", mapOf(
                    "text" to "Warning: Device temperature is high at ${temp.toInt()} degrees. Reducing activity to prevent damage.",
                    "priority" to "high"
                ))
            },
            cooldownMs = 3 * 60_000L  // 3 minute cooldown
        )
    )

    /**
     * Start the monitoring loop.
     * Call this when the app starts or when the user enables automation.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) {
            Log.d(TAG, "Monitoring already active")
            return
        }

        Log.i(TAG, "Starting automation monitoring with ${rules.size} rules")
        _monitoringActive.value = true

        // Start sensor listener
        startSensorListening()

        // Start periodic rule evaluation
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    evaluateRules()
                } catch (e: Exception) {
                    Log.e(TAG, "Error evaluating rules", e)
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop all monitoring.
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping automation monitoring")
        monitorJob?.cancel()
        monitorJob = null

        stopSensorListening()

        _monitoringActive.value = false
    }

    /**
     * Enable a specific rule by name.
     */
    fun enableRule(name: String) {
        disabledRules.remove(name)
        Log.d(TAG, "Rule enabled: $name")
    }

    /**
     * Disable a specific rule by name.
     */
    fun disableRule(name: String) {
        disabledRules.add(name)
        Log.d(TAG, "Rule disabled: $name")
    }

    /**
     * Check if a rule is currently enabled.
     */
    fun isRuleEnabled(name: String): Boolean = !disabledRules.contains(name)

    /**
     * Get the status of all rules.
     */
    fun getRuleStatuses(): List<RuleStatus> {
        val now = System.currentTimeMillis()
        return rules.map { rule ->
            val lastFired = lastTriggered[rule.name]
            val onCooldown = lastFired != null && (now - lastFired) < rule.cooldownMs
            RuleStatus(
                name = rule.name,
                description = rule.description,
                enabled = !disabledRules.contains(rule.name),
                lastTriggeredMs = lastFired,
                onCooldown = onCooldown,
                requiresDisasterMode = rule.requiresDisasterMode
            )
        }
    }

    // ---------- Internal ----------

    /**
     * Evaluate all rules against current device state.
     */
    private suspend fun evaluateRules() {
        val deviceState = readDeviceState()
        val sensor = currentSensorSnapshot
        val now = System.currentTimeMillis()

        for (rule in rules) {
            // Skip disabled rules
            if (disabledRules.contains(rule.name)) continue

            // Skip rules that require disaster mode if we're not in it
            if (rule.requiresDisasterMode && !deviceState.emergencyMode) continue

            // Check cooldown
            val lastFired = lastTriggered[rule.name]
            if (lastFired != null && (now - lastFired) < rule.cooldownMs) continue

            // Evaluate condition
            try {
                if (rule.condition(deviceState, sensor)) {
                    Log.i(TAG, "Rule triggered: ${rule.name}")
                    lastTriggered[rule.name] = now
                    _lastTriggeredRule.value = rule.name

                    // Execute the action
                    withContext(Dispatchers.IO) {
                        try {
                            rule.action()
                        } catch (e: Exception) {
                            Log.e(TAG, "Rule action failed: ${rule.name}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rule condition check failed: ${rule.name}", e)
            }
        }
    }

    /**
     * Read current device state from system APIs.
     */
    private fun readDeviceState(): DeviceState {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent: Intent? = context.registerReceiver(null, intentFilter)

        val level = if (batteryIntent != null) {
            val l = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) (l * 100) / s else 100
        } else 100

        val isCharging = if (batteryIntent != null) {
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } else false

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val hasNetwork = if (cm != null) {
            val net = cm.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else false

        val agentPrefs = context.getSharedPreferences("safeguardian_agent", Context.MODE_PRIVATE)
        val emergencyMode = agentPrefs.getBoolean("emergency_mode", false)
        val powerMode = agentPrefs.getString("power_mode", "balanced") ?: "balanced"

        return DeviceState(
            batteryLevel = level,
            isCharging = isCharging,
            hasNetwork = hasNetwork,
            emergencyMode = emergencyMode,
            powerMode = powerMode
        )
    }

    /**
     * Get battery temperature in Celsius.
     */
    private fun getBatteryTemperature(): Double {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent: Intent? = context.registerReceiver(null, intentFilter)
        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (temp > 0) temp / 10.0 else 25.0
    }

    /**
     * Start listening to sensors for continuous monitoring.
     */
    private fun startSensorListening() {
        if (sensorListener != null) return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        val magnitude = sqrt((x * x + y * y + z * z).toDouble())

                        // Track earthquake-level shaking
                        val now = System.currentTimeMillis()
                        if (now - lastShakeResetTime > 5000) {
                            // Reset shake count every 5 seconds
                            recentShakeCount = 0
                            lastShakeResetTime = now
                        }
                        if (magnitude > EARTHQUAKE_MAGNITUDE_THRESHOLD) {
                            recentShakeCount++
                        }

                        // Update snapshot
                        val current = currentSensorSnapshot
                        currentSensorSnapshot = SensorSnapshot(
                            accelerometerMagnitude = magnitude,
                            pressure = current?.pressure,
                            light = current?.light,
                            proximity = current?.proximity,
                            timestamp = now
                        )
                    }
                    Sensor.TYPE_PRESSURE -> {
                        val current = currentSensorSnapshot
                        currentSensorSnapshot = (current ?: SensorSnapshot(9.81, null, null, null, 0)).copy(
                            pressure = event.values[0],
                            timestamp = System.currentTimeMillis()
                        )
                    }
                    Sensor.TYPE_LIGHT -> {
                        val current = currentSensorSnapshot
                        currentSensorSnapshot = (current ?: SensorSnapshot(9.81, null, null, null, 0)).copy(
                            light = event.values[0],
                            timestamp = System.currentTimeMillis()
                        )
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        val current = currentSensorSnapshot
                        currentSensorSnapshot = (current ?: SensorSnapshot(9.81, null, null, null, 0)).copy(
                            proximity = event.values[0],
                            timestamp = System.currentTimeMillis()
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorListener = listener

        // Register with a normal delay for continuous monitoring
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        Log.d(TAG, "Sensor listeners registered")
    }

    /**
     * Stop sensor listeners.
     */
    private fun stopSensorListening() {
        sensorListener?.let {
            sensorManager.unregisterListener(it)
            sensorListener = null
        }
        Log.d(TAG, "Sensor listeners unregistered")
    }

    data class RuleStatus(
        val name: String,
        val description: String,
        val enabled: Boolean,
        val lastTriggeredMs: Long?,
        val onCooldown: Boolean,
        val requiresDisasterMode: Boolean
    )
}
