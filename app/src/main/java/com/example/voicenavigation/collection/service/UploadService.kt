package com.example.voicenavigation.collection.service

import android.util.Log
import com.example.voicenavigation.collection.data.CaptureTask
import com.example.voicenavigation.collection.data.PhotoRecord
import com.example.voicenavigation.collection.data.TaskStorage
import com.example.voicenavigation.collection.data.TaskStatus
import com.example.voicenavigation.collection.data.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 两步上传服务，对齐后端 api_contract.md。
 *
 * 流程：
 * ① POST /api/upload/metadata → 获取 upload_session_id
 * ② POST /api/upload/image   → 逐张上传（同一 session 可并发）
 * ③ GET  /api/upload/session/:id → 查询进度/恢复
 */
class UploadService(
    private val baseUrl: String,
    private val taskStorage: TaskStorage
) {

    companion object {
        private const val TAG = "UploadService"
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val MAX_CONCURRENT_UPLOADS = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val jpegMediaType = "image/jpeg".toMediaType()

    var lastError: String = ""
        private set

    // ===== 主上传流程 =====

    /**
     * 上传整个任务（两步）。
     * 前提：任务的所有照片已采集完成。
     *
     * @param onPhotoProgress (photoId, 0~100) 单张照片进度
     * @param onPhotoResult   (photoId, success) 单张照片结果
     */
    suspend fun uploadTask(
        task: CaptureTask,
        onPhotoProgress: (String, Int) -> Unit = { _, _ -> },
        onPhotoResult: (String, Boolean) -> Unit = { _, _ -> }
    ): Boolean {
        lastError = ""
        taskStorage.updateTaskStatus(task.pointId, TaskStatus.UPLOADING)

        // Step 1: 提交元数据获取 session_id
        val sessionId = if (task.uploadSessionId != null) {
            task.uploadSessionId!!
        } else {
            val sid = submitMetadata(task)
            if (sid == null) {
                lastError = "元数据提交失败: $lastError"
                taskStorage.updateTaskStatus(task.pointId, TaskStatus.FAILED)
                return false
            }
            taskStorage.updateUploadSessionId(task.pointId, sid)
            sid
        }

        // Step 2: 逐张上传图片（并发控制）
        var allSuccess = true
        val pendingPhotos = task.photos.filter {
            it.uploadStatus != UploadStatus.UPLOADED
        }

        // 按批次并发上传（每批最多 MAX_CONCURRENT_UPLOADS 张）
        pendingPhotos.chunked(MAX_CONCURRENT_UPLOADS).forEach { batch ->
            val results = coroutineScope {
                batch.map { photo ->
                    async(Dispatchers.IO) {
                        taskStorage.updatePhotoUploadStatus(task.pointId, photo.id, UploadStatus.UPLOADING)
                        val ok = uploadSingleImage(sessionId, task.pointId, photo) { pct ->
                            onPhotoProgress(photo.id, pct)
                        }
                        if (ok) {
                            taskStorage.updatePhotoUploadStatus(task.pointId, photo.id, UploadStatus.UPLOADED)
                        } else {
                            taskStorage.updatePhotoUploadStatus(task.pointId, photo.id, UploadStatus.FAILED)
                        }
                        onPhotoResult(photo.id, ok)
                        ok
                    }
                }.awaitAll()
            }
            if (results.any { !it }) allSuccess = false
        }

        taskStorage.updateTaskStatus(
            task.pointId,
            if (allSuccess) TaskStatus.SUCCESS else TaskStatus.FAILED
        )
        return allSuccess
    }

    /**
     * 单张重传（补拍场景或手动重试）。
     * 需要已有 uploadSessionId。
     */
    suspend fun uploadSinglePhoto(
        task: CaptureTask,
        photo: PhotoRecord,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        lastError = ""
        val sessionId = task.uploadSessionId
        if (sessionId == null) {
            lastError = "无 session_id，请先上传整个任务"
            return false
        }

        taskStorage.updatePhotoUploadStatus(task.pointId, photo.id, UploadStatus.UPLOADING)
        val ok = uploadSingleImage(sessionId, task.pointId, photo) { pct ->
            onProgress(pct)
        }
        if (ok) {
            taskStorage.updatePhotoUploadStatus(task.pointId, photo.id, UploadStatus.UPLOADED)
        } else {
            taskStorage.updatePhotoUploadStatus(task.pointId, photo.id, UploadStatus.FAILED)
        }
        return ok
    }

    // ===== Step 1: 提交元数据 =====

    private fun submitMetadata(task: CaptureTask): String? {
        val imagesArray = JSONArray()
        task.photos.forEach { photo ->
            imagesArray.put(JSONObject().apply {
                put("bearing", photo.bearing.toDouble())
                put("fov", photo.fov.toDouble())
                if (photo.description.isNotEmpty()) put("description", photo.description)
            })
        }

        val body = JSONObject().apply {
            put("point_id", task.pointId)
            put("location", JSONObject().apply {
                put("lng", task.longitude)
                put("lat", task.latitude)
            })
            put("crs", task.crs.name)
            if (task.sceneDescription.isNotEmpty()) put("scene_description", task.sceneDescription)
            put("images", imagesArray)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/api/upload/metadata")
            .post(body)
            .build()

        return retryWithBackoff("submitMetadata") {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                if (response.code == 201) {
                    val json = JSONObject(respBody)
                    json.optString("upload_session_id", "")
                } else {
                    lastError = "HTTP ${response.code}: $respBody"
                    Log.e(TAG, "submitMetadata failed: $lastError")
                    null
                }
            }
        }
    }

    // ===== Step 2: 上传单张图片 =====

    private fun uploadSingleImage(
        sessionId: String,
        pointId: String,
        photo: PhotoRecord,
        onProgress: (Int) -> Unit
    ): Boolean {
        val file = File(photo.filePath)
        if (!file.exists()) {
            lastError = "文件不存在: ${photo.filePath}"
            Log.e(TAG, lastError)
            return false
        }

        return retryWithBackoff("uploadImage:${photo.id}") {
            val fileBody = file.asRequestBody(jpegMediaType)
            val progressBody = CountingRequestBody(fileBody) { bytesSent, total ->
                if (total > 0) {
                    onProgress((bytesSent * 100 / total).toInt().coerceIn(0, 100))
                }
            }

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_session_id", sessionId)
                .addFormDataPart("bearing", photo.bearing.toInt().toString())
                .addFormDataPart("image", file.name, progressBody)
                .apply {
                    if (photo.description.isNotEmpty()) {
                        addFormDataPart("description", photo.description)
                    }
                }
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/upload/image")
                .post(multipart)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.d(TAG, "Image uploaded: ${photo.bearing}° for $pointId")
                    true
                } else {
                    lastError = "HTTP ${response.code}: $respBody"
                    Log.e(TAG, "uploadSingleImage failed: $lastError")
                    false
                }
            }
        }
    }

    // ===== Session 状态查询 =====

    /**
     * 查询上传 session 状态，用于恢复未完成的上传。
     */
    suspend fun checkSessionStatus(sessionId: String): SessionStatus? {
        val request = Request.Builder()
            .url("$baseUrl/api/upload/session/$sessionId")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string() ?: return null)
                val data = json.optJSONObject("data") ?: json
                SessionStatus(
                    sessionId = data.optString("session_id", sessionId),
                    pointId = data.optString("point_id", ""),
                    status = data.optString("status", ""),
                    totalImages = data.optInt("total_images", 0),
                    uploadedBearings = mutableListOf<Double>().apply {
                        data.optJSONArray("uploaded_bearings")?.let { arr ->
                            for (i in 0 until arr.length()) add(arr.getDouble(i))
                        }
                    },
                    pendingBearings = mutableListOf<Double>().apply {
                        data.optJSONArray("pending_bearings")?.let { arr ->
                            for (i in 0 until arr.length()) add(arr.getDouble(i))
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkSessionStatus error", e)
            null
        }
    }

    // ===== 工具方法 =====

    private inline fun <T> retryWithBackoff(tag: String, block: () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: IOException) {
                Log.w(TAG, "$tag attempt $attempt failed: ${e.message}")
                if (attempt >= MAX_RETRY) {
                    lastError = "$tag 重试 $MAX_RETRY 次后失败: ${e.message}"
                    throw e
                }
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
    }
}

data class SessionStatus(
    val sessionId: String,
    val pointId: String,
    val status: String,
    val totalImages: Int,
    val uploadedBearings: List<Double>,
    val pendingBearings: List<Double>
)
