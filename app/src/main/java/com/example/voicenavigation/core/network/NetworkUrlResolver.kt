package com.example.voicenavigation.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络 URL 智能解析器。
 *
 * 核心职责：根据当前网络环境自动选择最优服务端地址。
 * - 校园网 WiFi 且内网可达 → 内网直连（低延迟）
 * - 其他网络 → 外网中转
 *
 * 设计要点：
 * - 每次调用 [resolve] 时检测，结果带缓存（5 分钟 TTL）
 * - 网络类型变化时自动清除缓存
 * - 支持用户手动覆盖（预留给高级设置）
 * - 唯一管理服务端地址，消除硬编码 IP
 */
@Singleton
class NetworkUrlResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkUrlResolver"
        private const val SP_NAME = "corsight_config"
        private const val KEY_CUSTOM_URL = "custom_server_url"
        private const val KEY_MODE = "url_resolve_mode" // "auto" | "manual"
    }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(ServerConfig.HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ServerConfig.HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // 缓存
    @Volatile private var cachedUrl: String? = null
    @Volatile private var cacheTimestamp: Long = 0L
    @Volatile private var lastNetworkType: String? = null

    /**
     * 解析当前应使用的服务端 URL。
     *
     * 逻辑：
     * 1. 检查是否有用户手动设置（高级设置模式）
     * 2. 检查缓存是否有效
     * 3. 检测当前网络类型
     * 4. WiFi → 探测内网可达性 → 可达用内网，否则用外网
     * 5. 蜂窝/其他 → 直接用外网
     */
    fun resolve(): String {
        // 1. 手动模式
        val prefs = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "auto") ?: "auto"
        if (mode == "manual") {
            val custom = prefs.getString(KEY_CUSTOM_URL, null)
            if (!custom.isNullOrBlank()) return custom
        }

        // 2. 缓存有效 → 直接返回
        val now = System.currentTimeMillis()
        val currentNet = detectNetworkType()
        if (cachedUrl != null
            && now - cacheTimestamp < ServerConfig.CACHE_TTL_MS
            && lastNetworkType == currentNet
        ) {
            return cachedUrl!!
        }

        // 3. 根据网络类型选择
        val resolved = if (currentNet == "wifi") {
            resolveForWifi()
        } else {
            ServerConfig.EXTERNAL_URL
        }

        cachedUrl = resolved
        cacheTimestamp = now
        lastNetworkType = currentNet
        Log.d(TAG, "Resolved URL: $resolved (network=$currentNet)")
        return resolved
    }

    /**
     * 清除缓存，下次 resolve() 时重新探测。
     * 供网络变化监听调用。
     */
    fun invalidateCache() {
        cachedUrl = null
        cacheTimestamp = 0L
        lastNetworkType = null
    }

    /**
     * 获取当前解析模式。
     */
    fun getMode(): String {
        val prefs = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODE, "auto") ?: "auto"
    }

    /**
     * 设置手动 URL（高级设置用）。
     */
    fun setManualUrl(url: String) {
        val prefs = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MODE, "manual")
            .putString(KEY_CUSTOM_URL, url)
            .apply()
        invalidateCache()
    }

    /**
     * 切回自动模式。
     */
    fun setAutoMode() {
        val prefs = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MODE, "auto")
            .remove(KEY_CUSTOM_URL)
            .apply()
        invalidateCache()
    }

    /**
     * 获取当前解析结果的来源说明（UI 可选展示）。
     */
    fun resolveSource(): String {
        val mode = getMode()
        if (mode == "manual") return "手动设置"
        val url = resolve()
        return if (url == ServerConfig.INTERNAL_URL) "校园网内网" else "公网中转"
    }

    // ===== 内部实现 =====

    private fun resolveForWifi(): String {
        // 探测内网 /health 端点
        val internalHealth = "${ServerConfig.INTERNAL_URL}${ServerConfig.HEALTH_CHECK_PATH}"
        return try {
            val request = Request.Builder()
                .url(internalHealth)
                .get()
                .build()
            probeClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Internal server reachable")
                    ServerConfig.INTERNAL_URL
                } else {
                    Log.d(TAG, "Internal server responded ${response.code}, falling back to external")
                    ServerConfig.EXTERNAL_URL
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Internal server unreachable: ${e.message}, falling back to external")
            ServerConfig.EXTERNAL_URL
        }
    }

    private fun detectNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi" // 以太网视同有线
            else -> "other"
        }
    }
}
