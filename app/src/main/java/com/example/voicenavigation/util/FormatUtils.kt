package com.example.voicenavigation.util

import com.example.voicenavigation.config.AppConfigProvider

/**
 * 距离/时间格式化工具。所有阈值从 [AppConfigProvider] 读取，零硬编码。
 */
object FormatUtils {

    /**
     * 格式化距离：50m 以内显示"即将到达"，1000m 以内显示"Xm"，否则"X.Xkm"。
     */
    fun formatDistance(meters: Float, config: AppConfigProvider): String {
        val nearM = config.navDistanceFormatNearM
        val kmThreshold = config.navDistanceFormatKmThreshold
        return when {
            meters < nearM -> "即将到达"
            meters < kmThreshold -> "${meters.toInt()}m"
            else -> String.format("%.1fkm", meters / kmThreshold)
        }
    }

    /**
     * 格式化时间：60s 以内显示"1分钟"，60min 以内显示"X分钟"，否则"X小时X分钟"。
     */
    fun formatDuration(seconds: Float, config: AppConfigProvider): String {
        val minThreshold = config.navDurationFormatMinThresholdS
        val hourThreshold = config.navDurationFormatHourThresholdMin
        if (seconds < minThreshold) return "1分钟"
        val minutes = (seconds / minThreshold).toInt()
        if (minutes < hourThreshold) return "${minutes}分钟"
        return "${minutes / hourThreshold}小时${minutes % hourThreshold}分钟"
    }
}
