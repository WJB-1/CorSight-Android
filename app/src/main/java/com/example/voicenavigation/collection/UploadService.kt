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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    var lastError: String = ""
        private set

    val failedDirections = mutableListOf<String>()

    companion object {
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    /**
     * 分批上传：先传元数据，再逐张传图片
     */
    suspend fun uploadTask(task: CaptureTask): Boolean {
        lastError = ""
        failedDirections.clear()
        val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        return try {
            Log.d("UploadService", "Uploading to $baseUrl, task ${task.pointId}")

            // 步骤1：上传元数据（JSON only）
            val metaSuccess = uploadWithRetry("元数据") {
                uploadMetadata(task)
            }
            if (!metaSuccess) {
                lastError = "元数据上传失败，已重试 $MAX_RETRY 次"
                return false
            }

            // 步骤2：逐张上传图片
            var uploadedCount = 0
            for (dir in directions) {
                val path = task.images[dir] ?: continue
                val file = File(path)
                if (!file.exists()) {
                    Log.w("UploadService", "Image file not found: $path")
                    continue
                }

                Log.d("UploadService", "Uploading image $dir (${file.length() / 1024}KB)")
                val success = uploadWithRetry("图片 $dir") {
                    uploadImage(task.pointId, dir, file)
                }
                if (success) {
                    uploadedCount++
                } else {
                    failedDirections.add(dir)
                    Log.e("UploadService", "Failed to upload image $dir")
                }
            }

            Log.d("UploadService", "Upload complete: $uploadedCount/${directions.size} images")
            if (uploadedCount == 0 && task.images.isNotEmpty()) {
                lastError = "所有图片上传失败"
                false
            } else {
                true
            }
        } catch (e: Exception) {
            lastError = "上传异常: ${e.message}"
            Log.e("UploadService", lastError, e)
            false
        }
    }

    /**
     * 上传元数据（不含图片）
     */
    private fun uploadMetadata(task: CaptureTask): Boolean {
        val jsonData = JSONObject().apply {
            put("point_id", task.pointId)
            put("coordinates", JSONObject().apply {
                put("longitude", task.longitude)
                put("latitude", task.latitude)
            })
            put("scene_description", task.sceneDescription)
            put("images", JSONObject())
        }

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("jsonData", jsonData.toString())

        val request = Request.Builder()
            .url("$baseUrl/api/upload/sampling_point")
            .post(builder.build())
            .build()

        val response = client.newCall(request).execute()
        return response.use {
            val body = it.body?.string() ?: ""
            if (it.isSuccessful) {
                Log.d("UploadService", "Metadata upload success")
                true
            } else {
                lastError = "元数据上传失败 ${it.code}: $body"
                Log.e("UploadService", lastError)
                false
            }
        }
    }

    /**
     * 单张图片上传
     */
    private fun uploadImage(pointId: String, direction: String, file: File): Boolean {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("point_id", pointId)
            .addFormDataPart(
                "image_$direction",
                file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )

        val request = Request.Builder()
            .url("$baseUrl/api/upload/image")
            .post(builder.build())
            .build()

        val response = client.newCall(request).execute()
        return response.use {
            val body = it.body?.string() ?: ""
            if (it.isSuccessful) {
                Log.d("UploadService", "Image $direction upload success")
                true
            } else {
                lastError = "图片上传失败 ${it.code}: $body"
                Log.e("UploadService", lastError)
                false
            }
        }
    }

    /**
     * 带重试的上传包装器
     */
    private inline fun uploadWithRetry(tag: String, block: () -> Boolean): Boolean {
        var attempt = 0
        while (attempt < MAX_RETRY) {
            attempt++
            try {
                if (block()) {
                    return true
                }
                Log.w("UploadService", "$tag 第 $attempt 次尝试失败，准备重试...")
            } catch (e: Exception) {
                Log.w("UploadService", "$tag 第 $attempt 次尝试异常: ${e.message}，准备重试...")
            }
            if (attempt < MAX_RETRY) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        Log.e("UploadService", "$tag 重试 $MAX_RETRY 次后仍然失败")
        return false
    }
}
