package com.example.voicenavigation.core.location

import android.location.Location
import kotlinx.coroutines.flow.Flow

/**
 * 统一定位源接口。
 *
 * - [observe] 连续监听（供导航使用）
 * - [getLastLocation] 单次获取（供采样/补拍使用）
 * - [stop] 停止所有监听
 */
interface LocationProvider {

    /** 获取最新位置（挂起直到拿到结果或超时）。 */
    suspend fun getLastLocation(timeoutMs: Long = 5000L): Location?

    /** 持续监听位置变化。 */
    fun observe(intervalMs: Long = 3000L): Flow<Location>

    /** 停止所有定位监听。 */
    fun stop()
}
