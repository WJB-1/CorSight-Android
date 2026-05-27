package com.example.voicenavigation.collection

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UploadService(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    var lastError: String = ""
        private set

    suspend fun uploadTask(task: CaptureTask): Boolean {
        lastError = ""
        return try {
            val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

            // 构建 multipart body：jsonData + 8张图片
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)

            // 1. JSON 元数据
            val jsonData = JSONObject().apply {
                put("point_id", task.pointId)
                put("coordinates", JSONObject().apply {
                    put("longitude", task.longitude)
                    put("latitude", task.latitude)
                })
                put("scene_description", task.sceneDescription)
                put("images", JSONObject())
            }
            builder.addFormDataPart("jsonData", jsonData.toString())

            // 2. 8 张图片（字段名 image_N, image_NE, ...）
            var imageCount = 0
            for (dir in directions) {
                val path = task.images[dir]
                if (path == null) {
                    lastError = "缺少 $dir 方向图片"
                    Log.e("UploadService", lastError)
                    return false
                }
                val file = File(path)
                if (!file.exists()) {
                    lastError = "图片文件不存在: $dir"
                    Log.e("UploadService", lastError)
                    return false
                }
                builder.addFormDataPart(
                    "image_$dir",
                    file.name,
                    file.asRequestBody("image/jpeg".toMediaType())
                )
                imageCount++
            }

            Log.d("UploadService", "Uploading ${task.pointId} with $imageCount images to $baseUrl/api/upload/sampling_point")

            val request = Request.Builder()
                .url("$baseUrl/api/upload/sampling_point")
                .post(builder.build())
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Log.d("UploadService", "Upload success: $responseBody")
                true
            } else {
                lastError = "服务器错误 ${response.code}: $responseBody"
                Log.e("UploadService", lastError)
                false
            }
        } catch (e: Exception) {
            lastError = "上传异常: ${e.message}"
            Log.e("UploadService", lastError, e)
            false
        }
    }
}
