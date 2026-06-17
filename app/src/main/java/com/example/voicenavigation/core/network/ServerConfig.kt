package com.example.voicenavigation.core.network

/**
 * 服务端地址配置。
 *
 * 唯一定义点，全项目通过 NetworkUrlResolver 获取实际使用的 URL。
 * 消除硬编码 IP 散落在 AppConstants / BuildConfig / Settings hint 等多处的问题。
 */
object ServerConfig {

    /** 外网中转服务器（公网，始终可达） */
    const val EXTERNAL_URL = "http://114.132.86.138:5000"

    /** 内网服务器（校园网直连，延迟低） */
    const val INTERNAL_URL = "http://172.23.206.119:5741"

    /** 连通性探测路径 */
    const val HEALTH_CHECK_PATH = "/health"

    /** 探测超时（毫秒） */
    const val HEALTH_CHECK_TIMEOUT_MS = 2000L

    /** 探测结果缓存有效期（毫秒）——5 分钟内不重复探测 */
    const val CACHE_TTL_MS = 5 * 60 * 1000L
}
