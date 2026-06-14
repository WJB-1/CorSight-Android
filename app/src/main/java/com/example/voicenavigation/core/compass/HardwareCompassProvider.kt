package com.example.voicenavigation.core.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 基于加速度计 + 磁力计的罗盘实现。
 *
 * 职责：读取传感器 → 坐标系重映射（横屏兼容） → 航向平滑 → 输出 [HeadingData]。
 * 不包含任何八方向对齐逻辑（那是 UI 层职责）。
 */
@Singleton
class HardwareCompassProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : CompassProvider {

    companion object {
        private const val SMOOTHING_FACTOR = 0.1f   // EMA 平滑因子
        private const val JUMP_REJECT_DEG = 120f    // 跳变拒绝阈值（°）
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // 传感器原始数据
    private val accelValues = FloatArray(3)
    private val magnetValues = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    // 平滑状态
    private var smoothedHeading: Float? = null
    private var lastRawHeading: Float? = null

    // 当前屏幕旋转方向，由外部 Activity 通过 setScreenRotation 更新
    @Volatile
    private var screenRotation: Int = Surface.ROTATION_0

    private var currentAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_LOW

    override fun observe(): Flow<HeadingData> = callbackFlow {
        smoothedHeading = null
        lastRawHeading = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelValues, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetValues, 0, 3)
                }

                if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magnetValues)) return

                // 根据屏幕旋转方向重映射坐标系，使 heading 指向屏幕顶部（= 相机镜头方向）
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
                    else -> rotationMatrix // ROTATION_0 竖屏，无需 remap
                }

                SensorManager.getOrientation(orientedMatrix, orientation)
                var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (heading < 0) heading += 360f

                // 跳变拒绝：罗盘突变 > 120° 视为干扰，丢弃
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
    }

    override fun stop() {
        // Flow collector cancellation triggers awaitClose which unregisters listeners.
        // This method exists for explicit lifecycle control but is typically a no-op
        // since the Flow is the primary lifecycle-bound consumer.
    }

    override fun setScreenRotation(rotation: Int) {
        screenRotation = rotation
    }
}
