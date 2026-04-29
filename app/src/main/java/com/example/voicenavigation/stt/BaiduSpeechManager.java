package com.example.voicenavigation.stt;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.baidu.speech.EventListener;
import com.baidu.speech.EventManager;
import com.baidu.speech.EventManagerFactory;
import com.baidu.speech.asr.SpeechConstant;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class BaiduSpeechManager {

    private static final String TAG = "BaiduSpeechManager";
    private static final long AUTO_STOP_TIMEOUT = 8000;

    private final Context context;
    private EventManager asr;
    private EventListener eventListener;
    private STTCallback callback;
    private Handler handler;
    private boolean isListening = false;
    private boolean resultDelivered = false;

    private final Runnable stopRunnable = () -> {
        Log.d(TAG, "Auto-stop timeout reached");
        if (isListening) {
            stopListening();
        }
    };

    public interface STTCallback {
        void onPartialResult(String result);
        void onResult(String result);
        void onError(String error);
        void onListening();
        void onStopped();
    }

    public BaiduSpeechManager(Context context) {
        this.context = context;
        handler = new Handler(Looper.getMainLooper());
        initSpeechRecognizer();
    }

    private void initSpeechRecognizer() {
        try {
            asr = EventManagerFactory.create(context, "asr");
            Log.d(TAG, "EventManagerFactory.create() succeeded");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create EventManager: " + e.getMessage(), e);
            Log.e(TAG, "Possible causes: 1. SDK not properly loaded 2. Missing libs/bdasr.aar 3. API Key not configured");
            asr = null;
            return;
        }
        
        try {
            eventListener = (name, params, data, offset, length) ->
                    BaiduSpeechManager.this.onEvent(name, params);
            asr.registerListener(eventListener);
            Log.d(TAG, "Baidu ASR initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register EventListener: " + e.getMessage(), e);
            asr = null;
        }
    }

    private void onEvent(String name, String params) {
        Log.d(TAG, "ASR Event: " + name + ", params: " + params);

        if (params == null || params.isEmpty()) {
            Log.w(TAG, "Event " + name + " has null/empty params, skipping");
            return;
        }

        try {
            if (name.equals(SpeechConstant.CALLBACK_EVENT_ASR_READY)) {
                Log.d(TAG, "ASR ready");
            } else if (name.equals(SpeechConstant.CALLBACK_EVENT_ASR_PARTIAL)) {
                JSONObject json = new JSONObject(params);
                String resultType = json.optString("result_type", "");
                String result = json.optString("best_result", json.optString("results_recognition", ""));
                if ("partial_result".equals(resultType) && !result.isEmpty()) {
                    Log.d(TAG, "Partial result: " + result);
                    notifyPartialResult(result);
                }
            } else if (name.equals(SpeechConstant.CALLBACK_EVENT_ASR_FINISH)) {
                if (resultDelivered) {
                    Log.d(TAG, "Result already delivered, skipping FINISH");
                    isListening = false;
                    handler.removeCallbacks(stopRunnable);
                    notifyStopped();
                    return;
                }
                JSONObject json = new JSONObject(params);
                String result = json.optString("best_result", json.optString("results_recognition", ""));
                Log.d(TAG, "Final result: " + result);
                if (!result.isEmpty()) {
                    resultDelivered = true;
                    notifyResult(result);
                }
                isListening = false;
                handler.removeCallbacks(stopRunnable);
                notifyStopped();
            } else if (name.equals(SpeechConstant.CALLBACK_EVENT_ASR_ERROR)) {
                resultDelivered = true;
                JSONObject json = new JSONObject(params);
                int errorCode = json.optInt("error_code", -1);
                String errorMessage = json.optString("error_desc", json.optString("desc", "未知错误"));
                Log.e(TAG, "ASR error: " + errorCode + " - " + errorMessage);
                String error = translateErrorCode(errorCode, errorMessage);
                notifyError(error);
                isListening = false;
                handler.removeCallbacks(stopRunnable);
            } else if (name.equals(SpeechConstant.CALLBACK_EVENT_ASR_EXIT)) {
                Log.d(TAG, "ASR exit");
                isListening = false;
                handler.removeCallbacks(stopRunnable);
            } else {
                Log.d(TAG, "Unhandled ASR event: " + name);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse JSON for event [" + name + "], params: " + params, e);
            notifyError("解析结果失败");
        }
    }

    public void setCallback(STTCallback callback) {
        this.callback = callback;
    }

    public boolean isRecognitionAvailable() {
        return asr != null;
    }

    public String getRecognitionStatus() {
        if (asr == null) {
            return "百度语音识别器未初始化";
        }
        return "可用";
    }

    public void startListening() {
        if (asr == null) {
            String error = "语音识别器未初始化";
            Log.e(TAG, error);
            notifyError(error);
            return;
        }

        isListening = true;
        resultDelivered = false;
        notifyListening();

        Map<String, Object> params = new HashMap<>();
        params.put(SpeechConstant.ACCEPT_AUDIO_VOLUME, false);
        params.put(SpeechConstant.NLU, "enable");
        params.put(SpeechConstant.VAD_ENDPOINT_TIMEOUT, 0);
        params.put(SpeechConstant.VAD, SpeechConstant.VAD_TOUCH);
        params.put(SpeechConstant.WP_VAD_ENABLE, false);

        JSONObject jsonObject = new JSONObject(params);
        String jsonParam = jsonObject.toString();

        Log.d(TAG, "Starting Baidu ASR with params: " + jsonParam);

        try {
            asr.send(SpeechConstant.ASR_START, jsonParam, null, 0, 0);
            handler.postDelayed(stopRunnable, AUTO_STOP_TIMEOUT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ASR", e);
            notifyError("启动语音识别失败: " + e.getMessage());
            isListening = false;
        }
    }

    public void stopListening() {
        handler.removeCallbacks(stopRunnable);
        if (asr != null && isListening) {
            try {
                asr.send(SpeechConstant.ASR_STOP, null, null, 0, 0);
                isListening = false;
                notifyStopped();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop ASR", e);
            }
        }
    }

    public void cancelListening() {
        handler.removeCallbacks(stopRunnable);
        if (asr != null) {
            try {
                asr.send(SpeechConstant.ASR_CANCEL, null, null, 0, 0);
                isListening = false;
                notifyStopped();
            } catch (Exception e) {
                Log.e(TAG, "Failed to cancel ASR", e);
            }
        }
    }

    public void destroyRecognizer() {
        handler.removeCallbacks(stopRunnable);
        if (asr != null) {
            try {
                asr.send(SpeechConstant.ASR_CANCEL, null, null, 0, 0);
                if (eventListener != null) {
                    asr.unregisterListener(eventListener);
                    eventListener = null;
                }
                asr = null;
            } catch (Exception e) {
                Log.e(TAG, "Failed to destroy ASR", e);
            }
        }
        isListening = false;
    }

    private void notifyResult(String result) {
        handler.post(() -> {
            if (callback != null) {
                callback.onResult(result);
            }
        });
    }

    private void notifyPartialResult(String result) {
        handler.post(() -> {
            if (callback != null) {
                callback.onPartialResult(result);
            }
        });
    }

    private void notifyError(String error) {
        handler.post(() -> {
            if (callback != null) {
                callback.onError(error);
            }
        });
    }

    private void notifyListening() {
        handler.post(() -> {
            if (callback != null) {
                callback.onListening();
            }
        });
    }

    private void notifyStopped() {
        handler.post(() -> {
            if (callback != null) {
                callback.onStopped();
            }
        });
    }

    private String translateErrorCode(int errorCode, String errorMessage) {
        switch (errorCode) {
            case 1000:
                return "语音识别失败：网络连接超时";
            case 1001:
                return "语音识别失败：网络连接失败";
            case 2000:
                return "语音识别失败：服务端错误";
            case 3000:
                return "语音识别失败：参数错误";
            case 3300:
                return "语音识别失败：API Key 错误，请检查配置";
            case 3301:
                return "语音识别失败：API Key 过期";
            case 3302:
                return "语音识别失败：API Key 不存在";
            case 3307:
                return "语音识别失败：权限不足";
            case 3308:
                return "语音识别失败：请求超限";
            case 3309:
                return "语音识别失败：服务未开通";
            case 4000:
                return "语音识别失败：音频格式错误";
            case 4001:
                return "语音识别失败：音频采样率错误";
            case 4002:
                return "语音识别失败：音频通道数错误";
            case 5000:
                return "语音识别失败：没有检测到语音";
            case 5001:
                return "语音识别失败：语音过长";
            case 5002:
                return "语音识别失败：语音过短";
            default:
                return "语音识别失败：" + errorMessage;
        }
    }
}