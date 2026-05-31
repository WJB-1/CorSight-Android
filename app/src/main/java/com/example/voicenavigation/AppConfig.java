package com.example.voicenavigation;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppConfig {
    private static final String PREFS_NAME = "corsight_config";
    public static final String KEY_PREVIEW_SERVER_BASE_URL = "server_base_url";
    public static final String KEY_DETECTION_SERVER_BASE_URL = "detection_server_base_url";

    private AppConfig() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
