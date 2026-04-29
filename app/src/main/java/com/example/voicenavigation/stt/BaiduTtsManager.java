package com.example.voicenavigation.stt;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class BaiduTtsManager {

    private static final String TAG = "BaiduTtsManager";
    private static final String TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token";
    private static final String TTS_URL = "https://tsn.baidu.com/text2audio";

    private final Context context;
    private final String apiKey;
    private final String secretKey;
    private String accessToken;
    private final Handler mainHandler;
    private MediaPlayer mediaPlayer;
    private TtsCallback callback;
    private boolean isSpeaking = false;

    public interface TtsCallback {
        void onTtsReady();
        void onTtsError(String error);
    }

    public BaiduTtsManager(Context context, String apiKey, String secretKey) {
        this.context = context;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(TtsCallback callback) {
        this.callback = callback;
    }

    public void init() {
        Log.d(TAG, "Initializing BaiduTtsManager...");
        new Thread(() -> {
            try {
                fetchToken();
                mainHandler.post(() -> {
                    Log.d(TAG, "BaiduTtsManager initialized, token ready");
                    if (callback != null) callback.onTtsReady();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to init BaiduTtsManager", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onTtsError("TTS初始化失败: " + e.getMessage());
                });
            }
        }).start();
    }

    private void fetchToken() throws Exception {
        Log.d(TAG, "Fetching Baidu TTS token...");
        String params = "grant_type=client_credentials&client_id=" + apiKey + "&client_secret=" + secretKey;
        URL url = new URL(TOKEN_URL + "?" + params);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        Log.d(TAG, "Token response code: " + code);

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        JSONObject json = new JSONObject(response.toString());
        if (json.has("access_token")) {
            accessToken = json.getString("access_token");
            Log.d(TAG, "Token fetched successfully, expires_in: " + json.optInt("expires_in"));
        } else {
            String error = json.optString("error_description", json.toString());
            throw new Exception("Token fetch failed: " + error);
        }
    }

    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        Log.d(TAG, "Speak: " + text);
        new Thread(() -> synthesizeAndPlay(text)).start();
    }

    private void synthesizeAndPlay(String text) {
        try {
            if (accessToken == null) {
                Log.e(TAG, "No access token");
                notifyError("TTS token not ready");
                return;
            }

            String cuid = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (cuid == null) cuid = "voice_navigation_app";

            String encodedText = URLEncoder.encode(text, "UTF-8");
            String params = "tex=" + encodedText + "&lan=zh&cuid=" + cuid
                    + "&ctp=1&tok=" + accessToken + "&per=0&spd=5&pit=5&vol=15";

            URL url = new URL(TTS_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            OutputStream os = conn.getOutputStream();
            os.write(params.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String contentType = conn.getContentType();

            Log.d(TAG, "TTS response code: " + code + ", contentType: " + contentType);

            if (contentType != null && contentType.contains("audio")) {
                byte[] audioData = readAllBytes(conn.getInputStream());
                Log.d(TAG, "TTS audio received: " + audioData.length + " bytes");
                playAudioData(audioData);
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                Log.e(TAG, "TTS API returned text: " + response);
                notifyError("语音合成失败: " + response);
            }

            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "TTS synthesize failed", e);
            notifyError("语音合成失败: " + e.getMessage());
        }
    }

    private void playAudioData(byte[] audioData) {
        try {
            stopPlayback();

            File cacheFile = new File(context.getCacheDir(), "baidu_tts_temp.mp3");
            FileOutputStream fos = new FileOutputStream(cacheFile);
            fos.write(audioData);
            fos.flush();
            fos.close();

            isSpeaking = true;
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(cacheFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "MediaPlayer prepared, starting playback");
                mp.start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "MediaPlayer completed");
                isSpeaking = false;
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
                isSpeaking = false;
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play audio", e);
            isSpeaking = false;
            notifyError("播放语音失败");
        }
    }

    public void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping MediaPlayer", e);
            }
            mediaPlayer = null;
        }
        isSpeaking = false;
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }

    public void destroy() {
        stopPlayback();
        accessToken = null;
    }

    private void notifyError(final String error) {
        mainHandler.post(() -> {
            if (callback != null) callback.onTtsError(error);
        });
    }

    private byte[] readAllBytes(InputStream inputStream) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int n;
        while ((n = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, n);
        }
        inputStream.close();
        return buffer.toByteArray();
    }
}
