package com.example.voicenavigation.voice

import android.content.Context
import android.os.Handler
import android.util.Log
import com.example.voicenavigation.AppConfig
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Alias for Java callers that expect the old `LLMFunctionCaller` name.
 */
typealias LLMFunctionCaller = LlmFunctionCaller

/**
 * LLM Function Calling 客户端。
 *
 * 调用 OpenAI 兼容的 Chat Completions API，传入可用函数定义和用户语音文本，
 * 让大模型理解意图并选择要调用的函数。
 *
 * 支持的 API 提供商（凡是兼容 OpenAI 接口格式的都可以）：
 * - OpenAI (GPT-4, GPT-4o, GPT-3.5)
 * - Claude API (via Anthropic 兼容代理或直接用 Anthropic Messages)
 * - DeepSeek
 * - 通义千问 (Qwen)
 * - 智谱 GLM
 * - 本地 Ollama / vLLM
 *
 * 注意：目前实现的是 OpenAI 兼容格式。如需原生 Anthropic API，需补
 * Messages API 的请求/响应格式。
 */
class LlmFunctionCaller(private val context: Context) {

    companion object {
        private const val TAG = "LLMFunctionCaller"
        private val JSON_MEDIA: MediaType = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * LLM 返回的结果。
     */
    class Result(
        @JvmField val functionName: String,
        /** JSON 字符串，如 {"destination": "天安门"} */
        @JvmField val arguments: String
    ) {
        override fun toString(): String =
            "Result{function=$functionName, args=$arguments}"
    }

    interface Callback {
        fun onSuccess(result: Result)
        fun onFailure(error: String)
    }

    /**
     * 调用 LLM Function Calling API。
     *
     * @param userText 用户语音识别的文本
     * @param cb       回调
     */
    fun call(userText: String, cb: Callback?) {
        val baseUrl = getBaseUrl()
        val apiKey = getApiKey()
        val model = getModel()

        if (baseUrl.isEmpty()) {
            cb?.onFailure("LLM 服务地址未配置")
            return
        }
        if (apiKey.isEmpty()) {
            cb?.onFailure("LLM API Key 未配置")
            return
        }

        val prompt = buildSystemPrompt()
        val tools = buildToolsSchema()

        val body = try {
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.1) // 低温度，确保函数选择稳定
                put("max_tokens", 256)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", prompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "用户说：$userText\n请选择合适的函数调用。")
                    })
                })
                put("tools", tools)
                put("tool_choice", "auto") // 让 LLM 决定是否调用函数
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build request body", e)
            cb?.onFailure("请求构造失败：${e.message}")
            return
        }

        var url = baseUrl
        if (!url.endsWith("/")) url += "/"
        url += "v1/chat/completions"

        Log.d(TAG, "Calling LLM: $url model=$model")
        Log.d(TAG, "Request body: $body")

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "LLM request failed: ${e.message}")
                if (cb != null) {
                    val mainHandler = Handler(context.mainLooper)
                    mainHandler.post { cb.onFailure("LLM 请求超时: ${e.message}") }
                }
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyText = response.body?.string() ?: ""
                    Log.d(TAG, "LLM response code=${response.code} body=$bodyText")

                    if (!response.isSuccessful) {
                        var errMsg = "LLM HTTP ${response.code}"
                        try {
                            val err = JSONObject(bodyText)
                            errMsg = err.optJSONObject("error")
                                ?.optString("message", errMsg) ?: errMsg
                        } catch (_: Exception) {}
                        postError(cb, errMsg)
                        return
                    }

                    val json = JSONObject(bodyText)
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        postError(cb, "LLM 未返回有效结果")
                        return
                    }

                    val message = choices.getJSONObject(0).optJSONObject("message")
                    if (message == null) {
                        postError(cb, "LLM 响应格式异常")
                        return
                    }

                    // 1. 优先检查 tool_calls（标准 function calling 格式）
                    val toolCalls = message.optJSONArray("tool_calls")
                    if (toolCalls != null && toolCalls.length() > 0) {
                        val tc = toolCalls.getJSONObject(0)
                        val fn = tc.optJSONObject("function")
                        if (fn != null) {
                            val fnName = fn.optString("name", "").trim()
                            val fnArgs = fn.optString("arguments", "{}").trim()
                            postSuccess(cb, Result(fnName, fnArgs))
                            return
                        }
                    }

                    // 2. 如果 LLM 直接在 content 里返回了文本（未启用 tool_choice）
                    val content = message.optString("content", "").trim()
                    if (content.isNotEmpty()) {
                        postSuccess(cb, Result("text_response", content))
                        return
                    }

                    postError(cb, "LLM 未返回函数调用")
                } catch (e: Exception) {
                    Log.e(TAG, "Parse LLM response failed", e)
                    postError(cb, "LLM 响应解析失败: ${e.message}")
                } finally {
                    response.close()
                }
            }
        })
    }

    // ==================== 工具/函数 Schema ====================

    private fun buildSystemPrompt(): String =
        "你是一个盲人导航避障应用的语音助手。用户的语音输入经过了语音识别，可能会有识别误差。\n\n" +
                "你的任务：理解用户意图，从以下功能中选择最合适的一个，调用它：\n\n" +
                "1. navigate_to —— 导航到指定目的地\n" +
                "   示例：'导航到天安门' → navigate_to(destination='天安门')\n" +
                "2. start_obstacle_avoidance —— 启动障碍物检测避障\n" +
                "   示例：'开始避障' '打开避障' '帮我看看前面'\n" +
                "3. stop_navigation —— 停止当前导航\n" +
                "   示例：'停止导航' '不导了' '关掉导航'\n" +
                "4. stop_obstacle_avoidance —— 停止避障\n" +
                "   示例：'停止避障' '关掉检测'\n" +
                "5. where_am_i —— 播报当前位置\n" +
                "   示例：'我在哪里' '这是哪儿'\n" +
                "6. repeat_last —— 重复上一次播报\n" +
                "   示例：'再说一遍' '重复' '什么'\n" +
                "7. preview_route —— 预览当前路线\n" +
                "   示例：'预览路线' '查看路线'\n" +
                "8. query_status —— 查询当前导航/避障运行状态\n" +
                "   示例：'当前状态' '现在什么情况'\n" +
                "9. text_search —— 搜索地点但不自动导航\n" +
                "   示例：'附近有什么' '搜索医院'\n\n" +
                "注意：导航类表达（'去XX' '导航去XX' '带我去XX' 'XX怎么走'）统一用 navigate_to。\n" +
                "如果用户的话无法匹配任何功能，返回 query_status 作为默认操作。"

    private fun buildToolsSchema(): JSONArray {
        val tools = JSONArray()
        try {
            tools.put(makeTool("navigate_to",
                "导航到指定的目的地。用户说要去的任何地点都使用此函数。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("destination", JSONObject().apply {
                            put("type", "string")
                            put("description", "目的地名称，提取用户语音中的目标地点")
                        })
                    })
                    put("required", JSONArray().put("destination"))
                }))
            tools.put(makeTool("start_obstacle_avoidance",
                "启动障碍物检测避障模式。用户说开始避障、打开避障、检测障碍物时使用。", null))
            tools.put(makeTool("stop_navigation",
                "停止当前正在进行的导航。", null))
            tools.put(makeTool("stop_obstacle_avoidance",
                "停止当前正在进行的障碍物检测避障。", null))
            tools.put(makeTool("where_am_i",
                "播报用户的当前位置信息。", null))
            tools.put(makeTool("repeat_last",
                "重复上一次语音播报的内容。", null))
            tools.put(makeTool("preview_route",
                "预览当前路线，发送行前预览请求。", null))
            tools.put(makeTool("query_status",
                "查询当前导航和避障的运行状态。", null))
            tools.put(makeTool("text_search",
                "搜索指定地点，但仅搜索不自动开始导航。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("keyword", JSONObject().apply {
                            put("type", "string")
                            put("description", "搜索关键词")
                        })
                    })
                    put("required", JSONArray().put("keyword"))
                }))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build tools schema", e)
        }
        return tools
    }

    @Throws(Exception::class)
    private fun makeTool(name: String, description: String, parameters: JSONObject?): JSONObject {
        val fn = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parameters ?: JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", fn)
        }
    }

    // ==================== 配置读取 ====================

    private fun getBaseUrl(): String {
        val url = AppConfig.prefs(context).getString(AppConfig.KEY_LLM_BASE_URL, "") ?: ""
        return if (url.isNotEmpty()) url else "https://api.deepseek.com"
    }

    private fun getApiKey(): String =
        AppConfig.prefs(context).getString(AppConfig.KEY_LLM_API_KEY, "") ?: ""

    private fun getModel(): String {
        val model = AppConfig.prefs(context).getString(AppConfig.KEY_LLM_MODEL, "") ?: ""
        return if (model.isNotEmpty()) model else "deepseek-chat"
    }

    fun isConfigured(): Boolean =
        getBaseUrl().isNotEmpty() && getApiKey().isNotEmpty()

    // ==================== 线程切换辅助 ====================

    private fun postSuccess(cb: Callback?, result: Result) {
        if (cb == null) return
        val mainHandler = Handler(context.mainLooper)
        mainHandler.post { cb.onSuccess(result) }
    }

    private fun postError(cb: Callback?, error: String) {
        if (cb == null) return
        val mainHandler = Handler(context.mainLooper)
        mainHandler.post { cb.onFailure(error) }
    }
}
