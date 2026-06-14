package com.example.voicenavigation.core.compass

import kotlinx.coroutines.flow.Flow

/**
 * 统一罗盘源接口。
 *
 * [observe] 返回持续更新的航向数据流，已做平滑和跳变过滤。
 * 调用方需在屏幕旋转时通知 [setScreenRotation] 以保证航向正确。
 */
interface CompassProvider {

    /** 航向数据流（持续）。 */
    fun observe(): Flow<HeadingData>

    /** 停止传感器监听。 */
    fun stop()

    /**
     * 通知当前屏幕旋转方向。
     * Activity 应在 onConfigurationChanged 中调用此方法。
     * @param rotation Surface.ROTATION_0 / 90 / 180 / 270
     */
    fun setScreenRotation(rotation: Int)
}

/**
 * @param heading   0~360°，已平滑，已做横屏校正
 * @param accuracy  传感器精度（Android SensorManager 定义的 accuracy 值）
 * @param timestamp 传感器事件时间戳（SystemClock.elapsedRealtimeNanos）
 */
data class HeadingData(
    val heading: Float,
    val accuracy: Int,
    val timestamp: Long
)
