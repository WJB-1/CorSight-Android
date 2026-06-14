package com.example.voicenavigation.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.voicenavigation.R
import com.baidu.speech.EventListener
import com.baidu.speech.EventManagerFactory
import com.baidu.speech.asr.SpeechConstant
import org.json.JSONObject

class BaiduSpeechManager(context: Context) {

    companion object {
        private const val TAG = "BaiduSpeechManager"
        private const val AUTO_STOP_TIMEOUT = 8000L
    }

    private val context: Context = context
    private val handler = Handler(Looper.getMainLooper())
    private var asr: com.baidu.speech.EventManager? = null
    private var eventListener: EventListener? = null
    var callback: STTCallback? = null
    private var isListening = false
    private var resultDelivered = false
    /** 记录最后一条 partial 结果，用于 FINISH 未返回时的兜底 */
    private var lastPartialResult = ""
    /** asr.end 后延迟交付兜底结果的 Runnable，等待尾帧 partial 到达 */
    private val pendingFallbackRunnable = Runnable { deliverFallbackIfNeeded() }

    private val stopRunnable = Runnable {
        Log.d(TAG, "Auto-stop timeout reached")
        if (isListening) {
            stopListening()
        }
    }

    /**
     * STT callback interface. Compatible with Java callers -- implement this
     * interface in Java or Kotlin without SAM conversion issues.
     */
    interface STTCallback {
        fun onPartialResult(result: String)
        fun onResult(result: String)
        fun onError(error: String)
        fun onListening()
        fun onStopped()
    }

    init {
        initSpeechRecognizer()
    }

    private fun initSpeechRecognizer() {
        try {
            asr = EventManagerFactory.create(context, "asr")
            Log.d(TAG, "EventManagerFactory.create() succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EventManager: ${e.message}", e)
            Log.e(TAG, "Possible causes: 1. SDK not properly loaded 2. Missing libs/bdasr.aar 3. API Key not configured")
            asr = null
            return
        }

        try {
            eventListener = EventListener { name, params, _, _, _ ->
                onEvent(name, params)
            }
            asr!!.registerListener(eventListener)
            Log.d(TAG, "Baidu ASR initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register EventListener: ${e.message}", e)
            asr = null
        }
    }

    private fun onEvent(name: String, params: String?) {
        Log.d(TAG, "ASR Event: $name, params: $params")

        if (params.isNullOrEmpty()) {
            Log.w(TAG, "Event $name has null/empty params, skipping")
            return
        }

        try {
            when {
                name == SpeechConstant.CALLBACK_EVENT_ASR_READY -> {
                    Log.d(TAG, "ASR ready")
                    notifyListening()
                }
                name == SpeechConstant.CALLBACK_EVENT_ASR_PARTIAL -> {
                    val json = JSONObject(params)
                    val resultType = json.optString("result_type", "")
                    val result = json.optString("best_result", json.optString("results_recognition", ""))
                    if (result.isNotEmpty()) {
                        Log.d(TAG, "Partial/Final result: [$result] type=$resultType isListening=$isListening delivered=$resultDelivered")
                        lastPartialResult = cleanResultText(result)
                        // final_result：百度引擎把最终识别结果通过 PARTIAL 事件发来
                        // 如果还没交过结果，且引擎已停止（!isListening），直接交付
                        if ("final_result" == resultType && !resultDelivered) {
                            handler.removeCallbacks(pendingFallbackRunnable)
                            resultDelivered = true
                            notifyResult(lastPartialResult)
                            return
                        }
                        // 尾帧 partial：引擎已停但结果还在陆续到达，重武装兜底延迟
                        if (!isListening && !resultDelivered) {
                            handler.removeCallbacks(pendingFallbackRunnable)
                            handler.postDelayed(pendingFallbackRunnable, 300)
                        }
                        notifyPartialResult(result)
                    }
                }
                name == SpeechConstant.CALLBACK_EVENT_ASR_FINISH -> {
                    handler.removeCallbacks(pendingFallbackRunnable) // 取消兜底等待
                    if (resultDelivered) {
                        Log.d(TAG, "Result already delivered, skipping FINISH")
                        isListening = false
                        handler.removeCallbacks(stopRunnable)
                        notifyStopped()
                        return
                    }
                    val json = JSONObject(params)
                    val result = json.optString("best_result", json.optString("results_recognition", ""))
                    Log.d(TAG, "Final result: [$result], lastPartial: [$lastPartialResult]")
                    if (result.isNotEmpty() || lastPartialResult.isNotEmpty()) {
                        val finalResult = if (result.isNotEmpty()) result else lastPartialResult
                        Log.d(TAG, "Delivering result: $finalResult")
                        resultDelivered = true
                        notifyResult(finalResult)
                    }
                    isListening = false
                    handler.removeCallbacks(stopRunnable)
                    notifyStopped()
                }
                name == SpeechConstant.CALLBACK_EVENT_ASR_ERROR -> {
                    resultDelivered = true
                    val json = JSONObject(params)
                    val errorCode = json.optInt("error_code", -1)
                    val errorMessage = json.optString("error_desc", json.optString("desc", context.getString(R.string.stt_error_unknown)))
                    Log.e(TAG, "ASR error: $errorCode - $errorMessage")
                    val error = translateErrorCode(errorCode, errorMessage)
                    notifyError(error)
                    isListening = false
                    handler.removeCallbacks(stopRunnable)
                }
                name == SpeechConstant.CALLBACK_EVENT_ASR_EXIT -> {
                    Log.d(TAG, "ASR exit")
                    deliverFallbackIfNeeded()
                }
                name == "asr.end" -> {
                    // asr.end 事件：引擎会话结束。等 300ms 看尾帧 partial 是否到达，再决定是否兜底
                    Log.d(TAG, "ASR end event, lastPartial=[$lastPartialResult], delivered=$resultDelivered")
                    if (!resultDelivered && lastPartialResult.isNotEmpty()) {
                        handler.removeCallbacks(pendingFallbackRunnable)
                        handler.postDelayed(pendingFallbackRunnable, 300)
                    }
                }
                else -> {
                    Log.d(TAG, "Unhandled ASR event: $name")
                }
            }
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "Failed to parse JSON for event [$name], params: $params", e)
            notifyError(context.getString(R.string.stt_error_parse_failed))
        }
    }

    fun isRecognitionAvailable(): Boolean = asr != null

    fun getRecognitionStatus(): String =
        if (asr == null) context.getString(R.string.stt_status_not_initialized) else context.getString(R.string.stt_status_available)

    fun startListening() {
        if (asr == null) {
            val error = context.getString(R.string.stt_error_not_initialized)
            Log.e(TAG, error)
            notifyError(error)
            return
        }

        isListening = true
        resultDelivered = false
        lastPartialResult = ""

        val params = mutableMapOf<String, Any>()
        params[SpeechConstant.ACCEPT_AUDIO_VOLUME] = false
        params[SpeechConstant.NLU] = "enable"
        params[SpeechConstant.VAD_ENDPOINT_TIMEOUT] = 0
        params[SpeechConstant.VAD] = SpeechConstant.VAD_TOUCH
        params[SpeechConstant.WP_VAD_ENABLE] = false

        val jsonParam = JSONObject(params as Map<*, *>).toString()

        Log.d(TAG, "Starting Baidu ASR with params: $jsonParam")

        try {
            asr!!.send(SpeechConstant.ASR_START, jsonParam, null, 0, 0)
            handler.postDelayed(stopRunnable, AUTO_STOP_TIMEOUT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ASR", e)
            notifyError(context.getString(R.string.stt_error_start_failed, e.message))
            isListening = false
        }
    }

    fun stopListening() {
        handler.removeCallbacks(stopRunnable)
        if (asr != null && isListening) {
            try {
                asr!!.send(SpeechConstant.ASR_STOP, null, null, 0, 0)
                isListening = false
                notifyStopped()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ASR", e)
            }
        }
    }

    fun cancelListening() {
        handler.removeCallbacks(stopRunnable)
        if (asr != null) {
            try {
                asr!!.send(SpeechConstant.ASR_CANCEL, null, null, 0, 0)
                isListening = false
                notifyStopped()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel ASR", e)
            }
        }
    }

    fun destroyRecognizer() {
        handler.removeCallbacks(stopRunnable)
        if (asr != null) {
            try {
                asr!!.send(SpeechConstant.ASR_CANCEL, null, null, 0, 0)
                if (eventListener != null) {
                    asr!!.unregisterListener(eventListener)
                    eventListener = null
                }
                asr = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy ASR", e)
            }
        }
        isListening = false
    }

    private fun notifyResult(result: String) {
        handler.post {
            callback?.onResult(result)
        }
    }

    private fun notifyPartialResult(result: String) {
        handler.post {
            callback?.onPartialResult(result)
        }
    }

    private fun notifyError(error: String) {
        handler.post {
            callback?.onError(error)
        }
    }

    /** 清理识别结果末尾的标点 */
    private fun cleanResultText(text: String?): String {
        if (text == null) return ""
        return text.replace(Regex("[。，、！？；：,.!?;:]+$"), "").trim()
    }

    private fun notifyListening() {
        handler.post {
            callback?.onListening()
        }
    }

    private fun notifyStopped() {
        handler.post {
            callback?.onStopped()
        }
    }

    /**
     * 当 ASR 会话结束但没有 FINISH 结果时，用最后一条 partial 作为兜底。
     * 仅在 stopListening 已调用但 FINISH 未到达时使用。
     */
    private fun deliverFallbackIfNeeded() {
        isListening = false
        handler.removeCallbacks(stopRunnable)
        if (!resultDelivered && lastPartialResult.isNotEmpty()) {
            Log.d(TAG, "No FINISH received, delivering last partial as final: $lastPartialResult")
            resultDelivered = true
            notifyResult(lastPartialResult)
        }
    }

    private fun translateErrorCode(errorCode: Int, errorMessage: String): String =
        when (errorCode) {
            1000 -> context.getString(R.string.stt_error_network_timeout)
            1001 -> context.getString(R.string.stt_error_network_failed)
            2000 -> context.getString(R.string.stt_error_server)
            3000 -> context.getString(R.string.stt_error_param)
            3300 -> context.getString(R.string.stt_error_api_key)
            3301 -> context.getString(R.string.stt_error_api_key_expired)
            3302 -> context.getString(R.string.stt_error_api_key_missing)
            3307 -> context.getString(R.string.stt_error_permission)
            3308 -> context.getString(R.string.stt_error_rate_limit)
            3309 -> context.getString(R.string.stt_error_service_not_open)
            4000 -> context.getString(R.string.stt_error_audio_format)
            4001 -> context.getString(R.string.stt_error_audio_sample_rate)
            4002 -> context.getString(R.string.stt_error_audio_channel)
            5000 -> context.getString(R.string.stt_error_no_speech)
            5001 -> context.getString(R.string.stt_error_speech_too_long)
            5002 -> context.getString(R.string.stt_error_speech_too_short)
            else -> context.getString(R.string.stt_error_generic, errorMessage)
        }
}
