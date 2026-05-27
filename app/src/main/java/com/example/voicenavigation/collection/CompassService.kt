package com.example.voicenavigation.collection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.roundToInt

class CompassService(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val accelValues = FloatArray(3)
    private val magnetValues = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private var callback: ((heading: Float, direction: String, isAligned: Boolean) -> Unit)? = null
    private var targetDirection = "N"

    private var lastHeading: Float? = null
    private var lastCallbackTime = 0L
    private val callbackInterval = 100L
    private val alignTolerance = 15f

    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    var isRunning = false
        private set

    fun start(callback: (heading: Float, direction: String, isAligned: Boolean) -> Unit) {
        this.callback = callback
        if (isRunning) return
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        isRunning = true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isRunning = false
        callback = null
    }

    fun setTargetDirection(direction: String) {
        targetDirection = direction
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelValues, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetValues, 0, 3)
        }

        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magnetValues)) return
        SensorManager.getOrientation(rotationMatrix, orientationValues)

        var heading = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
        if (heading < 0) heading += 360f

        // 跳变过滤
        lastHeading?.let { last ->
            var diff = abs(heading - last)
            if (diff > 180) diff = 360 - diff
            if (diff > 120) return
        }
        lastHeading = heading

        // 节流
        val now = System.currentTimeMillis()
        if (now - lastCallbackTime < callbackInterval) return
        lastCallbackTime = now

        val direction = getDirection(heading)
        val aligned = isAligned(targetDirection, heading)
        callback?.invoke(heading, direction, aligned)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun getDirection(angle: Float): String {
        val normalized = ((angle % 360) + 360) % 360
        val index = (normalized / 45f).roundToInt() % 8
        return directions[index]
    }

    private fun isAligned(target: String, heading: Float): Boolean {
        val targetIndex = directions.indexOf(target)
        if (targetIndex == -1) return false
        val targetAngle = targetIndex * 45f
        var diff = abs(heading - targetAngle)
        if (diff > 180) diff = 360 - diff
        return diff <= alignTolerance
    }
}
