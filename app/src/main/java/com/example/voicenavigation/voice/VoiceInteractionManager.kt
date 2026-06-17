package com.example.voicenavigation.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.voicenavigation.R
import com.example.voicenavigation.stt.BaiduSpeechManager
import com.example.voicenavigation.stt.UnifiedTtsManager
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
    private val ttsManager: UnifiedTtsManager?,
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

    private val interpreter = VoiceCommandInterpreter(context)
    private var currentMode = Mode.TEXT_INPUT
    private var textInputListener: TextInputListener? = null
    private var commandExecutor: CommandExecutor? = null
    private var voiceEventListener: VoiceEventListener? = null
    private var lastFeedbackText: String? = ""
    private var pendingRawText = ""
    /** 用户主动取消后，忽略后续的 STT 回调 */
    @Volatile private var cancelled = false
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
        cancelled = false
        Log.d(TAG, "startListening mode=$mode")
        if (mode == Mode.COMMAND) {
            ttsManager?.flushQueue()
        }
        speechManager.startListening()
    }

    fun stopListening(cancel: Boolean = false) {
        Log.d(TAG, "stopListening cancel=$cancel")
        cancelled = cancel
        speechManager.stopListening()

        if (!cancel && currentMode == Mode.COMMAND) {
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
                showToast(context.getString(R.string.tts_timeout_no_result))
                emitStage(context.getString(R.string.stage_voice_assistant))
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
    fun getTtsManager(): UnifiedTtsManager? = ttsManager
    fun isLLMAvailable(): Boolean = llmCaller != null && llmCaller.isConfigured()

    /**
     * 释放所有 Activity 级回调引用，防止 Activity 销毁后
     * LLM 回调或延迟 TTS 仍然触发已销毁的 Activity。
     */
    fun release() {
        textInputListener = null
        commandExecutor = null
        voiceEventListener = null
        lastFeedbackText = ""
    }

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
        Handler(context.mainLooper).postDelayed({ emitStage(context.getString(R.string.stage_voice_assistant)) }, delayMs)
    }

    // ==================== STT 回调 ====================

    override fun onResult(result: String) {
        if (cancelled) return  // 用户已取消，忽略结果
        waitingForResult = false
        watchdogHandler.removeCallbacks(getResultTimeoutRunnable())

        Log.d(TAG, "=== STT RESULT === mode=$currentMode text=[$result]")
        if (result.isBlank()) {
            showToast(context.getString(R.string.tts_not_heard))
            emitStage(context.getString(R.string.stage_voice_assistant))
            return
        }
        val trimmed = result.trim()
        Log.d(TAG, "完整的识别文字：【$trimmed】，长度=${trimmed.length}")

        if (currentMode == Mode.TEXT_INPUT) {
            textInputListener?.onTextResult(trimmed)
        } else {
            showToast(context.getString(R.string.msg_stt_recognized, trimmed))
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
        if (cancelled) return  // 用户已取消，忽略错误
        waitingForResult = false
        watchdogHandler.removeCallbacks(getResultTimeoutRunnable())

        Log.e(TAG, "=== STT ERROR === $error")
        showToast(context.getString(R.string.msg_stt_failed, error))
        speakFeedback(context.getString(R.string.msg_stt_failed, error))
        emitStage(context.getString(R.string.stage_voice_assistant))
    }

    override fun onListening() {
        Log.d(TAG, "=== STT LISTENING ===")
        if (currentMode == Mode.COMMAND) {
            showToast(context.getString(R.string.msg_mic_open))
            emitStage(context.getString(R.string.stage_listening))
        }
        voiceEventListener?.onListeningStarted()
    }

    override fun onStopped() {
        Log.d(TAG, "=== STT STOPPED ===")
        if (cancelled) return  // 用户已取消，忽略后续回调
        if (currentMode == Mode.COMMAND) emitStage(context.getString(R.string.stage_recognizing))
        voiceEventListener?.onListeningStopped()
    }

    // ==================== Function Calling 混合架构 ====================

    private fun processCommand(rawText: String) {
        pendingRawText = rawText
        val command = interpreter.interpret(rawText)
        Log.d(TAG, "本地解析结果: type=${command.type} dest=${command.destination}")

        if (command.type != VoiceCommand.Type.UNKNOWN) {
            val typeLabel = command.type.description
            showToast(context.getString(R.string.msg_local_match, typeLabel))
            emitStage("✅ $typeLabel")
            executeCommand(command)
        } else if (llmCaller != null && llmCaller.isConfigured()) {
            showToast(context.getString(R.string.msg_local_miss_cloud))
            emitStage(context.getString(R.string.msg_cloud_requesting))
            Log.d(TAG, "Local miss, falling back to LLM for: $rawText")
            llmCaller.call(rawText, object : LlmFunctionCaller.Callback {
                override fun onSuccess(result: LlmFunctionCaller.Result) {
                    Log.d(TAG, "LLM SUCCESS: function=${result.functionName} args=${result.arguments}")
                    showToast(context.getString(R.string.msg_llm_result, result.functionName))
                    emitStage("☁️ ${result.functionName}")
                    val llmCommand = mapLLMResultToCommand(result)
                    executeCommand(llmCommand)
                }

                override fun onFailure(error: String) {
                    Log.w(TAG, "LLM FAILED: $error")
                    showToast(context.getString(R.string.msg_llm_timeout))
                    emitStage(context.getString(R.string.msg_llm_failed))
                    speakFeedback(context.getString(R.string.tts_not_understood))
                    commandExecutor?.executeUnknown(rawText)
                }
            })
        } else {
            showToast(context.getString(R.string.msg_local_miss_no_llm))
            emitStage(context.getString(R.string.msg_no_response))
            speakFeedback(context.getString(R.string.tts_not_understood))
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
            speakFeedback(context.getString(R.string.msg_voice_assistant_not_ready))
            restoreButtonAfter(2000)
            return
        }

        when (command.type) {
            VoiceCommand.Type.NAVIGATE_TO -> {
                speakFeedback(context.getString(R.string.tts_searching, command.destination))
                emitStage(context.getString(R.string.stage_searching, command.destination))
                executor.executeNavigateTo(command.destination!!)
            }
            VoiceCommand.Type.START_OBSTACLE_AVOIDANCE -> {
                speakFeedback(context.getString(R.string.tts_starting_obstacle))
                executor.executeStartObstacleAvoidance()
            }
            VoiceCommand.Type.STOP_NAVIGATION -> {
                if (executor.isNavigating()) {
                    speakFeedback(context.getString(R.string.tts_stopping_navigation))
                } else {
                    speakFeedback(context.getString(R.string.tts_no_active_navigation))
                }
                executor.executeStopNavigation()
            }
            VoiceCommand.Type.STOP_OBSTACLE_AVOIDANCE -> {
                if (executor.isObstacleAvoiding()) {
                    speakFeedback(context.getString(R.string.tts_stopping_obstacle))
                } else {
                    speakFeedback(context.getString(R.string.tts_no_active_obstacle))
                }
                executor.executeStopObstacleAvoidance()
            }
            VoiceCommand.Type.WHERE_AM_I -> {
                val locDesc = executor.getCurrentLocationDescription()
                if (!locDesc.isNullOrEmpty()) {
                    speakAndToast(context.getString(R.string.tts_current_location, locDesc))
                } else {
                    speakAndToast(context.getString(R.string.tts_locating_wait))
                }
                executor.executeWhereAmI()
            }
            VoiceCommand.Type.REPEAT_LAST -> {
                val lastText = executor.getLastSpokenText()?.takeIf { it.isNotEmpty() }
                    ?: lastFeedbackText
                if (!lastText.isNullOrEmpty()) {
                    speakAndToast(lastText)
                } else {
                    speakAndToast(context.getString(R.string.tts_nothing_to_repeat))
                }
                executor.executeRepeatLast()
            }
            VoiceCommand.Type.PREVIEW_ROUTE -> {
                speakAndToast(context.getString(R.string.tts_generating_preview))
                executor.executePreviewRoute()
            }
            VoiceCommand.Type.QUERY_STATUS -> {
                val nav = executor.isNavigating()
                val obs = executor.isObstacleAvoiding()
                val status = (if (nav) context.getString(R.string.tts_nav_active) else context.getString(R.string.tts_nav_inactive)) +
                        "，" + (if (obs) context.getString(R.string.tts_obstacle_active) else context.getString(R.string.tts_obstacle_inactive))
                speakAndToast(status)
                executor.executeQueryStatus()
            }
            VoiceCommand.Type.TEXT_SEARCH -> {
                speakAndToast(context.getString(R.string.tts_searching, command.destination))
                executor.executeTextSearch(command.destination!!)
            }
            VoiceCommand.Type.UNKNOWN -> {
                speakAndToast(context.getString(R.string.tts_not_understood))
                executor.executeUnknown(command.rawText)
            }
        }
        restoreButtonAfter(5000)
    }
}
