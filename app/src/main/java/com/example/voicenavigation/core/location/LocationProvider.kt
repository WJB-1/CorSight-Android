package com.example.voicenavigation.core.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.flow.Flow

/**
 * 统一定位源接口。
 *
 * - [observe] 连续监听，返回 [LocationResult] 流（含成功和错误）
 * - [getLastLocation] 单次获取
 * - [stop] 停止所有监听
 * - [isLocationEnabled] 检查设备位置服务是否开启
 */
interface LocationProvider {

    /** 获取最新位置（挂起直到拿到结果或超时）。 */
    suspend fun getLastLocation(timeoutMs: Long = 5000L): Location?

    /**
     * 持续监听位置变化。
     * 返回 [LocationResult] 的 Flow，包含成功位置和错误信息。
     */
    fun observe(intervalMs: Long = 3000L): Flow<LocationResult>

    /** 停止所有定位监听。 */
    fun stop()

    /** 检查设备位置服务是否开启。 */
    fun isLocationEnabled(): Boolean
}

/**
 * 定位结果密封类，成功时携带 Location，失败时携带错误信息。
 */
sealed class LocationResult {
    data class Success(val location: Location) : LocationResult()
    data class Error(val errorCode: Int, val message: String) : LocationResult()
}
