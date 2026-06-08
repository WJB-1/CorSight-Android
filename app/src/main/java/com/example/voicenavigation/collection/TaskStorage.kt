package com.example.voicenavigation.collection

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class TaskStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("capture_tasks", Context.MODE_PRIVATE)
    private val key = "semantic_map_tasks"

    fun saveTask(task: CaptureTask) {
        val tasks = getAllTasks().toMutableList()
        tasks.add(task)
        prefs.edit().putString(key, tasksToJson(tasks)).apply()
    }

    fun getAllTasks(): List<CaptureTask> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            tasksFromJson(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getPendingTasks(): List<CaptureTask> {
        return getAllTasks().filter { it.status == "pending" || it.status == "failed" }
    }

    fun updateStatus(pointId: String, status: String) {
        val tasks = getAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.pointId == pointId }
        if (index != -1) {
            tasks[index] = tasks[index].copy(
                status = status,
                updatedAt = formatDate(),
                uploadedAt = if (status == "success") formatDate() else tasks[index].uploadedAt
            )
            prefs.edit().putString(key, tasksToJson(tasks)).apply()
        }
    }

    fun clearSuccessTasks() {
        val remaining = getAllTasks().filter { it.status != "success" }
        prefs.edit().putString(key, tasksToJson(remaining)).apply()
    }

    fun clearAll() {
        prefs.edit().remove(key).apply()
    }

    // === JSON serialization ===

    private fun tasksToJson(tasks: List<CaptureTask>): String {
        val arr = JSONArray()
        tasks.forEach { task ->
            val obj = JSONObject().apply {
                put("point_id", task.pointId)
                put("chunk_id", task.chunkId)
                put("latitude", task.latitude)
                put("longitude", task.longitude)
                put("scene_description", task.sceneDescription)
                put("status", task.status)
                put("createdAt", task.createdAt)
                put("updatedAt", task.updatedAt)
                put("uploadedAt", task.uploadedAt ?: JSONObject.NULL)
                val imgObj = JSONObject()
                task.images.forEach { (k, v) -> imgObj.put(k, v) }
                put("images", imgObj)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun tasksFromJson(json: String): List<CaptureTask> {
        val arr = JSONArray(json)
        val result = mutableListOf<CaptureTask>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val imgObj = obj.optJSONObject("images") ?: JSONObject()
            val images = mutableMapOf<String, String>()
            imgObj.keys().forEach { key ->
                images[key] = imgObj.getString(key)
            }
            result.add(CaptureTask(
                pointId = obj.getString("point_id"),
                chunkId = obj.getString("chunk_id"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                sceneDescription = obj.getString("scene_description"),
                images = images,
                status = obj.getString("status"),
                createdAt = obj.getString("createdAt"),
                updatedAt = obj.getString("updatedAt"),
                uploadedAt = if (obj.isNull("uploadedAt")) null else obj.getString("uploadedAt")
            ))
        }
        return result
    }

    private fun formatDate(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date())
    }
}
