package com.example.voicenavigation.data.tts

import android.content.Context
import android.util.Log
import com.example.voicenavigation.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TTS 预合成器：首次启动时批量合成所有固定文本，建立本地音频索引。
 *
 * 工作流程：
 * 1. 收集所有需要预合成的文本（strings.xml 中的 tts_ 条目 + 菜单项名称）
 * 2. 跳过已缓存的文本
 * 3. 逐条调用百度 TTS API 合成并缓存
 * 4. 完成后所有固定文本的播报延迟 <50ms
 *
 * 后续新增 TTS 需求：只需在 strings.xml 中加 `tts_` 前缀的条目，
 * 下次启动自动补合成。
 */
class TtsPreloader(
    private val context: Context,
    private val cache: TtsAudioCache
) {

    companion object {
        private const val TAG = "TtsPreloader"
        private const val TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"
        private const val TTS_URL = "https://tsn.baidu.com/text2audio"
    }

    /**
     * 收集所有需要预合成的文本。
     */
    fun collectTexts(): List<String> {
        val texts = mutableSetOf<String>()

        // 1. strings.xml 中所有 tts_ 和 msg_voice_ 开头的条目
        val res = context.resources
        val packageName = context.packageName
        val fields = R.string::class.java.declaredFields
        for (field in fields) {
            val name = field.name
            if (name.startsWith("tts_") || name.startsWith("msg_voice")
                || name.startsWith("msg_no_") || name.startsWith("msg_unknown")
                || name.startsWith("menu_")) {
                try {
                    val resId = field.getInt(null)
                    val text = res.getString(resId)
                    // 跳过含占位符的（如 %s, %d）——这些需要运行时填充
                    if (!text.contains("%") && text.isNotBlank()) {
                        texts.add(text)
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. 菜单项名称
        try {
            val json = context.assets.open("menu_config.json").bufferedReader().readText()
            val root = JSONObject(json)
            val items = root.optJSONArray("items") ?: return texts.toList()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val label = item.optString("label", "")
                if (label.isNotBlank()) texts.add(label)
                // "正在" + label（执行确认文本）
                texts.add("正在$label")
                val children = item.optJSONArray("children")
                if (children != null) {
                    for (j in 0 until children.length()) {
                        val child = children.getJSONObject(j)
                        val childLabel = child.optString("label", "")
                        if (childLabel.isNotBlank()) {
                            texts.add(childLabel)
                            texts.add("正在$childLabel")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read menu_config.json", e)
        }

        return texts.toList()
    }

    /**
     * 预合成所有未缓存的文本。返回新合成的数量。
     *
     * @param apiKey 百度 API Key
     * @param secretKey 百度 Secret Key
     * @param onProgress 进度回调 (已处理/总数, 当前文本)
     */
    fun preload(
        apiKey: String,
        secretKey: String,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Int {
        val allTexts = collectTexts()
        val uncached = allTexts.filter { !cache.has(it) }

        if (uncached.isEmpty()) {
            Log.d(TAG, "All ${allTexts.size} TTS texts already cached")
            return 0
        }

        Log.d(TAG, "Preloading ${uncached.size} TTS texts (${allTexts.size - uncached.size} already cached)")

        // 获取 token
        val token = fetchToken(apiKey, secretKey)
        if (token == null) {
            Log.e(TAG, "Failed to fetch Baidu TTS token")
            return 0
        }

        var synthesized = 0
        for ((index, text) in uncached.withIndex()) {
            onProgress?.invoke(index + 1, uncached.size, text)
            try {
                val audioData = synthesize(text, token)
                if (audioData != null && audioData.isNotEmpty()) {
                    cache.put(text, audioData)
                    synthesized++
                    Log.d(TAG, "Preloaded [$text] (${audioData.size} bytes)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload [$text]: ${e.message}")
            }
        }

        Log.d(TAG, "Preloading complete: $synthesized/${uncached.size} synthesized")
        return synthesized
    }

    private fun fetchToken(apiKey: String, secretKey: String): String? {
        return try {
            val params = "grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
            val url = URL("$TOKEN_URL?$params")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(response)
            json.optString("access_token", null)
        } catch (e: Exception) {
            Log.e(TAG, "Token fetch failed: ${e.message}")
            null
        }
    }

    private fun synthesize(text: String, token: String): ByteArray? {
        val cuid = "corsight_tts_cache"
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val params = "tex=$encodedText&lan=zh&cuid=$cuid&ctp=1&tok=$token&per=0&spd=5&pit=5&vol=15"

        val url = URL(TTS_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        conn.outputStream.write(params.toByteArray(Charsets.UTF_8))
        conn.outputStream.flush()
        conn.outputStream.close()

        val contentType = conn.contentType
        return if (contentType != null && contentType.contains("audio")) {
            conn.inputStream.readBytes()
        } else {
            null
        }.also { conn.disconnect() }
    }
}
