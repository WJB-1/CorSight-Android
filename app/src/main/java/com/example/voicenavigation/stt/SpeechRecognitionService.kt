package com.example.voicenavigation.stt

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SpeechRecognitionService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "SpeechRecognitionService"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionIntent: Intent? = null
    var callback: STTCallback? = null
    private lateinit var handler: Handler

    /**
     * STT callback interface. Compatible with Java callers.
     */
    interface STTCallback {
        fun onResult(result: String)
        fun onError(error: String)
        fun onListening()
        fun onStopped()
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        initSpeechRecognizer()
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer!!.setRecognitionListener(this)
            recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        }
    }

    fun startListening() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer!!.startListening(recognitionIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting listening", e)
                callback?.onError("启动语音识别失败: ${e.message}")
            }
        } else {
            callback?.onError("语音识别不可用")
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ==================== RecognitionListener ====================

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Ready for speech")
        callback?.onListening()
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "Beginning of speech")
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "End of speech")
    }

    override fun onError(error: Int) {
        val errorMessage = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "音频错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
            SpeechRecognizer.ERROR_SERVER -> "服务器错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
            else -> "未知错误: $error"
        }
        Log.e(TAG, "Speech recognition error: $errorMessage")
        callback?.onError(errorMessage)
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val result = matches[0]
            Log.d(TAG, "Recognition result: $result")
            callback?.onResult(result)
        } else {
            callback?.onError("未识别到语音内容")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val result = matches[0]
            Log.d(TAG, "Partial result: $result")
            callback?.onResult(result)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyRecognizer()
        super.onDestroy()
    }
}
