package com.example.voicenavigation

import android.content.Context
import android.content.SharedPreferences

object AppConfig {
    private const val PREFS_NAME = "corsight_config"

    const val KEY_PREVIEW_SERVER_BASE_URL = "server_base_url"
    const val KEY_DETECTION_SERVER_BASE_URL = "detection_server_base_url"

    // LLM Function Calling 配置
    const val KEY_LLM_ENABLED = "llm_enabled"
    const val KEY_LLM_BASE_URL = "llm_base_url"
    const val KEY_LLM_API_KEY = "llm_api_key"
    const val KEY_LLM_MODEL = "llm_model"

    @JvmStatic
    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun normalizeBaseUrl(baseUrl: String?): String {
        if (baseUrl.isNullOrEmpty()) return ""
        var normalized = baseUrl.trim()
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized
    }
}
