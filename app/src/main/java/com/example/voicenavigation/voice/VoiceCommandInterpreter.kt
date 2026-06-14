package com.example.voicenavigation.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 语音指令意图解析器。
 *
 * 将语音识别得到的文本解析为 [VoiceCommand]。
 * 采用**本地关键词匹配**策略，无需联网即可实时响应。
 *
 * 关键词从 `assets/voice_keywords.json` 加载，新增/修改关键词只需编辑 JSON，无需重编译。
 */
class VoiceCommandInterpreter(private val context: Context? = null) {

    companion object {
        private const val TAG = "VoiceCommandInterpreter"
        private const val KEYWORDS_FILE = "voice_keywords.json"
    }

    private val keywords: Map<String, List<String>> = loadKeywords()

    private fun loadKeywords(): Map<String, List<String>> {
        val ctx = context ?: return emptyMap()
        return try {
            val json = ctx.assets.open(KEYWORDS_FILE).bufferedReader().readText()
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { key ->
                val arr = obj.getJSONArray(key)
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $KEYWORDS_FILE, using empty keywords", e)
            emptyMap()
        }
    }

    private fun getKeywords(key: String): List<String> = keywords[key] ?: emptyList()

    /**
     * 解析语音文本，返回结构化的语音指令。
     */
    fun interpret(text: String): VoiceCommand {
        val cleaned = cleanText(text)
        Log.d(TAG, "Interpreting: \"$cleaned\"")

        // 1. 精确匹配：停止导航（优先级高）
        if (containsAny(cleaned, getKeywords("stop_navigation"))) {
            return VoiceCommand(VoiceCommand.Type.STOP_NAVIGATION, null, cleaned)
        }

        // 2. 停止避障
        if (containsAny(cleaned, getKeywords("stop_obstacle"))) {
            return VoiceCommand(VoiceCommand.Type.STOP_OBSTACLE_AVOIDANCE, null, cleaned)
        }

        // 3. 开始避障
        if (containsAny(cleaned, getKeywords("start_obstacle"))) {
            return VoiceCommand(VoiceCommand.Type.START_OBSTACLE_AVOIDANCE, null, cleaned)
        }

        // 4. 我在哪里
        if (containsAny(cleaned, getKeywords("where_am_i"))) {
            return VoiceCommand(VoiceCommand.Type.WHERE_AM_I, null, cleaned)
        }

        // 5. 重复播报
        if (containsAny(cleaned, getKeywords("repeat"))) {
            return VoiceCommand(VoiceCommand.Type.REPEAT_LAST, null, cleaned)
        }

        // 6. 预览路线
        if (containsAny(cleaned, getKeywords("preview_route"))) {
            return VoiceCommand(VoiceCommand.Type.PREVIEW_ROUTE, null, cleaned)
        }

        // 7. 查询状态
        if (containsAny(cleaned, getKeywords("query_status"))) {
            return VoiceCommand(VoiceCommand.Type.QUERY_STATUS, null, cleaned)
        }

        // 8. 导航意图：提取目的地
        val destination = extractDestination(cleaned)
        if (!destination.isNullOrEmpty()) {
            Log.d(TAG, "Parsed NAVIGATE_TO destination: $destination")
            return VoiceCommand(VoiceCommand.Type.NAVIGATE_TO, destination, cleaned)
        }

        // 9. fallback：普通文本搜索
        if (cleaned.length >= 2) {
            Log.d(TAG, "Falling back to TEXT_SEARCH: $cleaned")
            return VoiceCommand(VoiceCommand.Type.TEXT_SEARCH, cleaned, cleaned)
        }

        Log.d(TAG, "Unknown command: $cleaned")
        return VoiceCommand(VoiceCommand.Type.UNKNOWN, null, cleaned)
    }

    private fun cleanText(text: String): String {
        return text.trim()
            .replace(Regex("^[。，、！；：,.!?;:]+"), "")
            .replace(Regex("[。，、！；：,.!?;:]+$"), "")
            .replace(Regex("^[吧吗呢啊]+"), "")
            .replace(Regex("[吧吗呢啊]+$"), "")
            .trim()
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    private fun extractDestination(text: String): String? {
        // 前缀匹配
        for (prefix in getKeywords("navigate_prefixes")) {
            if (text.startsWith(prefix)) {
                val dest = text.substring(prefix.length).trim()
                    .replace(Regex("^[的吧吗呢啊]+"), "")
                    .replace(Regex("[的吧吗呢啊]+$"), "")
                    .trim()
                if (dest.isNotEmpty()) return dest
            }
        }

        // 后缀匹配
        for (suffix in getKeywords("navigate_suffixes")) {
            if (text.endsWith(suffix)) {
                val dest = text.substring(0, text.length - suffix.length).trim()
                    .replace(Regex("^[的吧吗呢啊]+"), "")
                    .replace(Regex("[的吧吗呢啊]+$"), "")
                    .trim()
                if (dest.isNotEmpty()) return dest
            }
        }

        // 特殊处理："去XX"
        if (text.startsWith("去") && text.length > 1) {
            val dest = text.substring(1).trim()
                .replace(Regex("^[的吧吗呢啊]+"), "")
                .replace(Regex("[的吧吗呢啊]+$"), "")
                .trim()
            if (dest.isNotEmpty() && dest.length >= 2) return dest
        }

        // 特殊处理："到XX"
        if (text.startsWith("到") && text.length > 1) {
            val dest = text.substring(1).trim()
                .replace(Regex("^[的吧吗呢啊]+"), "")
                .replace(Regex("[的吧吗呢啊]+$"), "")
                .trim()
            if (dest.isNotEmpty() && dest.length >= 2) return dest
        }

        return null
    }
}
