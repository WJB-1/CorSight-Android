package com.example.voicenavigation.core.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 基于加速度计 + 磁力计的罗盘实现。
 *
 * 单例，返回共享流（shareIn），多个订阅者共用同一个传感器会话。
 * 平滑状态为 flow 内部局部变量，多订阅者不会互相覆盖。
 *
 * 职责：读取传感器 → 坐标系重映射（横屏兼容） → 航向平滑 → 输出 [HeadingData]。
 */
@Singleton
class HardwareCompassProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : CompassProvider {

    companion object {
        private const val SMOOTHING_FACTOR = 0.1f
        private const val JUMP_REJECT_DEG = 120f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    @Volatile
    private var screenRotation: Int = Surface.ROTATION_0

    /**
     * 共享罗盘流。底层只注册一次传感器监听，多个 Fragment 订阅时共享数据。
     * - replay = 1：新订阅者立即拿到最后一次航向
     * - WhileSubscribed(1000)：最后一个订阅者取消后 1 秒注销传感器
     */
    private val sharedCompassFlow = callbackFlow {
        // 传感器原始数据（局部，不共享）
        val accelValues = FloatArray(3)
        val magnetValues = FloatArray(3)
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        // 平滑状态（局部，每个 flow 实例独立）
        var smoothedHeading: Float? = null
        var lastRawHeading: Float? = null
        var currentAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelValues, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetValues, 0, 3)
                }

                if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magnetValues)) return

                // 根据屏幕旋转方向重映射坐标系
                val orientedMatrix = when (screenRotation) {
                    Surface.ROTATION_90 -> {
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix
                        )
                        remappedMatrix
                    }
                    Surface.ROTATION_270 -> {
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix
                        )
                        remappedMatrix
                    }
                    Surface.ROTATION_180 -> {
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix
                        )
                        remappedMatrix
                    }
                    else -> rotationMatrix
                }

                SensorManager.getOrientation(orientedMatrix, orientation)
                var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (heading < 0) heading += 360f

                // 跳变拒绝
                lastRawHeading?.let { last ->
                    var diff = abs(heading - last)
                    if (diff > 180) diff = 360 - diff
                    if (diff > JUMP_REJECT_DEG) return
                }
                lastRawHeading = heading

                // EMA 指数平滑
                smoothedHeading = if (smoothedHeading == null) {
                    heading
                } else {
                    var diff = heading - smoothedHeading!!
                    if (diff > 180) diff -= 360
                    if (diff < -180) diff += 360
                    (smoothedHeading!! + SMOOTHING_FACTOR * diff + 360) % 360
                }

                trySend(
                    HeadingData(
                        heading = smoothedHeading!!,
                        accuracy = currentAccuracy,
                        timestamp = event.timestamp
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ACCELEROMETER || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    currentAccuracy = accuracy
                }
            }
        }

        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }.shareIn(
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000L),
        replay = 1
    )

    override fun observe(): Flow<HeadingData> = sharedCompassFlow

    override fun stop() {
        // shareIn 的 WhileSubscribed 会自动管理传感器生命周期
    }

    override fun setScreenRotation(rotation: Int) {
        screenRotation = rotation
    }
}
