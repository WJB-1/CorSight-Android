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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun uploadTask(task: CaptureTask): Boolean {
        return try {
            // Step 1: upload metadata
            val metaJson = JSONObject().apply {
                put("point_id", task.pointId)
                put("chunk_id", task.chunkId)
                put("longitude", task.longitude)
                put("latitude", task.latitude)
                put("scene_description", task.sceneDescription)
                put("image_count", 8)
            }

            val metaRequest = Request.Builder()
                .url("$baseUrl/api/upload/upload_meta")
                .post(RequestBody.create(jsonMediaType, metaJson.toString()))
                .build()

            val metaResponse = client.newCall(metaRequest).execute()
            if (!metaResponse.isSuccessful) {
                Log.e("UploadService", "Meta upload failed: ${metaResponse.code}")
                return false
            }

            Thread.sleep(100)

            // Step 2: upload images serially
            val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            for (dir in directions) {
                val path = task.images[dir] ?: continue
                val file = File(path)
                if (!file.exists()) continue

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("point_id", task.pointId)
                    .addFormDataPart("direction", dir)
                    .addFormDataPart("image", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                    .build()

                val imgRequest = Request.Builder()
                    .url("$baseUrl/api/upload/upload_image")
                    .post(body)
                    .build()

                val imgResponse = client.newCall(imgRequest).execute()
                if (!imgResponse.isSuccessful) {
                    Log.e("UploadService", "Image $dir upload failed: ${imgResponse.code}")
                    return false
                }
                Thread.sleep(100)
            }

            true
        } catch (e: Exception) {
            Log.e("UploadService", "Upload failed", e)
            false
        }
    }
}
