package com.example.voicenavigation.collection

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class UploadService(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    var lastError: String = ""
        private set

    /** 记录上传失败的方向，供外部检查 */
    val failedDirections = mutableListOf<String>()

    companion object {
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    /**
     * 上传任务：先一次性上传所有数据，然后根据后端返回检查哪些图片缺失，单独补传
     */
    suspend fun uploadTask(task: CaptureTask): Boolean {
        lastError = ""
        failedDirections.clear()
        val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        return try {
            Log.d("UploadService", "Uploading task ${task.pointId} with ${task.images.size} images")

            // 步骤1：一次性上传 JSON + 所有图片
            val responseBody = uploadWithRetryBody("采样点数据") {
                uploadAll(task, directions)
            }

            if (responseBody == null) {
                lastError = "上传失败，已重试 $MAX_RETRY 次"
                return false
            }

            // 步骤2：解析后端返回，检查哪些图片保存成功
            val savedImages = parseSavedImages(responseBody)
            Log.d("UploadService", "Server saved images: $savedImages")

            // 步骤3：找出缺失的图片并补传
            val missingDirs = directions.filter { dir ->
                savedImages[dir] == null && task.images[dir] != null
            }

            if (missingDirs.isNotEmpty()) {
                Log.w("UploadService", "Missing images after upload: $missingDirs")
                for (dir in missingDirs) {
                    val path = task.images[dir] ?: continue
                    val file = File(path)
                    if (!file.exists()) continue

                    val ok = uploadWithRetry("补传图片 $dir") {
                        uploadSingleImage(task.pointId, dir, file)
                    }
                    if (!ok) {
                        failedDirections.add(dir)
                        Log.e("UploadService", "Failed to upload image $dir after retries")
                    }
                }
            }

            if (failedDirections.isNotEmpty()) {
                lastError = "以下方向上传失败: ${failedDirections.joinToString(", ")}"
                Log.e("UploadService", lastError)
            }

            // 只要元数据保存成功，就返回 true（部分图片失败不影响整体）
            true
        } catch (e: Exception) {
            lastError = "上传异常: ${e.message}"
            Log.e("UploadService", lastError, e)
            false
        }
    }

    /**
     * 一次性上传 JSON + 所有图片
     */
    private fun uploadAll(task: CaptureTask, directions: List<String>): String? {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("jsonData", JSONObject().apply {
                put("point_id", task.pointId)
                put("coordinates", JSONObject().apply {
                    put("longitude", task.longitude)
                    put("latitude", task.latitude)
                })
                put("scene_description", task.sceneDescription)
            }.toString())

        var imageCount = 0
        for (dir in directions) {
            val path = task.images[dir] ?: continue
            val file = File(path)
            if (!file.exists()) continue

            builder.addFormDataPart(
                "image_$dir",
                file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
            imageCount++
        }

        Log.d("UploadService", "Uploading $imageCount images total")

        val request = Request.Builder()
            .url("$baseUrl/api/upload/sampling_point")
            .post(builder.build())
            .build()

        val response = client.newCall(request).execute()
        return response.use {
            val body = it.body?.string() ?: ""
            if (it.isSuccessful) {
                body
            } else {
                lastError = "上传失败 ${it.code}: $body"
                Log.e("UploadService", lastError)
                null
            }
        }
    }

    /**
     * 单张图片补传
     */
    private fun uploadSingleImage(pointId: String, direction: String, file: File): Boolean {
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
     * 解析后端返回的已保存图片列表
     */
    private fun parseSavedImages(responseBody: String): Map<String, String?> {
        return try {
            val json = JSONObject(responseBody)
            val data = json.optJSONObject("data") ?: return emptyMap()
            val images = data.optJSONObject("images") ?: return emptyMap()

            val result = mutableMapOf<String, String?>()
            val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            for (dir in directions) {
                result[dir] = if (images.isNull(dir)) null else images.getString(dir)
            }
            result
        } catch (e: Exception) {
            Log.e("UploadService", "Parse response failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 带重试的上传包装器（返回 String）
     */
    private inline fun uploadWithRetryBody(tag: String, block: () -> String?): String? {
        var attempt = 0
        while (attempt < MAX_RETRY) {
            attempt++
            try {
                val result = block()
                if (result != null) {
                    return result
                }
                Log.w("UploadService", "$tag 第 $attempt 次尝试失败，准备重试...")
            } catch (e: IOException) {
                Log.w("UploadService", "$tag 第 $attempt 次尝试网络异常: ${e.message}，准备重试...")
            }
            if (attempt < MAX_RETRY) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        Log.e("UploadService", "$tag 重试 $MAX_RETRY 次后仍然失败")
        return null
    }

    /**
     * 带重试的上传包装器（返回 Boolean）
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
            } catch (e: IOException) {
                Log.w("UploadService", "$tag 第 $attempt 次尝试网络异常: ${e.message}，准备重试...")
            }
            if (attempt < MAX_RETRY) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        Log.e("UploadService", "$tag 重试 $MAX_RETRY 次后仍然失败")
        return false
    }
}
