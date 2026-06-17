package com.example.voicenavigation.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 行前预览服务：向后端发送用户当前定位和目的地，获取行前预览信息。
 */
class TripPreviewService(
    var baseUrl: String = DEFAULT_BASE_URL
) {

    companion object {
        private const val TAG = "TripPreviewService"
        // 服务端地址由 core/network/ServerConfig.kt 统一定义
        val DEFAULT_BASE_URL: String = com.example.voicenavigation.core.network.ServerConfig.EXTERNAL_URL
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun sendPreviewRequest(
        originLat: Double, originLng: Double,
        destLat: Double, destLng: Double,
        previewCallback: PreviewCallback
    ) {
        val url = "$baseUrl/api/navigation/preview"

        val requestBody = JSONObject().apply {
            put("origin", "$originLng,$originLat")
            put("destination", "$destLng,$destLat")
        }

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Sending preview request to $url")
        Log.d(TAG, "Request body: ${requestBody}")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Preview request failed", e)
                mainHandler.post { previewCallback.onError("网络请求失败: ${e.message}") } // TODO: migrate to strings.xml when Context is available via DI
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.d(TAG, "Preview request success: $responseBody")
                    mainHandler.post { previewCallback.onSuccess(responseBody) }
                } else {
                    Log.e(TAG, "Preview request error, code=${response.code}, body=$responseBody")
                    mainHandler.post { previewCallback.onError("服务器错误 (${response.code}): $responseBody") } // TODO: migrate to strings.xml when Context is available via DI
                }
            }
        })
    }

    fun sendFixedPreviewRequest(routeId: String, previewCallback: PreviewCallback) {
        val url = "$baseUrl/api/navigation/preview/fixed/$routeId"

        val requestBody = JSONObject().apply {
            put("options", JSONObject().apply {
                put("enable_perception", true)
                put("enable_broadcast", true)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Sending fixed preview request to $url")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Fixed preview request failed", e)
                mainHandler.post { previewCallback.onError("网络请求失败: ${e.message}") } // TODO: migrate to strings.xml when Context is available via DI
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.d(TAG, "Fixed preview success: $responseBody")
                    mainHandler.post { previewCallback.onSuccess(responseBody) }
                } else {
                    Log.e(TAG, "Fixed preview error, code=${response.code}, body=$responseBody")
                    mainHandler.post { previewCallback.onError("服务器错误 (${response.code}): $responseBody") } // TODO: migrate to strings.xml when Context is available via DI
                }
            }
        })
    }

    fun cancelAll() {
        httpClient.dispatcher.queuedCalls().forEach { it.cancel() }
        httpClient.dispatcher.runningCalls().forEach { it.cancel() }
    }

    interface PreviewCallback {
        fun onSuccess(response: String)
        fun onError(error: String)
    }
}
