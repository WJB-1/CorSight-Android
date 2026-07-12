package com.example.voicenavigation.config

/**
 * 编译时常量 — 不可热更新的配置项。
 *
 * 可热更新的超时/阈值/数值请放在 assets/app_constants.json，
 * 通过 [AppConfigProvider] 读取。
 */
object AppConstants {

    // ── 数据库 ──
    const val DB_NAME = "voice_navigation.db"

    // ── SharedPreferences ──
    const val SP_NAME = "corsight_config"
    const val SP_KEY_PREVIEW_SERVER = "server_base_url"
    const val SP_KEY_DETECTION_SERVER = "detection_server_base_url"
    const val SP_KEY_LLM_ENABLED = "llm_enabled"
    const val SP_KEY_LLM_BASE_URL = "llm_base_url"
    const val SP_KEY_LLM_API_KEY = "llm_api_key"
    const val SP_KEY_LLM_MODEL = "llm_model"
    const val SP_KEY_USE_EXTERNAL_DEVICE = "use_external_device"

    // ── 导航 ──
    const val DEFAULT_ROUTE_ID = "gzdx_stadium"

    // ── LLM 默认值 ──
    const val LLM_DEFAULT_MODEL = "deepseek-chat"
    const val LLM_DEFAULT_BASE_URL = "https://api.deepseek.com"
    const val LLM_API_PATH = "v1/chat/completions"
    const val LLM_TOOL_CHOICE = "auto"

    // ── 网络默认 URL ──
    // 由 core/network/ServerConfig.kt 统一管理，此处保留为兼容引用
    @Deprecated("Use core.network.ServerConfig via NetworkUrlResolver", ReplaceWith("ServerConfig.EXTERNAL_URL"))
    const val PREVIEW_DEFAULT_BASE_URL = "http://106.55.149.50:5741"
    const val PREVIEW_API_PATH = "/api/navigation/preview"
    const val PREVIEW_FIXED_API_PATH_PREFIX = "/api/navigation/preview/fixed/"

    // ── 百度 TTS URL ──
    const val BAIDU_TTS_TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"
    const val BAIDU_TTS_AUDIO_URL = "https://tsn.baidu.com/text2audio"
    const val BAIDU_TTS_CUID_FALLBACK = "voice_navigation_app"

    // ── 金造村参观模式 ──
    /** 金造村中心坐标（高德 GCJ-02） */
    const val JINZAO_VILLAGE_LAT = 23.0480
    const val JINZAO_VILLAGE_LNG = 113.1169
    /** 路线 ID（给 path-guide 后端匹配用，可为空让后端自动选） */
    const val JINZAO_ROUTE_QIYI = "jinzao_qiyi_square"
    const val JINZAO_ROUTE_SILINGBU = "jinzao_silingbu"
    /** path-guide 定位上报间隔（毫秒），对应后端 loop 2s 频率 */
    const val JINZAO_PATH_GUIDE_INTERVAL_MS = 2000L
    /** vlm-guide 自动触发间隔（毫秒），15 秒一次 */
    const val JINZAO_VLM_GUIDE_INTERVAL_MS = 15_000L

    // ── 广播 Action ──
    const val BROADCAST_ACTION_STOP_OBSTACLE = "com.example.voicenavigation.ACTION_STOP_OBSTACLE"

    // ── 网络端口 ──
    const val UDP_DISCOVERY_PORT = 8888
    const val DEFAULT_STREAM_PORT = 8080

    // ── 地图 ──
    const val EARTH_RADIUS_M = 6371000.0
}
