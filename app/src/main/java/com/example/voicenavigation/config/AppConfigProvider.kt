package com.example.voicenavigation.config

import android.content.Context
import android.util.Log
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一配置读取入口。从 assets/app_constants.json 加载所有超时/阈值/数值配置。
 *
 * 首次访问时一次性加载并缓存。支持按 section（voice/gesture/navigation/...）读取。
 */
@Singleton
class AppConfigProvider @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "AppConfigProvider"
        private const val CONFIG_FILE = "app_constants.json"
    }

    private var config: JSONObject? = null

    private fun ensureLoaded(): JSONObject {
        config?.let { return it }
        return try {
            val json = context.assets.open(CONFIG_FILE).bufferedReader().readText()
            JSONObject(json).also { config = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $CONFIG_FILE", e)
            JSONObject()
        }
    }

    private fun section(name: String): JSONObject {
        return ensureLoaded().optJSONObject(name) ?: JSONObject()
    }

    // ── voice ──
    val voiceAutoStopTimeoutMs: Long get() = section("voice").optLong("auto_stop_timeout_ms", 8000)
    val voiceWatchdogTimeoutMs: Long get() = section("voice").optLong("watchdog_timeout_ms", 8000)
    val voiceToastDurationMs: Int get() = section("voice").optInt("toast_duration_ms", 1200)
    val voiceButtonRestoreDelayMs: Long get() = section("voice").optLong("button_restore_delay_ms", 5000)
    val voiceButtonRestoreDelayShortMs: Long get() = section("voice").optLong("button_restore_delay_short_ms", 2000)
    val voiceMinSearchTextLength: Int get() = section("voice").optInt("min_search_text_length", 2)
    val voiceTailFrameFallbackDelayMs: Long get() = section("voice").optLong("tail_frame_fallback_delay_ms", 300)

    // ── gesture ──
    val gestureLongPressDurationMs: Long get() = section("gesture").optLong("long_press_duration_ms", 500)
    val gestureMoveThresholdPx: Float get() = section("gesture").optDouble("move_cancel_threshold_px", 50.0).toFloat()
    val gestureVibrateDurationMs: Long get() = section("gesture").optLong("vibrate_duration_ms", 100)

    // ── navigation ──
    val navUpdateIntervalMs: Long get() = section("navigation").optLong("update_interval_ms", 3000)
    val navArrivalDistanceM: Float get() = section("navigation").optDouble("arrival_distance_m", 20.0).toFloat()
    val navOffRouteThresholdM: Float get() = section("navigation").optDouble("off_route_threshold_m", 50.0).toFloat()
    val navRouteSearchBackward: Int get() = section("navigation").optInt("route_search_backward", 5)
    val navRouteSearchForward: Int get() = section("navigation").optInt("route_search_forward", 50)
    val navMapZoomDefault: Float get() = section("navigation").optDouble("map_zoom_default", 15.0).toFloat()
    val navMapZoomDetail: Float get() = section("navigation").optDouble("map_zoom_detail", 16.0).toFloat()
    val navPoiPageSize: Int get() = section("navigation").optInt("poi_page_size", 10)
    val navDistanceFormatNearM: Float get() = section("navigation").optDouble("distance_format_near_m", 50.0).toFloat()
    val navDistanceFormatKmThreshold: Float get() = section("navigation").optDouble("distance_format_km_threshold", 1000.0).toFloat()
    val navDurationFormatMinThresholdS: Float get() = section("navigation").optDouble("duration_format_min_threshold_s", 60.0).toFloat()
    val navDurationFormatHourThresholdMin: Float get() = section("navigation").optDouble("duration_format_hour_threshold_min", 60.0).toFloat()

    // ── obstacle ──
    val obstacleLocalFrameIntervalMs: Long get() = section("obstacle").optLong("local_frame_interval_ms", 120)
    val obstacleModelInputSize: Int get() = section("obstacle").optInt("model_input_size", 640)
    val obstacleIouThreshold: Float get() = section("obstacle").optDouble("iou_threshold", 0.35).toFloat()
    val obstacleRiskAreaRatio: Float get() = section("obstacle").optDouble("risk_area_ratio", 0.30).toFloat()
    val obstacleRiskWidthRatio: Float get() = section("obstacle").optDouble("risk_width_ratio", 0.60).toFloat()
    val obstacleUrgencyHighThreshold: Float get() = section("obstacle").optDouble("urgency_high_threshold", 0.70).toFloat()
    val obstacleUrgencyMediumThreshold: Float get() = section("obstacle").optDouble("urgency_medium_threshold", 0.50).toFloat()
    val obstacleUrgencyLowThreshold: Float get() = section("obstacle").optDouble("urgency_low_threshold", 0.30).toFloat()
    val obstacleSmoothHistoryFrames: Int get() = section("obstacle").optInt("smooth_history_frames", 4)
    val obstacleMaxHistoryFrames: Int get() = section("obstacle").optInt("max_history_frames", 5)
    val obstacleJpegQuality: Int get() = section("obstacle").optInt("jpeg_quality", 80)

    // ── tts ──
    val ttsLanguage: String get() = section("tts").optString("language", "zh")
    val ttsPerson: Int get() = section("tts").optInt("person", 0)
    val ttsSpeed: Int get() = section("tts").optInt("speed", 5)
    val ttsPitch: Int get() = section("tts").optInt("pitch", 5)
    val ttsVolume: Int get() = section("tts").optInt("volume", 15)
    val ttsCacheFilename: String get() = section("tts").optString("cache_filename", "baidu_tts_temp.mp3")

    // ── network ──
    val networkConnectTimeoutS: Long get() = section("network").optLong("connect_timeout_s", 15)
    val networkReadTimeoutS: Long get() = section("network").optLong("read_timeout_s", 15)
    val networkLlmConnectTimeoutS: Long get() = section("network").optLong("llm_connect_timeout_s", 10)
    val networkLlmReadTimeoutS: Long get() = section("network").optLong("llm_read_timeout_s", 20)
    val networkLlmTemperature: Double get() = section("network").optDouble("llm_temperature", 0.1)
    val networkLlmMaxTokens: Int get() = section("network").optInt("llm_max_tokens", 256)
    val networkCloudDetectConnectTimeoutS: Long get() = section("network").optLong("cloud_detect_connect_timeout_s", 8)
    val networkCloudDetectReadTimeoutS: Long get() = section("network").optLong("cloud_detect_read_timeout_s", 15)
    val networkUdpDiscoveryTimeoutMs: Long get() = section("network").optLong("udp_discovery_timeout_ms", 5000)
    val networkUdpSocketTimeoutMs: Int get() = section("network").optInt("udp_socket_timeout_ms", 1000)
    val networkUdpBufferSize: Int get() = section("network").optInt("udp_buffer_size", 512)
}
