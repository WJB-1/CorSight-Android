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
import com.example.voicenavigation.R
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
                callback?.onError(getString(R.string.stt_start_failed, e.message))
            }
        } else {
            callback?.onError(getString(R.string.stt_recognizer_unavailable))
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
            SpeechRecognizer.ERROR_AUDIO -> getString(R.string.stt_error_audio)
            SpeechRecognizer.ERROR_CLIENT -> getString(R.string.stt_error_client)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.stt_error_insufficient_permissions)
            SpeechRecognizer.ERROR_NETWORK -> getString(R.string.stt_error_network)
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> getString(R.string.stt_error_network_timeout)
            SpeechRecognizer.ERROR_NO_MATCH -> getString(R.string.stt_error_no_match)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> getString(R.string.stt_error_recognizer_busy)
            SpeechRecognizer.ERROR_SERVER -> getString(R.string.stt_error_server)
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.stt_error_speech_timeout)
            else -> getString(R.string.stt_error_unknown_code, error)
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
            callback?.onError(getString(R.string.stt_no_speech_content))
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
