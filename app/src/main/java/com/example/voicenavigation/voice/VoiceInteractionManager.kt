package com.example.voicenavigation.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.voicenavigation.stt.BaiduSpeechManager
import com.example.voicenavigation.stt.BaiduTtsManager
import org.json.JSONObject

/**
 * 语音交互管理器 —— 统一封装「听 → 懂 → 做 → 说」的完整闭环。
 *
 * 支持两种模式，共用同一个 [BaiduSpeechManager] 实例：
 * - **TEXT_INPUT**：语音转文字 → 通过 [TextInputListener] 回调
 * - **COMMAND**：Function Calling（本地关键词 + LLM 云端混合）
 *
 * Function Calling 混合架构：
 * ```
 *   用户语音
 *     ├── 本地关键词匹配成功 → 直接执行（快，离线）
 *     └── 本地失败（UNKNOWN） → LLM Function Calling 兜底（慢，需联网）
 * ```
 */
class VoiceInteractionManager(
    private val context: Context,
    private val speechManager: BaiduSpeechManager,
    private val ttsManager: BaiduTtsManager?,
    private val llmCaller: LlmFunctionCaller?
) : BaiduSpeechManager.STTCallback {

    companion object {
        private const val TAG = "VoiceInteractionManager"
        private const val TOAST_DURATION_MS = 1200
    }

    enum class Mode {
        TEXT_INPUT,
        COMMAND
    }

    // ==================== 回调接口 ====================

    interface TextInputListener {
        fun onTextResult(result: String)
        fun onTextPartial(partial: String)
    }

    interface CommandExecutor {
        fun executeNavigateTo(destination: String)
        fun executeStartObstacleAvoidance()
        fun executeStopNavigation()
        fun executeStopObstacleAvoidance()
        fun executeWhereAmI()
        fun executeRepeatLast()
        fun executePreviewRoute()
        fun executeQueryStatus()
        fun executeTextSearch(text: String)
        fun executeUnknown(text: String)
        fun getLastSpokenText(): String?
        fun isNavigating(): Boolean
        fun isObstacleAvoiding(): Boolean
        fun getCurrentLocationDescription(): String?
    }

    interface VoiceEventListener {
        fun onListeningStarted()
        fun onListeningStopped()
        fun onPartialResultReceived(text: String)
        fun onPipelineStage(stage: String)
    }

    // ==================== 内部状态 ====================

    private val interpreter = VoiceCommandInterpreter()
    private var currentMode = Mode.TEXT_INPUT
    private var textInputListener: TextInputListener? = null
    private var commandExecutor: CommandExecutor? = null
    private var voiceEventListener: VoiceEventListener? = null
    private var lastFeedbackText: String? = ""
    private var pendingRawText = ""
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var resultTimeoutRunnable: Runnable? = null
    private var waitingForResult = false

    init {
        speechManager.callback = this
    }

    fun setTextInputListener(listener: TextInputListener?) { textInputListener = listener }
    fun setCommandExecutor(executor: CommandExecutor?) { commandExecutor = executor }
    fun setVoiceEventListener(listener: VoiceEventListener?) { voiceEventListener = listener }

    // ==================== 对外 API ====================

    fun startListening(mode: Mode) {
        currentMode = mode
        Log.d(TAG, "startListening mode=$mode")
        if (mode == Mode.COMMAND) {
            ttsManager?.flushQueue()
        }
        speechManager.startListening()
    }

    fun stopListening() {
        Log.d(TAG, "stopListening")
        speechManager.stopListening()

        if (currentMode == Mode.COMMAND) {
            waitingForResult = true
            watchdogHandler.removeCallbacks(getResultTimeoutRunnable())
            watchdogHandler.postDelayed(getResultTimeoutRunnable(), 8000)
        }
    }

    private fun getResultTimeoutRunnable(): Runnable {
        if (resultTimeoutRunnable == null) {
            resultTimeoutRunnable = Runnable {
                if (!waitingForResult) return@Runnable
                waitingForResult = false
                Log.w(TAG, "Result timeout — no onResult received after stop")
                showToast("⚠️ 识别超时/无结果，请重试")
                emitStage("语音助手")
            }
        }
        return resultTimeoutRunnable!!
    }

    fun speakAndToast(text: String) {
        lastFeedbackText = text
        showToast(text)
        ttsManager?.speak(text)
    }

    fun speakFeedback(text: String) {
        lastFeedbackText = text
        ttsManager?.speak(text)
    }

    fun speakForce(text: String) {
        ttsManager?.speak(text)
    }

    fun getLastFeedbackText(): String? = lastFeedbackText
    fun getTtsManager(): BaiduTtsManager? = ttsManager
    fun isLLMAvailable(): Boolean = llmCaller != null && llmCaller.isConfigured()

    // ==================== Toast 管理 ====================

    private var currentToast: Toast? = null
    private val toastHandler = Handler(Looper.getMainLooper())

    private val cancelToastRunnable = Runnable {
        currentToast?.cancel()
        currentToast = null
    }

    private fun showToast(msg: String) {
        toastHandler.post {
            currentToast?.cancel()
            currentToast = Toast.makeText(context, msg, Toast.LENGTH_SHORT).also { it.show() }
            toastHandler.removeCallbacks(cancelToastRunnable)
            toastHandler.postDelayed(cancelToastRunnable, TOAST_DURATION_MS.toLong())
        }
    }

    private fun emitStage(stage: String) {
        voiceEventListener?.onPipelineStage(stage)
    }

    private fun restoreButtonAfter(delayMs: Long) {
        Handler(context.mainLooper).postDelayed({ emitStage("语音助手") }, delayMs)
    }

    // ==================== STT 回调 ====================

    override fun onResult(result: String) {
        waitingForResult = false
        watchdogHandler.removeCallbacks(getResultTimeoutRunnable())

        Log.d(TAG, "=== STT RESULT === mode=$currentMode text=[$result]")
        if (result.isBlank()) {
            showToast("❌ 没有听清，请松开后再说一次")
            emitStage("语音助手")
            return
        }
        val trimmed = result.trim()
        Log.d(TAG, "完整的识别文字：【$trimmed】，长度=${trimmed.length}")

        if (currentMode == Mode.TEXT_INPUT) {
            textInputListener?.onTextResult(trimmed)
        } else {
            showToast("📝 识别：【$trimmed】")
            processCommand(trimmed)
        }
    }

    override fun onPartialResult(result: String) {
        Log.d(TAG, "STT partial: [$result]")
        if (currentMode == Mode.TEXT_INPUT) {
            textInputListener?.onTextPartial(result)
        } else {
            voiceEventListener?.onPartialResultReceived(result)
        }
    }

    override fun onError(error: String) {
        waitingForResult = false
        watchdogHandler.removeCallbacks(getResultTimeoutRunnable())

        Log.e(TAG, "=== STT ERROR === $error")
        showToast("❌ 识别失败：$error")
        emitStage("语音助手")
    }

    override fun onListening() {
        Log.d(TAG, "=== STT LISTENING ===")
        if (currentMode == Mode.COMMAND) {
            showToast("🎙️ 麦克风已开，请说话")
            emitStage("🎙️ 正在听...")
        }
        voiceEventListener?.onListeningStarted()
    }

    override fun onStopped() {
        Log.d(TAG, "=== STT STOPPED ===")
        if (currentMode == Mode.COMMAND) emitStage("⏳ 识别中...")
        voiceEventListener?.onListeningStopped()
    }

    // ==================== Function Calling 混合架构 ====================

    private fun processCommand(rawText: String) {
        pendingRawText = rawText
        val command = interpreter.interpret(rawText)
        Log.d(TAG, "本地解析结果: type=${command.type} dest=${command.destination}")

        if (command.type != VoiceCommand.Type.UNKNOWN) {
            val typeLabel = command.type.description
            showToast("✅ 本地命中 → $typeLabel")
            emitStage("✅ $typeLabel")
            executeCommand(command)
        } else if (llmCaller != null && llmCaller.isConfigured()) {
            showToast("⏳ 本地未匹配 → 云端 LLM...")
            emitStage("☁️ 请求云端...")
            Log.d(TAG, "Local miss, falling back to LLM for: $rawText")
            llmCaller.call(rawText, object : LlmFunctionCaller.Callback {
                override fun onSuccess(result: LlmFunctionCaller.Result) {
                    Log.d(TAG, "LLM SUCCESS: function=${result.functionName} args=${result.arguments}")
                    showToast("☁️ LLM→${result.functionName}")
                    emitStage("☁️ ${result.functionName}")
                    val llmCommand = mapLLMResultToCommand(result)
                    executeCommand(llmCommand)
                }

                override fun onFailure(error: String) {
                    Log.w(TAG, "LLM FAILED: $error")
                    showToast("❌ LLM 超时/失败")
                    emitStage("❌ LLM 失败")
                    speakFeedback("抱歉，没有听懂。您可以试试说：导航去某地、开始避障、查询状态、我在哪里等")
                    commandExecutor?.executeUnknown(rawText)
                }
            })
        } else {
            showToast("❌ 本地未匹配，LLM 未配置")
            emitStage("❌ 无法响应")
            speakFeedback("抱歉，没有听懂。您可以试试说：导航去某地、开始避障、查询状态、我在哪里等")
            commandExecutor?.executeUnknown(rawText)
        }
    }

    private fun mapLLMResultToCommand(result: LlmFunctionCaller.Result): VoiceCommand {
        val fnName = result.functionName.lowercase().trim()
        val args = result.arguments ?: "{}"

        return try {
            val argObj = JSONObject(args)
            when (fnName) {
                "navigate_to" -> {
                    val dest = argObj.optString("destination", pendingRawText)
                    VoiceCommand(VoiceCommand.Type.NAVIGATE_TO, dest, pendingRawText)
                }
                "start_obstacle_avoidance" -> VoiceCommand(VoiceCommand.Type.START_OBSTACLE_AVOIDANCE, null, pendingRawText)
                "stop_navigation" -> VoiceCommand(VoiceCommand.Type.STOP_NAVIGATION, null, pendingRawText)
                "stop_obstacle_avoidance" -> VoiceCommand(VoiceCommand.Type.STOP_OBSTACLE_AVOIDANCE, null, pendingRawText)
                "where_am_i" -> VoiceCommand(VoiceCommand.Type.WHERE_AM_I, null, pendingRawText)
                "repeat_last" -> VoiceCommand(VoiceCommand.Type.REPEAT_LAST, null, pendingRawText)
                "preview_route" -> VoiceCommand(VoiceCommand.Type.PREVIEW_ROUTE, null, pendingRawText)
                "query_status" -> VoiceCommand(VoiceCommand.Type.QUERY_STATUS, null, pendingRawText)
                "text_search" -> {
                    val kw = argObj.optString("keyword", pendingRawText)
                    VoiceCommand(VoiceCommand.Type.TEXT_SEARCH, kw, pendingRawText)
                }
                "text_response" -> VoiceCommand(VoiceCommand.Type.TEXT_SEARCH, result.arguments, pendingRawText)
                else -> {
                    Log.w(TAG, "Unknown LLM function: $fnName")
                    VoiceCommand(VoiceCommand.Type.UNKNOWN, null, pendingRawText)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse LLM arguments: $args", e)
            VoiceCommand(VoiceCommand.Type.UNKNOWN, null, pendingRawText)
        }
    }

    // ==================== 指令执行 ====================

    private fun executeCommand(command: VoiceCommand) {
        val executor = commandExecutor
        if (executor == null) {
            speakFeedback("语音助手尚未就绪")
            restoreButtonAfter(2000)
            return
        }

        when (command.type) {
            VoiceCommand.Type.NAVIGATE_TO -> {
                speakFeedback("正在搜索${command.destination}")
                emitStage("🔍 搜索${command.destination}")
                executor.executeNavigateTo(command.destination!!)
            }
            VoiceCommand.Type.START_OBSTACLE_AVOIDANCE -> {
                speakFeedback("正在启动避障模式")
                executor.executeStartObstacleAvoidance()
            }
            VoiceCommand.Type.STOP_NAVIGATION -> {
                if (executor.isNavigating()) {
                    speakFeedback("正在停止导航")
                } else {
                    speakFeedback("当前没有正在进行的导航")
                }
                executor.executeStopNavigation()
            }
            VoiceCommand.Type.STOP_OBSTACLE_AVOIDANCE -> {
                if (executor.isObstacleAvoiding()) {
                    speakFeedback("正在停止避障")
                } else {
                    speakFeedback("当前没有正在进行的避障")
                }
                executor.executeStopObstacleAvoidance()
            }
            VoiceCommand.Type.WHERE_AM_I -> {
                val locDesc = executor.getCurrentLocationDescription()
                if (!locDesc.isNullOrEmpty()) {
                    speakAndToast("您当前位于$locDesc")
                } else {
                    speakAndToast("正在定位中，请稍后再试")
                }
                executor.executeWhereAmI()
            }
            VoiceCommand.Type.REPEAT_LAST -> {
                val lastText = executor.getLastSpokenText()?.takeIf { it.isNotEmpty() }
                    ?: lastFeedbackText
                if (!lastText.isNullOrEmpty()) {
                    speakAndToast(lastText)
                } else {
                    speakAndToast("暂无可重复播报的内容")
                }
                executor.executeRepeatLast()
            }
            VoiceCommand.Type.PREVIEW_ROUTE -> {
                speakAndToast("正在生成路线预览")
                executor.executePreviewRoute()
            }
            VoiceCommand.Type.QUERY_STATUS -> {
                val nav = executor.isNavigating()
                val obs = executor.isObstacleAvoiding()
                val status = (if (nav) "当前正在导航中" else "导航未启动") +
                        "，" + (if (obs) "避障模式已开启" else "避障模式未开启")
                speakAndToast(status)
                executor.executeQueryStatus()
            }
            VoiceCommand.Type.TEXT_SEARCH -> {
                speakAndToast("正在搜索${command.destination}")
                executor.executeTextSearch(command.destination!!)
            }
            VoiceCommand.Type.UNKNOWN -> {
                speakAndToast("抱歉，没有听懂。您可以试试说：导航去某地、开始避障、查询状态、我在哪里等")
                executor.executeUnknown(command.rawText)
            }
        }
        restoreButtonAfter(5000)
    }
}
