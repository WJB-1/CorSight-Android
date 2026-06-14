package com.example.voicenavigation.core.location

import android.content.Context
import android.location.Location
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 高德定位实现。
 *
 * 单例，内部维护一个 AMapLocationClient 实例：
 * - [observe] 使用连续定位模式
 * - [getLastLocation] 使用单次定位模式
 *
 * 注意：同一时刻只应使用一种模式，切换时内部自动重新配置。
 */
@Singleton
class AMapLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationProvider {

    companion object {
        private const val TAG = "AMapLocationProvider"
    }

    private var client: AMapLocationClient? = null
    private val running = AtomicBoolean(false)

    private fun ensureClient(): AMapLocationClient {
        return client ?: AMapLocationClient(context).also { client = it }
    }

    private fun buildOption(intervalMs: Long, once: Boolean): AMapLocationClientOption {
        return AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = once
            this.interval = intervalMs
            isNeedAddress = false
            httpTimeOut = 10_000L
        }
    }

    override suspend fun getLastLocation(timeoutMs: Long): Location? {
        return suspendCancellableCoroutine { cont ->
            val c = ensureClient()
            c.stopLocation()
            c.setLocationOption(buildOption(timeoutMs, once = true))
            c.setLocationListener { aMapLoc ->
                c.stopLocation()
                running.set(false)
                if (aMapLoc == null || aMapLoc.errorCode != 0) {
                    Log.w(TAG, "getLastLocation failed: ${aMapLoc?.errorInfo}")
                    if (cont.isActive) cont.resume(null)
                    return@setLocationListener
                }
                val loc = Location("amap").apply {
                    latitude = aMapLoc.latitude
                    longitude = aMapLoc.longitude
                    accuracy = aMapLoc.accuracy
                    time = aMapLoc.time
                }
                if (cont.isActive) cont.resume(loc)
            }
            running.set(true)
            c.startLocation()

            cont.invokeOnCancellation {
                c.stopLocation()
                running.set(false)
            }
        }
    }

    override fun observe(intervalMs: Long): Flow<Location> = callbackFlow {
        val c = ensureClient()
        c.stopLocation()
        c.setLocationOption(buildOption(intervalMs, once = false))
        c.setLocationListener { aMapLoc ->
            if (aMapLoc == null || aMapLoc.errorCode != 0) {
                Log.w(TAG, "observe failed: ${aMapLoc?.errorInfo}")
                return@setLocationListener
            }
            val loc = Location("amap").apply {
                latitude = aMapLoc.latitude
                longitude = aMapLoc.longitude
                accuracy = aMapLoc.accuracy
                time = aMapLoc.time
            }
            trySend(loc)
        }
        running.set(true)
        c.startLocation()

        awaitClose {
            c.stopLocation()
            running.set(false)
        }
    }

    override fun stop() {
        client?.stopLocation()
        running.set(false)
    }
}
