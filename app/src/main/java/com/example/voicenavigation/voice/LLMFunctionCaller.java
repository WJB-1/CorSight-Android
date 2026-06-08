package com.example.voicenavigation.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.voicenavigation.AppConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * LLM Function Calling 客户端。
 *
 * <p>调用 OpenAI 兼容的 Chat Completions API，传入可用函数定义和用户语音文本，
 * 让大模型理解意图并选择要调用的函数。</p>
 *
 * <p>支持的 API 提供商（凡是兼容 OpenAI 接口格式的都可以）：</p>
 * <ul>
 *   <li>OpenAI (GPT-4, GPT-4o, GPT-3.5)</li>
 *   <li>Claude API (via Anthropic 兼容代理或直接用 Anthropic Messages)</li>
 *   <li>DeepSeek</li>
 *   <li>通义千问 (Qwen)</li>
 *   <li>智谱 GLM</li>
 *   <li>本地 Ollama / vLLM</li>
 * </ul>
 *
 * <p>注意：目前实现的是 OpenAI 兼容格式。如需原生 Anthropic API，需补
 * Messages API 的请求/响应格式。</p>
 */
public class LLMFunctionCaller {

    private static final String TAG = "LLMFunctionCaller";

    private final Context context;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    /** LLM 返回的结果 */
    public static class Result {
        public final String functionName;
        public final String arguments; // JSON 字符串，如 {"destination": "天安门"}

        public Result(String functionName, String arguments) {
            this.functionName = functionName;
            this.arguments = arguments;
        }

        @Override
        public String toString() {
            return "Result{function=" + functionName + ", args=" + arguments + "}";
        }
    }

    public interface Callback {
        void onSuccess(@NonNull Result result);
        void onFailure(@NonNull String error);
    }

    public LLMFunctionCaller(@NonNull Context context) {
        this.context = context;
    }

    /**
     * 调用 LLM Function Calling API。
     *
     * @param userText 用户语音识别的文本
     * @param cb       回调
     */
    public void call(@NonNull String userText, @Nullable Callback cb) {
        String baseUrl = getBaseUrl();
        String apiKey = getApiKey();
        String model = getModel();

        if (baseUrl.isEmpty()) {
            if (cb != null) cb.onFailure("LLM 服务地址未配置");
            return;
        }
        if (apiKey.isEmpty()) {
            if (cb != null) cb.onFailure("LLM API Key 未配置");
            return;
        }

        String prompt = buildSystemPrompt();
        JSONArray tools = buildToolsSchema();

        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("temperature", 0.1); // 低温度，确保函数选择稳定
            body.put("max_tokens", 256);
            body.put("messages", new JSONArray()
                    .put(new JSONObject()
                            .put("role", "system")
                            .put("content", prompt))
                    .put(new JSONObject()
                            .put("role", "user")
                            .put("content", "用户说：" + userText + "\n请选择合适的函数调用。"))
            );
            body.put("tools", tools);
            body.put("tool_choice", "auto"); // 让 LLM 决定是否调用函数
        } catch (Exception e) {
            Log.e(TAG, "Failed to build request body", e);
            if (cb != null) cb.onFailure("请求构造失败：" + e.getMessage());
            return;
        }

        String url = baseUrl;
        if (!url.endsWith("/")) url += "/";
        url += "v1/chat/completions";

        Log.d(TAG, "Calling LLM: " + url + " model=" + model);
        Log.d(TAG, "Request body: " + body.toString());

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();

        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "LLM request failed: " + e.getMessage());
                if (cb != null) {
                    android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                    mainHandler.post(() -> cb.onFailure("LLM 请求超时: " + e.getMessage()));
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String bodyText = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "LLM response code=" + response.code() + " body=" + bodyText);

                    if (!response.isSuccessful()) {
                        String errMsg = "LLM HTTP " + response.code();
                        try {
                            JSONObject err = new JSONObject(bodyText);
                            errMsg = err.optJSONObject("error")
                                    .optString("message", errMsg);
                        } catch (Exception ignored) {}
                        postError(cb, errMsg);
                        return;
                    }

                    JSONObject json = new JSONObject(bodyText);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        postError(cb, "LLM 未返回有效结果");
                        return;
                    }

                    JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                    if (message == null) {
                        postError(cb, "LLM 响应格式异常");
                        return;
                    }

                    // 1. 优先检查 tool_calls（标准 function calling 格式）
                    JSONArray toolCalls = message.optJSONArray("tool_calls");
                    if (toolCalls != null && toolCalls.length() > 0) {
                        JSONObject tc = toolCalls.getJSONObject(0);
                        JSONObject fn = tc.optJSONObject("function");
                        if (fn != null) {
                            String fnName = fn.optString("name", "").trim();
                            String fnArgs = fn.optString("arguments", "{}").trim();
                            postSuccess(cb, new Result(fnName, fnArgs));
                            return;
                        }
                    }

                    // 2. 如果 LLM 直接在 content 里返回了文本（未启用 tool_choice）
                    String content = message.optString("content", "").trim();
                    if (!content.isEmpty()) {
                        postSuccess(cb, new Result("text_response", content));
                        return;
                    }

                    postError(cb, "LLM 未返回函数调用");
                } catch (Exception e) {
                    Log.e(TAG, "Parse LLM response failed", e);
                    postError(cb, "LLM 响应解析失败: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    // ==================== 工具/函数 Schema ====================

    private String buildSystemPrompt() {
        return "你是一个盲人导航避障应用的语音助手。用户的语音输入经过了语音识别，可能会有识别误差。\n\n"
                + "你的任务：理解用户意图，从以下功能中选择最合适的一个，调用它：\n\n"
                + "1. navigate_to —— 导航到指定目的地\n"
                + "   示例：'导航到天安门' → navigate_to(destination='天安门')\n"
                + "2. start_obstacle_avoidance —— 启动障碍物检测避障\n"
                + "   示例：'开始避障' '打开避障' '帮我看看前面'\n"
                + "3. stop_navigation —— 停止当前导航\n"
                + "   示例：'停止导航' '不导了' '关掉导航'\n"
                + "4. stop_obstacle_avoidance —— 停止避障\n"
                + "   示例：'停止避障' '关掉检测'\n"
                + "5. where_am_i —— 播报当前位置\n"
                + "   示例：'我在哪里' '这是哪儿'\n"
                + "6. repeat_last —— 重复上一次播报\n"
                + "   示例：'再说一遍' '重复' '什么'\n"
                + "7. preview_route —— 预览当前路线\n"
                + "   示例：'预览路线' '查看路线'\n"
                + "8. query_status —— 查询当前导航/避障运行状态\n"
                + "   示例：'当前状态' '现在什么情况'\n"
                + "9. text_search —— 搜索地点但不自动导航\n"
                + "   示例：'附近有什么' '搜索医院'\n\n"
                + "注意：导航类表达（'去XX' '导航去XX' '带我去XX' 'XX怎么走'）统一用 navigate_to。\n"
                + "如果用户的话无法匹配任何功能，返回 query_status 作为默认操作。";
    }

    private JSONArray buildToolsSchema() {
        JSONArray tools = new JSONArray();
        try {
            tools.put(makeTool("navigate_to",
                    "导航到指定的目的地。用户说要去的任何地点都使用此函数。",
                    new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("destination", new JSONObject()
                                            .put("type", "string")
                                            .put("description", "目的地名称，提取用户语音中的目标地点"))
                            )
                            .put("required", new JSONArray().put("destination"))));
            tools.put(makeTool("start_obstacle_avoidance",
                    "启动障碍物检测避障模式。用户说开始避障、打开避障、检测障碍物时使用。", null));
            tools.put(makeTool("stop_navigation",
                    "停止当前正在进行的导航。", null));
            tools.put(makeTool("stop_obstacle_avoidance",
                    "停止当前正在进行的障碍物检测避障。", null));
            tools.put(makeTool("where_am_i",
                    "播报用户的当前位置信息。", null));
            tools.put(makeTool("repeat_last",
                    "重复上一次语音播报的内容。", null));
            tools.put(makeTool("preview_route",
                    "预览当前路线，发送行前预览请求。", null));
            tools.put(makeTool("query_status",
                    "查询当前导航和避障的运行状态。", null));
            tools.put(makeTool("text_search",
                    "搜索指定地点，但仅搜索不自动开始导航。",
                    new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("keyword", new JSONObject()
                                            .put("type", "string")
                                            .put("description", "搜索关键词"))
                            )
                            .put("required", new JSONArray().put("keyword"))));
        } catch (Exception e) {
            Log.e(TAG, "Failed to build tools schema", e);
        }
        return tools;
    }

    private JSONObject makeTool(String name, String description, @Nullable JSONObject parameters) throws Exception {
        JSONObject fn = new JSONObject()
                .put("name", name)
                .put("description", description);
        if (parameters != null) {
            fn.put("parameters", parameters);
        } else {
            fn.put("parameters", new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()));
        }
        return new JSONObject()
                .put("type", "function")
                .put("function", fn);
    }

    // ==================== 配置读取 ====================

    private String getBaseUrl() {
        String url = AppConfig.prefs(context).getString(AppConfig.KEY_LLM_BASE_URL, "");
        return !url.isEmpty() ? url : "https://api.deepseek.com";
    }

    private String getApiKey() {
        return AppConfig.prefs(context).getString(AppConfig.KEY_LLM_API_KEY, "");
    }

    private String getModel() {
        String model = AppConfig.prefs(context).getString(AppConfig.KEY_LLM_MODEL, "");
        return !model.isEmpty() ? model : "deepseek-chat";
    }

    public boolean isConfigured() {
        return !getBaseUrl().isEmpty() && !getApiKey().isEmpty();
    }

    // ==================== 线程切换辅助 ====================

    private void postSuccess(@Nullable Callback cb, Result result) {
        if (cb == null) return;
        android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
        mainHandler.post(() -> cb.onSuccess(result));
    }

    private void postError(@Nullable Callback cb, String error) {
        if (cb == null) return;
        android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
        mainHandler.post(() -> cb.onFailure(error));
    }
}
