package com.example.voicenavigation.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
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
 * 行前预览服务：向后端发送用户当前定位和目的地，获取行前预览信息。
 *
 * <p>使用方式：
 * <pre>
 * TripPreviewService service = new TripPreviewService();
 * service.sendPreviewRequest(currentLat, currentLng, destLat, destLng, new Callback() { ... });
 * </pre>
 *
 * <p><b>后端接口约定（请根据实际后端地址修改 {@link #BASE_URL}）：</b>
 * <ul>
 *   <li>URL: POST /api/trip/preview</li>
 *   <li>请求体 JSON:
 *     <pre>
 *     {
 *       "origin": {
 *         "latitude": 39.9042,
 *         "longitude": 116.4074
 *       },
 *       "destination": {
 *         "latitude": 39.9156,
 *         "longitude": 116.4112
 *       }
 *     }
 *     </pre>
 *   </li>
 *   <li>响应示例:
 *     <pre>
 *     {
 *       "code": 0,
 *       "message": "success",
 *       "data": { ... }
 *     }
 *     </pre>
 *   </li>
 * </ul>
 */
public class TripPreviewService {

    private static final String TAG = "TripPreviewService";

    /** 后端服务基础地址，请根据实际部署环境修改 */
    public static final String DEFAULT_BASE_URL = "https://your-backend-domain.com";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long CONNECT_TIMEOUT_SECONDS = 15;
    private static final long READ_TIMEOUT_SECONDS = 15;

    private final OkHttpClient httpClient;
    private final Handler mainHandler;
    private String baseUrl;

    public TripPreviewService() {
        this(DEFAULT_BASE_URL);
    }

    public TripPreviewService(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置后端服务基础地址。
     *
     * @param baseUrl 例如 "https://api.example.com"
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 发送行前预览请求。
     *
     * @param originLat      起点纬度
     * @param originLng      起点经度
     * @param destLat        终点纬度
     * @param destLng        终点经度
     * @param previewCallback 结果回调（在主线程执行）
     */
    public void sendPreviewRequest(double originLat, double originLng,
                                    double destLat, double destLng,
                                    @NonNull PreviewCallback previewCallback) {
        String url = baseUrl + "/api/trip/preview";

        JSONObject requestBody = new JSONObject();
        try {
            JSONObject origin = new JSONObject();
            origin.put("latitude", originLat);
            origin.put("longitude", originLng);

            JSONObject destination = new JSONObject();
            destination.put("latitude", destLat);
            destination.put("longitude", destLng);

            requestBody.put("origin", origin);
            requestBody.put("destination", destination);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build request JSON", e);
            mainHandler.post(() -> previewCallback.onError("请求参数构建失败: " + e.getMessage()));
            return;
        }

        RequestBody body = RequestBody.create(requestBody.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json")
                .build();

        Log.d(TAG, "Sending preview request to " + url);
        Log.d(TAG, "Request body: " + requestBody.toString());

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Preview request failed", e);
                mainHandler.post(() -> previewCallback.onError("网络请求失败: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    Log.d(TAG, "Preview request success: " + responseBody);
                    mainHandler.post(() -> previewCallback.onSuccess(responseBody));
                } else {
                    Log.e(TAG, "Preview request error, code=" + response.code() + ", body=" + responseBody);
                    mainHandler.post(() -> previewCallback.onError("服务器错误 (" + response.code() + "): " + responseBody));
                }
            }
        });
    }

    /**
     * 取消所有进行中的请求。
     */
    public void cancelAll() {
        for (Call call : httpClient.dispatcher().queuedCalls()) {
            call.cancel();
        }
        for (Call call : httpClient.dispatcher().runningCalls()) {
            call.cancel();
        }
    }

    /**
     * 行前预览结果回调接口。
     */
    public interface PreviewCallback {
        /**
         * 请求成功时调用。
         *
         * @param response 后端返回的原始 JSON 字符串
         */
        void onSuccess(String response);

        /**
         * 请求失败时调用。
         *
         * @param error 错误描述
         */
        void onError(String error);
    }
}
