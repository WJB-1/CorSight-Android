package com.example.voicenavigation.core.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 高德定位实现。
 *
 * 单例，内部维护一个 AMapLocationClient 实例。
 * [observe] 返回共享流（shareIn），多个订阅者共用同一个定位会话，
 * 无人订阅 2 秒后自动停止定位以节省电量。
 * [getLastLocation] 单次定位模式，与 observe 互斥。
 */
@Singleton
class AMapLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationProvider {

    companion object {
        private const val TAG = "AMapLocationProvider"
    }

    private var client: AMapLocationClient? = null

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

    /**
     * 共享定位流。底层只启动一次定位，多个 Fragment 订阅时共享数据。
     * - replay = 1：新订阅者立即拿到最后一次位置
     * - WhileSubscribed(2000)：最后一个订阅者取消后 2 秒停止定位
     */
    private val sharedLocationFlow = callbackFlow {
        Log.d(TAG, "sharedLocationFlow: starting location")
        val c = ensureClient()
        c.stopLocation()
        c.setLocationOption(buildOption(intervalMs = 3000L, once = false))
        c.setLocationListener { aMapLoc ->
            if (aMapLoc == null || aMapLoc.errorCode != 0) {
                val code = aMapLoc?.errorCode ?: -1
                val info = aMapLoc?.errorInfo ?: "unknown error"
                Log.w(TAG, "observe error: code=$code info=$info")
                trySend(LocationResult.Error(code, info))
                return@setLocationListener
            }
            val loc = Location("amap").apply {
                latitude = aMapLoc.latitude
                longitude = aMapLoc.longitude
                accuracy = aMapLoc.accuracy
                time = aMapLoc.time
            }
            trySend(LocationResult.Success(loc))
        }
        c.startLocation()

        awaitClose {
            Log.d(TAG, "sharedLocationFlow: stopping location")
            c.stopLocation()
        }
    }.shareIn(
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 2000L),
        replay = 1
    )

    override fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun getLastLocation(timeoutMs: Long): Location? {
        return suspendCancellableCoroutine { cont ->
            val c = ensureClient()
            c.stopLocation()
            c.setLocationOption(buildOption(timeoutMs, once = true))
            c.setLocationListener { aMapLoc ->
                c.stopLocation()
                if (aMapLoc == null || aMapLoc.errorCode != 0) {
                    Log.w(TAG, "getLastLocation failed: code=${aMapLoc?.errorCode} info=${aMapLoc?.errorInfo}")
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
            c.startLocation()

            cont.invokeOnCancellation {
                c.stopLocation()
            }
        }
    }

    override fun observe(intervalMs: Long): Flow<LocationResult> = sharedLocationFlow

    override fun stop() {
        client?.stopLocation()
    }
}
