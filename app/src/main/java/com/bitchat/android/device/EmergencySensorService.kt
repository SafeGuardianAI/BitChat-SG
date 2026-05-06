package com.bitchat.android.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.sqrt

/**
 * Emergency Sensor Service
 *
 * Monitors Android hardware sensors for disaster-relevant data:
 * - **Accelerometer**: earthquake / shaking detection
 * - **Barometer**: altitude / floor estimation (if available)
 * - **Light sensor**: trapped-under-rubble detection
 * - **Proximity**: phone-to-face detection (voice call mode)
 * - **Step counter**: mobility assessment
 *
 * All sensor readings are aggregated into a [SensorSnapshot] exposed via [StateFlow].
 * Earthquake detection uses a ring buffer of recent accelerometer magnitudes and
 * flags sustained high-amplitude oscillation.
 */
class EmergencySensorService(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "EmergencySensor"

        // Earthquake detection thresholds
        /** Magnitude (m/s^2) above gravity that counts as a "shake" sample. */
        private const val SHAKE_THRESHOLD = 3.0f
        /** Number of recent samples that must exceed the threshold. */
        private const val SHAKE_COUNT_THRESHOLD = 15
        /** Ring-buffer size (approx 2 s at SENSOR_DELAY_GAME). */
        private const val RING_BUFFER_SIZE = 100

        /** Light level (lux) below which we consider the environment "dark". */
        private const val DARKNESS_LUX = 10f

        /** Standard atmospheric pressure at sea level (hPa). */
        private const val SEA_LEVEL_PRESSURE = SensorManager.PRESSURE_STANDARD_ATMOSPHERE

        /** Approximate height per floor (meters). */
        private const val METERS_PER_FLOOR = 3.0f
    }

    @Serializable
    data class SensorSnapshot(
        val accelerometerX: Float = 0f,
        val accelerometerY: Float = 0f,
        val accelerometerZ: Float = 0f,
        val isShaking: Boolean = false,
        /** Atmospheric pressure in hPa (null if barometer unavailable). */
        val pressure: Float? = null,
        /** Estimated floor relative to ground level (null if barometer unavailable). */
        val estimatedFloor: Int? = null,
        /** Ambient light in lux (null if sensor unavailable). */
        val lightLevel: Float? = null,
        /** True when ambient light < [DARKNESS_LUX]. */
        val isInDarkness: Boolean = false,
        /** Proximity sensor near flag (phone to face). */
        val proximityNear: Boolean = false,
        /** Steps since device boot (from TYPE_STEP_COUNTER). */
        val stepCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val _sensorData = MutableStateFlow(SensorSnapshot())
    val sensorData: StateFlow<SensorSnapshot> = _sensorData.asStateFlow()

    // Ring buffer for accelerometer magnitudes (earthquake detection)
    private val magnitudeBuffer = FloatArray(RING_BUFFER_SIZE)
    private var bufferIndex = 0
    private var bufferFilled = false

    // Baseline pressure captured on first reading (used for relative floor estimation)
    private var baselinePressure: Float? = null

    // Current raw values (written from sensor callbacks)
    @Volatile private var accelX = 0f
    @Volatile private var accelY = 0f
    @Volatile private var accelZ = 0f
    @Volatile private var currentPressure: Float? = null
    @Volatile private var currentLight: Float? = null
    @Volatile private var proximityNear = false
    @Volatile private var stepCount = 0

    private var isMonitoring = false

    // ---- Lifecycle ----

    /**
     * Register all available emergency-relevant sensors.
     */
    fun startMonitoring() {
        if (isMonitoring) return
        val sm = sensorManager ?: run {
            Log.w(TAG, "SensorManager unavailable")
            return
        }

        registerSensor(sm, Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
        registerSensor(sm, Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL)
        registerSensor(sm, Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL)
        registerSensor(sm, Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_NORMAL)
        registerSensor(sm, Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL)

        isMonitoring = true
        Log.d(TAG, "Sensor monitoring started")
    }

    /**
     * Unregister all sensor listeners.
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        sensorManager?.unregisterListener(this)
        isMonitoring = false
        baselinePressure = null
        bufferIndex = 0
        bufferFilled = false
        Log.d(TAG, "Sensor monitoring stopped")
    }

    /**
     * Return the latest sensor snapshot without waiting for the next event.
     */
    fun getSnapshot(): SensorSnapshot = _sensorData.value

    // ---- Earthquake detection ----

    /**
     * True when sustained high-amplitude oscillation is detected.
     * This is a heuristic: at least [SHAKE_COUNT_THRESHOLD] of the last
     * [RING_BUFFER_SIZE] accelerometer samples exceeded [SHAKE_THRESHOLD] m/s^2
     * above gravity.
     */
    fun isEarthquakeDetected(): Boolean {
        val limit = if (bufferFilled) RING_BUFFER_SIZE else bufferIndex
        if (limit == 0) return false
        var count = 0
        for (i in 0 until limit) {
            if (magnitudeBuffer[i] > SHAKE_THRESHOLD) count++
        }
        return count >= SHAKE_COUNT_THRESHOLD
    }

    // ---- Trapped detection ----

    /**
     * Heuristic for "likely trapped": prolonged darkness and no movement.
     * Darkness is determined by the light sensor (< [DARKNESS_LUX]).
     * No movement is determined by the accelerometer (no shaking and magnitude
     * close to gravity, i.e., the device is stationary).
     */
    fun isTrappedLikely(): Boolean {
        val snapshot = _sensorData.value
        val inDarkness = snapshot.isInDarkness
        val noMovement = !snapshot.isShaking
        return inDarkness && noMovement
    }

    // ---- SensorEventListener ----

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_PRESSURE -> handlePressure(event)
            Sensor.TYPE_LIGHT -> handleLight(event)
            Sensor.TYPE_PROXIMITY -> handleProximity(event)
            Sensor.TYPE_STEP_COUNTER -> handleStepCounter(event)
        }

        // Rebuild snapshot
        rebuildSnapshot()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op; accuracy changes don't affect emergency heuristics.
    }

    // ---- Sensor handlers ----

    private fun handleAccelerometer(event: SensorEvent) {
        accelX = event.values[0]
        accelY = event.values[1]
        accelZ = event.values[2]

        // Magnitude minus gravity gives "excess" acceleration
        val magnitude = sqrt(
            accelX * accelX + accelY * accelY + accelZ * accelZ
        ) - SensorManager.GRAVITY_EARTH

        magnitudeBuffer[bufferIndex] = magnitude
        bufferIndex = (bufferIndex + 1) % RING_BUFFER_SIZE
        if (bufferIndex == 0) bufferFilled = true
    }

    private fun handlePressure(event: SensorEvent) {
        val p = event.values[0]
        currentPressure = p
        if (baselinePressure == null) {
            baselinePressure = p
        }
    }

    private fun handleLight(event: SensorEvent) {
        currentLight = event.values[0]
    }

    private fun handleProximity(event: SensorEvent) {
        val maxRange = event.sensor.maximumRange
        proximityNear = event.values[0] < maxRange
    }

    private fun handleStepCounter(event: SensorEvent) {
        stepCount = event.values[0].toInt()
    }

    // ---- Snapshot assembly ----

    private fun rebuildSnapshot() {
        val pressure = currentPressure
        val light = currentLight

        // Estimate relative floor from baseline pressure
        val estimatedFloor: Int? = if (pressure != null && baselinePressure != null) {
            val altitudeDelta = SensorManager.getAltitude(SEA_LEVEL_PRESSURE, baselinePressure!!) -
                    SensorManager.getAltitude(SEA_LEVEL_PRESSURE, pressure)
            (altitudeDelta / METERS_PER_FLOOR).toInt()
        } else {
            null
        }

        _sensorData.value = SensorSnapshot(
            accelerometerX = accelX,
            accelerometerY = accelY,
            accelerometerZ = accelZ,
            isShaking = isEarthquakeDetected(),
            pressure = pressure,
            estimatedFloor = estimatedFloor,
            lightLevel = light,
            isInDarkness = light != null && light < DARKNESS_LUX,
            proximityNear = proximityNear,
            stepCount = stepCount,
            timestamp = System.currentTimeMillis()
        )
    }

    // ---- Helpers ----

    private fun registerSensor(sm: SensorManager, type: Int, delay: Int) {
        val sensor = sm.getDefaultSensor(type)
        if (sensor != null) {
            sm.registerListener(this, sensor, delay)
            Log.d(TAG, "Registered sensor: ${sensor.name}")
        } else {
            Log.d(TAG, "Sensor type $type not available on this device")
        }
    }
}
