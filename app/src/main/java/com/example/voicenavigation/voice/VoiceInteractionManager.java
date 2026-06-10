package com.example.voicenavigation.voice;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.voicenavigation.stt.BaiduSpeechManager;
import com.example.voicenavigation.stt.BaiduTtsManager;

/**
 * 语音交互管理器 —— 统一封装「听 → 懂 → 做 → 说」的完整闭环。
 *
 * <p>支持两种模式，共用同一个 {@link BaiduSpeechManager} 实例：</p>
 * <ul>
 *   <li><strong>TEXT_INPUT</strong>：语音转文字 → 通过 {@link TextInputListener} 回调</li>
 *   <li><strong>COMMAND</strong>：Function Calling（本地关键词 + LLM 云端混合）</li>
 * </ul>
 *
 * <h3>Function Calling 混合架构</h3>
 * <pre>
 *   用户语音
 *     ├── 本地关键词匹配成功 → 直接执行（快，离线）
 *     └── 本地失败（UNKNOWN） → LLM Function Calling 兜底（慢，需联网）
 * </pre>
 */
public class VoiceInteractionManager implements BaiduSpeechManager.STTCallback {

    private static final String TAG = "VoiceInteractionManager";

    public enum Mode {
        TEXT_INPUT,
        COMMAND
    }

    // ==================== 回调接口 ====================

    public interface TextInputListener {
        void onTextResult(@NonNull String result);
        void onTextPartial(@NonNull String partial);
    }

    public interface CommandExecutor {
        void executeNavigateTo(@NonNull String destination);
        void executeStartObstacleAvoidance();
        void executeStopNavigation();
        void executeStopObstacleAvoidance();
        void executeWhereAmI();
        void executeRepeatLast();
        void executePreviewRoute();
        void executeQueryStatus();
        void executeTextSearch(@NonNull String text);
        void executeUnknown(@NonNull String text);
        @Nullable String getLastSpokenText();
        boolean isNavigating();
        boolean isObstacleAvoiding();
        @Nullable String getCurrentLocationDescription();
    }

    public interface VoiceEventListener {
        void onListeningStarted();
        void onListeningStopped();
        void onPartialResultReceived(@NonNull String text);
        /** 流水线阶段变化：用于更新按钮文字显示当前进度 */
        void onPipelineStage(@NonNull String stage);
    }

    // ==================== 内部状态 ====================

    private final Context context;
    private final BaiduSpeechManager speechManager;
    private final BaiduTtsManager ttsManager;
    private final VoiceCommandInterpreter interpreter;
    @Nullable private final LLMFunctionCaller llmCaller;

    private Mode currentMode = Mode.TEXT_INPUT;
    private TextInputListener textInputListener;
    private CommandExecutor commandExecutor;
    private VoiceEventListener voiceEventListener;
    private String lastFeedbackText = "";
    /** 当前正在处理的原始语音文本 */
    private String pendingRawText = "";
    /** stopListening 后等待 onResult 的超时看门狗 */
    private final android.os.Handler watchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable resultTimeoutRunnable;
    private boolean waitingForResult = false;

    public VoiceInteractionManager(@NonNull Context context,
                                    @NonNull BaiduSpeechManager speechManager,
                                    @NonNull BaiduTtsManager ttsManager,
                                    @Nullable LLMFunctionCaller llmCaller) {
        this.context = context;
        this.speechManager = speechManager;
        this.ttsManager = ttsManager;
        this.interpreter = new VoiceCommandInterpreter();
        this.llmCaller = llmCaller;
        speechManager.setCallback(this);
    }

    public void setTextInputListener(@Nullable TextInputListener listener) { this.textInputListener = listener; }
    public void setCommandExecutor(@Nullable CommandExecutor executor) { this.commandExecutor = executor; }
    public void setVoiceEventListener(@Nullable VoiceEventListener listener) { this.voiceEventListener = listener; }

    // ==================== 对外 API ====================

    public void startListening(@NonNull Mode mode) {
        currentMode = mode;
        Log.d(TAG, "startListening mode=" + mode);
        // 语音命令模式：打断当前所有 TTS 播报，新指令优先
        if (mode == Mode.COMMAND && ttsManager != null) {
            ttsManager.flushQueue();
        }
        if (speechManager != null) speechManager.startListening();
    }

    public void stopListening() {
        Log.d(TAG, "stopListening");
        if (speechManager != null) speechManager.stopListening();

        // 启动看门狗：8 秒内没收 onResult 则自动恢复按钮
        if (currentMode == Mode.COMMAND) {
            waitingForResult = true;
            watchdogHandler.removeCallbacks(getResultTimeoutRunnable());
            watchdogHandler.postDelayed(getResultTimeoutRunnable(), 8000);
        }
    }

    private Runnable getResultTimeoutRunnable() {
        if (resultTimeoutRunnable == null) {
            resultTimeoutRunnable = () -> {
                if (!waitingForResult) return;
                waitingForResult = false;
                Log.w(TAG, "Result timeout — no onResult received after stop");
                showToast("⚠️ 识别超时/无结果，请重试");
                emitStage("语音助手");
            };
        }
        return resultTimeoutRunnable;
    }

    /** 播报 + 弹 Toast。用于用户必须看到的关键操作确认。 */
    public void speakAndToast(@NonNull String text) {
        lastFeedbackText = text;
        showToast(text);
        if (ttsManager != null) ttsManager.speak(text);
    }

    /** 仅 TTS 播报，不弹 Toast。用于中间过渡消息。 */
    public void speakFeedback(@NonNull String text) {
        lastFeedbackText = text;
        if (ttsManager != null) ttsManager.speak(text);
    }

    public void speakForce(@NonNull String text) {
        if (ttsManager != null) ttsManager.speak(text);
    }

    @Nullable public String getLastFeedbackText() { return lastFeedbackText; }
    @Nullable public BaiduTtsManager getTtsManager() { return ttsManager; }
    /** 是否配置了 LLM 兜底 */
    public boolean isLLMAvailable() { return llmCaller != null && llmCaller.isConfigured(); }

    private android.widget.Toast currentToast;
    private final android.os.Handler toastHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final int TOAST_DURATION_MS = 1200; // 比系统 LENGTH_SHORT(~2s) 更快

    private void showToast(@NonNull String msg) {
        toastHandler.post(() -> {
            // 新 Toast 立即取消旧 Toast，不堆积
            if (currentToast != null) currentToast.cancel();
            currentToast = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT);
            currentToast.show();
            // 1.2s 后主动取消，确保不会挂太久
            toastHandler.removeCallbacks(cancelToastRunnable);
            toastHandler.postDelayed(cancelToastRunnable, TOAST_DURATION_MS);
        });
    }

    private final Runnable cancelToastRunnable = () -> {
        if (currentToast != null) {
            currentToast.cancel();
            currentToast = null;
        }
    };

    private void emitStage(@NonNull String stage) {
        if (voiceEventListener != null) {
            voiceEventListener.onPipelineStage(stage);
        }
    }

    /** 执行完成后恢复按钮文字 */
    private void restoreButtonAfter(long delayMs) {
        android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
        mainHandler.postDelayed(() -> emitStage("语音助手"), delayMs);
    }

    // ==================== STT 回调 ====================

    @Override
    public void onResult(String result) {
        // 取消看门狗
        waitingForResult = false;
        watchdogHandler.removeCallbacks(getResultTimeoutRunnable());

        Log.d(TAG, "=== STT RESULT === mode=" + currentMode + " text=[" + result + "]");
        if (result == null || result.trim().isEmpty()) {
            showToast("❌ 没有听清，请松开后再说一次");
            emitStage("语音助手");
            return;
        }
        String trimmed = result.trim();
        Log.d(TAG, "完整的识别文字：【" + trimmed + "】，长度=" + trimmed.length());

        if (currentMode == Mode.TEXT_INPUT) {
            if (textInputListener != null) textInputListener.onTextResult(trimmed);
        } else {
            // 命令模式：显示识别到的文字
            showToast("📝 识别：【" + trimmed + "】");
            processCommand(trimmed);
        }
    }

    @Override
    public void onPartialResult(String result) {
        if (result == null) return;
        Log.d(TAG, "STT partial: [" + result + "]");
        if (currentMode == Mode.TEXT_INPUT) {
            if (textInputListener != null) textInputListener.onTextPartial(result);
        } else {
            if (voiceEventListener != null) voiceEventListener.onPartialResultReceived(result);
        }
    }

    @Override
    public void onError(String error) {
        // 取消看门狗
        waitingForResult = false;
        watchdogHandler.removeCallbacks(getResultTimeoutRunnable());

        Log.e(TAG, "=== STT ERROR === " + error);
        showToast("❌ 识别失败：" + error);
        emitStage("语音助手");
    }

    @Override public void onListening() {
        Log.d(TAG, "=== STT LISTENING ===");
        if (currentMode == Mode.COMMAND) {
            showToast("🎙️ 麦克风已开，请说话");
            emitStage("🎙️ 正在听...");
        }
        if (voiceEventListener != null) voiceEventListener.onListeningStarted();
    }

    @Override public void onStopped() {
        Log.d(TAG, "=== STT STOPPED ===");
        if (currentMode == Mode.COMMAND) emitStage("⏳ 识别中...");
        if (voiceEventListener != null) voiceEventListener.onListeningStopped();
    }

    // ==================== Function Calling 混合架构 ====================

    /**
     * 处理语音命令：先走本地关键词匹配，失败则尝试 LLM。
     * 每一步都有 Toast 反馈，方便调试。
     */
    private void processCommand(@NonNull String rawText) {
        pendingRawText = rawText;
        VoiceCommand command = interpreter.interpret(rawText);
        Log.d(TAG, "本地解析结果: type=" + command.getType() + " dest=" + command.getDestination());

        if (command.getType() != VoiceCommand.Type.UNKNOWN) {
            // ✅ 本地命中
            String typeLabel = getTypeLabel(command.getType());
            showToast("✅ 本地命中 → " + typeLabel);
            emitStage("✅ " + typeLabel);
            executeCommand(command);
        } else if (llmCaller != null && llmCaller.isConfigured()) {
            // ⏳ 本地未命中 → 尝试 LLM
            showToast("⏳ 本地未匹配 → 云端 LLM...");
            emitStage("☁️ 请求云端...");
            Log.d(TAG, "Local miss, falling back to LLM for: " + rawText);
            llmCaller.call(rawText, new LLMFunctionCaller.Callback() {
                @Override
                public void onSuccess(@NonNull LLMFunctionCaller.Result result) {
                    Log.d(TAG, "LLM SUCCESS: function=" + result.functionName + " args=" + result.arguments);
                    showToast("☁️ LLM→" + result.functionName);
                    emitStage("☁️ " + result.functionName);
                    VoiceCommand llmCommand = mapLLMResultToCommand(result);
                    executeCommand(llmCommand);
                }

                @Override
                public void onFailure(@NonNull String error) {
                    Log.w(TAG, "LLM FAILED: " + error);
                    showToast("❌ LLM 超时/失败");
                    emitStage("❌ LLM 失败");
                    speakFeedback("抱歉，没有听懂。您可以试试说：导航去某地、开始避障、查询状态、我在哪里等");
                    if (commandExecutor != null) commandExecutor.executeUnknown(rawText);
                }
            });
        } else {
            // ❌ LLM 未配置
            showToast("❌ 本地未匹配，LLM 未配置");
            emitStage("❌ 无法响应");
            speakFeedback("抱歉，没有听懂。您可以试试说：导航去某地、开始避障、查询状态、我在哪里等");
            if (commandExecutor != null) commandExecutor.executeUnknown(rawText);
        }
    }

    /** 指令类型 → 中文标签（用于 Toast 调试） */
    private String getTypeLabel(VoiceCommand.Type type) {
        switch (type) {
            case NAVIGATE_TO: return "导航去某地";
            case START_OBSTACLE_AVOIDANCE: return "启动避障";
            case STOP_NAVIGATION: return "停止导航";
            case STOP_OBSTACLE_AVOIDANCE: return "停止避障";
            case WHERE_AM_I: return "我在哪里";
            case REPEAT_LAST: return "重复播报";
            case PREVIEW_ROUTE: return "预览路线";
            case QUERY_STATUS: return "查询状态";
            case TEXT_SEARCH: return "搜索地点";
            default: return "未知";
        }
    }

    /**
     * 将 LLM 返回的 function_name + arguments 映射为 VoiceCommand。
     */
    private VoiceCommand mapLLMResultToCommand(LLMFunctionCaller.Result result) {
        String fnName = result.functionName.toLowerCase().trim();
        String args = result.arguments != null ? result.arguments : "{}";

        try {
            org.json.JSONObject argObj = new org.json.JSONObject(args);

            switch (fnName) {
                case "navigate_to":
                    String dest = argObj.optString("destination", pendingRawText);
                    return new VoiceCommand(VoiceCommand.Type.NAVIGATE_TO, dest, pendingRawText);
                case "start_obstacle_avoidance":
                    return new VoiceCommand(VoiceCommand.Type.START_OBSTACLE_AVOIDANCE, null, pendingRawText);
                case "stop_navigation":
                    return new VoiceCommand(VoiceCommand.Type.STOP_NAVIGATION, null, pendingRawText);
                case "stop_obstacle_avoidance":
                    return new VoiceCommand(VoiceCommand.Type.STOP_OBSTACLE_AVOIDANCE, null, pendingRawText);
                case "where_am_i":
                    return new VoiceCommand(VoiceCommand.Type.WHERE_AM_I, null, pendingRawText);
                case "repeat_last":
                    return new VoiceCommand(VoiceCommand.Type.REPEAT_LAST, null, pendingRawText);
                case "preview_route":
                    return new VoiceCommand(VoiceCommand.Type.PREVIEW_ROUTE, null, pendingRawText);
                case "query_status":
                    return new VoiceCommand(VoiceCommand.Type.QUERY_STATUS, null, pendingRawText);
                case "text_search":
                    String kw = argObj.optString("keyword", pendingRawText);
                    return new VoiceCommand(VoiceCommand.Type.TEXT_SEARCH, kw, pendingRawText);
                case "text_response":
                    // LLM 返回了文本而非函数调用
                    return new VoiceCommand(VoiceCommand.Type.TEXT_SEARCH, result.arguments, pendingRawText);
                default:
                    Log.w(TAG, "Unknown LLM function: " + fnName);
                    return new VoiceCommand(VoiceCommand.Type.UNKNOWN, null, pendingRawText);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse LLM arguments: " + args, e);
            return new VoiceCommand(VoiceCommand.Type.UNKNOWN, null, pendingRawText);
        }
    }

    // ==================== 指令执行 ====================

    private void executeCommand(@NonNull VoiceCommand command) {
        if (commandExecutor == null) {
            speakFeedback("语音助手尚未就绪");
            restoreButtonAfter(2000);
            return;
        }

        // 执行指令
        switch (command.getType()) {
            case NAVIGATE_TO:
                speakFeedback("正在搜索" + command.getDestination());
                emitStage("🔍 搜索" + command.getDestination());
                commandExecutor.executeNavigateTo(command.getDestination());
                break;
            case START_OBSTACLE_AVOIDANCE:
                speakFeedback("正在启动避障模式");
                commandExecutor.executeStartObstacleAvoidance();
                break;
            case STOP_NAVIGATION:
                if (commandExecutor.isNavigating()) {
                    speakFeedback("正在停止导航");
                } else {
                    speakFeedback("当前没有正在进行的导航");
                }
                commandExecutor.executeStopNavigation();
                break;
            case STOP_OBSTACLE_AVOIDANCE:
                if (commandExecutor.isObstacleAvoiding()) {
                    speakFeedback("正在停止避障");
                } else {
                    speakFeedback("当前没有正在进行的避障");
                }
                commandExecutor.executeStopObstacleAvoidance();
                break;
            case WHERE_AM_I:
                String locDesc = commandExecutor.getCurrentLocationDescription();
                if (locDesc != null && !locDesc.isEmpty()) {
                    speakAndToast("您当前位于" + locDesc);
                } else {
                    speakAndToast("正在定位中，请稍后再试");
                }
                commandExecutor.executeWhereAmI();
                break;
            case REPEAT_LAST: {
                String lastText = commandExecutor.getLastSpokenText();
                if (lastText == null || lastText.isEmpty()) lastText = lastFeedbackText;
                if (lastText != null && !lastText.isEmpty()) {
                    speakAndToast(lastText);
                } else {
                    speakAndToast("暂无可重复播报的内容");
                }
                commandExecutor.executeRepeatLast();
                break;
            }
            case PREVIEW_ROUTE:
                speakAndToast("正在生成路线预览");
                commandExecutor.executePreviewRoute();
                break;
            case QUERY_STATUS:
                boolean nav = commandExecutor.isNavigating();
                boolean obs = commandExecutor.isObstacleAvoiding();
                String status = (nav ? "当前正在导航中" : "导航未启动")
                        + "，" + (obs ? "避障模式已开启" : "避障模式未开启");
                speakAndToast(status);
                commandExecutor.executeQueryStatus();
                break;
            case TEXT_SEARCH:
                speakAndToast("正在搜索" + command.getDestination());
                commandExecutor.executeTextSearch(command.getDestination());
                break;
            case UNKNOWN:
            default:
                speakAndToast("抱歉，没有听懂。您可以试试说：导航去某地、开始避障、查询状态、我在哪里等");
                commandExecutor.executeUnknown(command.getRawText());
                break;
        }
        // 执行完毕，延时恢复按钮文字
        restoreButtonAfter(5000);
    }
}
