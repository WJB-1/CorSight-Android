package com.example.voicenavigation.stt;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public class SpeechRecognitionManager implements RecognitionListener {

    private static final String TAG = "SpeechRecognitionManager";
    private final Context context;
    private SpeechRecognizer speechRecognizer;
    private Intent recognitionIntent;
    private STTCallback callback;
    private Handler handler;

    public interface STTCallback {
        void onResult(String result);
        void onError(String error);
        void onListening();
        void onStopped();
    }

    public SpeechRecognitionManager(Context context) {
        this.context = context;
        handler = new Handler(Looper.getMainLooper());
        initSpeechRecognizer();
    }

    private void initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            speechRecognizer.setRecognitionListener(this);
            recognitionIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString());
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        }
    }

    public void setCallback(STTCallback callback) {
        this.callback = callback;
    }

    public boolean isRecognitionAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public String getRecognitionStatus() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return "语音识别服务不可用";
        }
        if (speechRecognizer == null) {
            return "语音识别器未初始化";
        }
        return "可用";
    }

    public void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            String error = "语音识别服务不可用。请检查：\n1. 设备是否支持语音识别\n2. 是否连接网络\n3. 是否安装了语音识别应用";
            Log.e(TAG, error);
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }

        if (speechRecognizer != null) {
            try {
                speechRecognizer.startListening(recognitionIntent);
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied for speech recognition", e);
                if (callback != null) {
                    callback.onError("录音权限被拒绝，请在设置中开启麦克风权限");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting listening", e);
                if (callback != null) {
                    callback.onError("启动语音识别失败: " + e.getMessage());
                }
            }
        } else {
            if (callback != null) {
                callback.onError("语音识别器初始化失败");
            }
        }
    }

    public void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
    }

    public void destroyRecognizer() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        Log.d(TAG, "Ready for speech");
        if (callback != null) {
            callback.onListening();
        }
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "Beginning of speech");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "End of speech");
    }

    @Override
    public void onError(int error) {
        String errorMessage = "";
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                errorMessage = "音频错误";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                errorMessage = "客户端错误";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                errorMessage = "权限不足";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                errorMessage = "网络错误";
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                errorMessage = "网络超时";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                errorMessage = "未识别到语音";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                errorMessage = "识别器忙";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                errorMessage = "服务器错误";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                errorMessage = "语音超时";
                break;
            default:
                errorMessage = "未知错误: " + error;
        }
        Log.e(TAG, "Speech recognition error: " + errorMessage);
        if (callback != null) {
            callback.onError(errorMessage);
        }
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String result = matches.get(0);
            Log.d(TAG, "Recognition result: " + result);
            if (callback != null) {
                callback.onResult(result);
            }
        } else {
            if (callback != null) {
                callback.onError("未识别到语音内容");
            }
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String result = matches.get(0);
            Log.d(TAG, "Partial result: " + result);
            if (callback != null) {
                callback.onResult(result);
            }
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }
}