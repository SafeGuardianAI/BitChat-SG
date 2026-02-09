package com.bitchat.android.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Pressure Sensor
 */
class PressureSensor(private val context: Context) : BaseSensor(SensorID.PRESSURE, "pressure", 5000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var pressureSensor: Sensor? = null
    private var pressureData: PressureData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        
        if (pressureSensor != null) {
            sensorManager?.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        pressureSensor = null
        pressureData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PRESSURE) {
            pressureData = PressureData(mbar = round(event.values[0] * 100) / 100f)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = pressureData
    
    override fun packData(data: Any): ByteArray {
        val pressureData = data as PressureData
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(pressureData.mbar).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val mbar = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).float
        pressureData = PressureData(mbar = mbar)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return pressureData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = pressureData ?: return null
        return mapOf(
            "icon" to "weather-cloudy",
            "name" to "Ambient Pressure",
            "values" to mapOf("mbar" to data.mbar)
        )
    }
    
    data class PressureData(val mbar: Float)
}

/**
 * Temperature Sensor
 */
class TemperatureSensor(private val context: Context) : BaseSensor(SensorID.TEMPERATURE, "temperature", 5000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var temperatureSensor: Sensor? = null
    private var temperatureData: TemperatureData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        temperatureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        
        if (temperatureSensor != null) {
            sensorManager?.registerListener(this, temperatureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        temperatureSensor = null
        temperatureData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_AMBIENT_TEMPERATURE) {
            temperatureData = TemperatureData(c = round(event.values[0] * 100) / 100f)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = temperatureData
    
    override fun packData(data: Any): ByteArray {
        val temperatureData = data as TemperatureData
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(temperatureData.c).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val c = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).float
        temperatureData = TemperatureData(c = c)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return temperatureData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = temperatureData ?: return null
        return mapOf(
            "icon" to "thermometer",
            "name" to "Temperature",
            "values" to mapOf("c" to data.c)
        )
    }
    
    data class TemperatureData(val c: Float)
}

/**
 * Humidity Sensor
 */
class HumiditySensor(private val context: Context) : BaseSensor(SensorID.HUMIDITY, "humidity", 5000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var humiditySensor: Sensor? = null
    private var humidityData: HumidityData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        humiditySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
        
        if (humiditySensor != null) {
            sensorManager?.registerListener(this, humiditySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        humiditySensor = null
        humidityData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_RELATIVE_HUMIDITY) {
            humidityData = HumidityData(percentRelative = round(event.values[0] * 100) / 100f)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = humidityData
    
    override fun packData(data: Any): ByteArray {
        val humidityData = data as HumidityData
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(humidityData.percentRelative).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val percentRelative = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).float
        humidityData = HumidityData(percentRelative = percentRelative)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return humidityData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = humidityData ?: return null
        return mapOf(
            "icon" to "water-percent",
            "name" to "Relative Humidity",
            "values" to mapOf("percent" to data.percentRelative)
        )
    }
    
    data class HumidityData(val percentRelative: Float)
}

/**
 * Magnetic Field Sensor
 */
class MagneticFieldSensor(private val context: Context) : BaseSensor(SensorID.MAGNETIC_FIELD, "magnetic_field", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var magneticFieldSensor: Sensor? = null
    private var magneticFieldData: MagneticFieldData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magneticFieldSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        if (magneticFieldSensor != null) {
            sensorManager?.registerListener(this, magneticFieldSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        magneticFieldSensor = null
        magneticFieldData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticFieldData = MagneticFieldData(
                x = round(event.values[0] * 1e6) / 1e6f,
                y = round(event.values[1] * 1e6) / 1e6f,
                z = round(event.values[2] * 1e6) / 1e6f
            )
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = magneticFieldData
    
    override fun packData(data: Any): ByteArray {
        val magneticFieldData = data as MagneticFieldData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeFloat(magneticFieldData.x)
        dos.writeFloat(magneticFieldData.y)
        dos.writeFloat(magneticFieldData.z)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        magneticFieldData = MagneticFieldData(x = x, y = y, z = z)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return magneticFieldData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = magneticFieldData ?: return null
        return mapOf(
            "icon" to "magnet",
            "name" to "Magnetic Field",
            "values" to mapOf(
                "x" to data.x,
                "y" to data.y,
                "z" to data.z
            )
        )
    }
    
    data class MagneticFieldData(val x: Float, val y: Float, val z: Float)
}

/**
 * Ambient Light Sensor
 */
class AmbientLightSensor(private val context: Context) : BaseSensor(SensorID.AMBIENT_LIGHT, "ambient_light", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lightData: LightData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        
        if (lightSensor != null) {
            sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        lightSensor = null
        lightData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            lightData = LightData(lux = round(event.values[0] * 100) / 100f)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = lightData
    
    override fun packData(data: Any): ByteArray {
        val lightData = data as LightData
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(lightData.lux).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val lux = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).float
        lightData = LightData(lux = lux)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return lightData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = lightData ?: return null
        return mapOf(
            "icon" to "white-balance-sunny",
            "name" to "Ambient Light",
            "values" to mapOf("lux" to data.lux)
        )
    }
    
    data class LightData(val lux: Float)
}

/**
 * Gravity Sensor
 */
class GravitySensor(private val context: Context) : BaseSensor(SensorID.GRAVITY, "gravity", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var gravityData: GravityData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        
        if (gravitySensor != null) {
            sensorManager?.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        gravitySensor = null
        gravityData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GRAVITY) {
            gravityData = GravityData(
                x = round(event.values[0] * 1e6) / 1e6f,
                y = round(event.values[1] * 1e6) / 1e6f,
                z = round(event.values[2] * 1e6) / 1e6f
            )
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = gravityData
    
    override fun packData(data: Any): ByteArray {
        val gravityData = data as GravityData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeFloat(gravityData.x)
        dos.writeFloat(gravityData.y)
        dos.writeFloat(gravityData.z)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        gravityData = GravityData(x = x, y = y, z = z)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return gravityData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = gravityData ?: return null
        return mapOf(
            "icon" to "arrow-down-thin-circle-outline",
            "name" to "Gravity",
            "values" to mapOf(
                "x" to data.x,
                "y" to data.y,
                "z" to data.z
            )
        )
    }
    
    data class GravityData(val x: Float, val y: Float, val z: Float)
}

/**
 * Angular Velocity (Gyroscope) Sensor
 */
class AngularVelocitySensor(private val context: Context) : BaseSensor(SensorID.ANGULAR_VELOCITY, "angular_velocity", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var angularVelocityData: AngularVelocityData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        if (gyroscopeSensor != null) {
            sensorManager?.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        gyroscopeSensor = null
        angularVelocityData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            angularVelocityData = AngularVelocityData(
                x = round(event.values[0] * 1e6) / 1e6f,
                y = round(event.values[1] * 1e6) / 1e6f,
                z = round(event.values[2] * 1e6) / 1e6f
            )
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = angularVelocityData
    
    override fun packData(data: Any): ByteArray {
        val angularVelocityData = data as AngularVelocityData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeFloat(angularVelocityData.x)
        dos.writeFloat(angularVelocityData.y)
        dos.writeFloat(angularVelocityData.z)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        angularVelocityData = AngularVelocityData(x = x, y = y, z = z)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return angularVelocityData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = angularVelocityData ?: return null
        return mapOf(
            "icon" to "orbit",
            "name" to "Angular Velocity",
            "values" to mapOf(
                "x" to data.x,
                "y" to data.y,
                "z" to data.z
            )
        )
    }
    
    data class AngularVelocityData(val x: Float, val y: Float, val z: Float)
}

/**
 * Acceleration Sensor
 */
class AccelerationSensor(private val context: Context) : BaseSensor(SensorID.ACCELERATION, "acceleration", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var accelerometerSensor: Sensor? = null
    private var accelerationData: AccelerationData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        if (accelerometerSensor != null) {
            sensorManager?.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        accelerometerSensor = null
        accelerationData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            accelerationData = AccelerationData(
                x = round(event.values[0] * 1e6) / 1e6f,
                y = round(event.values[1] * 1e6) / 1e6f,
                z = round(event.values[2] * 1e6) / 1e6f
            )
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = accelerationData
    
    override fun packData(data: Any): ByteArray {
        val accelerationData = data as AccelerationData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeFloat(accelerationData.x)
        dos.writeFloat(accelerationData.y)
        dos.writeFloat(accelerationData.z)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        accelerationData = AccelerationData(x = x, y = y, z = z)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return accelerationData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = accelerationData ?: return null
        return mapOf(
            "icon" to "arrow-right-thick",
            "name" to "Acceleration",
            "values" to mapOf(
                "x" to data.x,
                "y" to data.y,
                "z" to data.z
            )
        )
    }
    
    data class AccelerationData(val x: Float, val y: Float, val z: Float)
}

/**
 * Proximity Sensor
 */
class ProximitySensor(private val context: Context) : BaseSensor(SensorID.PROXIMITY, "proximity", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityData: ProximityData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        
        if (proximitySensor != null) {
            sensorManager?.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        proximitySensor = null
        proximityData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            proximityData = ProximityData(triggered = event.values[0] < event.sensor.maximumRange)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = proximityData
    
    override fun packData(data: Any): ByteArray {
        val proximityData = data as ProximityData
        return byteArrayOf(if (proximityData.triggered) 1 else 0)
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val triggered = packed[0] != 0.toByte()
        proximityData = ProximityData(triggered = triggered)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return proximityData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = proximityData ?: return null
        return mapOf(
            "icon" to "signal-distance-variant",
            "name" to "Proximity",
            "values" to mapOf("triggered" to data.triggered)
        )
    }
    
    data class ProximityData(val triggered: Boolean)
}




