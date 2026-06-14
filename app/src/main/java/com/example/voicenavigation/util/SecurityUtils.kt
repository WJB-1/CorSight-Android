package com.example.voicenavigation.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.voicenavigation.BuildConfig
import java.security.MessageDigest

/**
 * 安全/签名相关工具方法。
 */
object SecurityUtils {

    private const val TAG = "SecurityUtils"

    /**
     * 获取 APK 签名的 SHA1 指纹。
     */
    fun getAppSignatureSha1(context: Context): String {
        return try {
            val packageInfo = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val signatures = packageInfo.signatures ?: return "unknown"
            if (signatures.isEmpty()) return "unknown"
            val digest = MessageDigest.getInstance("SHA1")
            val sha1 = digest.digest(signatures[0].toByteArray())
            sha1.joinToString(":") { String.format("%02X", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read app signature SHA1", e)
            "unknown"
        }
    }

    /**
     * 检查高德 API Key 是否已配置。
     */
    fun hasValidAmapKey(): Boolean {
        return !BuildConfig.AMAP_API_KEY.isNullOrBlank()
    }
}
