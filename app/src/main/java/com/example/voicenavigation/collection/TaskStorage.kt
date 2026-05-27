package com.example.voicenavigation.collection

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TaskStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("capture_tasks", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "semantic_map_tasks"

    fun saveTask(task: CaptureTask) {
        val tasks = getAllTasks().toMutableList()
        tasks.add(task)
        prefs.edit().putString(key, gson.toJson(tasks)).apply()
    }

    fun getAllTasks(): List<CaptureTask> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CaptureTask>>() {}.type
            gson.fromJson(json, type)
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
                updatedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()),
                uploadedAt = if (status == "success") java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()) else tasks[index].uploadedAt
            )
            prefs.edit().putString(key, gson.toJson(tasks)).apply()
        }
    }

    fun clearSuccessTasks() {
        val remaining = getAllTasks().filter { it.status != "success" }
        prefs.edit().putString(key, gson.toJson(remaining)).apply()
    }

    fun clearAll() {
        prefs.edit().remove(key).apply()
    }
}
