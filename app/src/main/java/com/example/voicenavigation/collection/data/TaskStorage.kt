package com.example.voicenavigation.collection.data

import android.content.Context
import android.content.SharedPreferences
import com.example.voicenavigation.core.camera.Crs
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 采样任务持久化存储（SharedPreferences + JSON）。
 *
 * 使用 v2 存储文件名以隔离旧数据。
 * JSON 字段命名对齐后端 api_contract.md。
 */
@Singleton
class TaskStorage @Inject constructor(
    @ApplicationContext context: Context
) {

    companion object {
        private const val PREFS_NAME = "capture_tasks_v2"
        private const val KEY = "semantic_map_tasks"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ===== 任务级操作 =====

    fun saveTask(task: CaptureTask) {
        val tasks = getAllTasks().toMutableList()
        // 同 pointId 的旧任务先移除（更新逻辑）
        tasks.removeAll { it.pointId == task.pointId }
        tasks.add(task)
        prefs.edit().putString(KEY, tasksToJson(tasks)).apply()
    }

    fun getTask(pointId: String): CaptureTask? {
        return getAllTasks().find { it.pointId == pointId }
    }

    fun getAllTasks(): List<CaptureTask> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            tasksFromJson(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getPendingTasks(): List<CaptureTask> {
        return getAllTasks().filter {
            it.status == TaskStatus.PENDING || it.status == TaskStatus.FAILED
        }
    }

    fun updateTaskStatus(pointId: String, status: TaskStatus) {
        val tasks = getAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.pointId == pointId }
        if (index != -1) {
            val now = System.currentTimeMillis()
            tasks[index] = tasks[index].copy(
                status = status,
                updatedAt = now
            )
            prefs.edit().putString(KEY, tasksToJson(tasks)).apply()
        }
    }

    fun updateUploadSessionId(pointId: String, sessionId: String) {
        val tasks = getAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.pointId == pointId }
        if (index != -1) {
            tasks[index] = tasks[index].copy(
                uploadSessionId = sessionId,
                updatedAt = System.currentTimeMillis()
            )
            prefs.edit().putString(KEY, tasksToJson(tasks)).apply()
        }
    }

    fun deleteTask(pointId: String) {
        val tasks = getAllTasks().toMutableList()
        tasks.removeAll { it.pointId == pointId }
        prefs.edit().putString(KEY, tasksToJson(tasks)).apply()
    }

    fun clearSuccessTasks() {
        val remaining = getAllTasks().filter { it.status != TaskStatus.SUCCESS }
        prefs.edit().putString(KEY, tasksToJson(remaining)).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY).apply()
    }

    // ===== 照片级操作 =====

    fun updatePhotoUploadStatus(
        pointId: String,
        photoId: String,
        status: UploadStatus,
        remotePath: String? = null
    ) {
        val tasks = getAllTasks().toMutableList()
        val tIdx = tasks.indexOfFirst { it.pointId == pointId }
        if (tIdx == -1) return

        val task = tasks[tIdx]
        val pIdx = task.photos.indexOfFirst { it.id == photoId }
        if (pIdx == -1) return

        task.photos[pIdx] = task.photos[pIdx].copy(
            uploadStatus = status,
            remotePath = remotePath ?: task.photos[pIdx].remotePath
        )

        // 自动更新任务状态
        val now = System.currentTimeMillis()
        tasks[tIdx] = when {
            task.photos.all { it.uploadStatus == UploadStatus.UPLOADED } ->
                task.copy(status = TaskStatus.SUCCESS, updatedAt = now)
            task.photos.any { it.uploadStatus == UploadStatus.FAILED } ->
                task.copy(status = TaskStatus.FAILED, updatedAt = now)
            else -> task.copy(updatedAt = now)
        }

        prefs.edit().putString(KEY, tasksToJson(tasks)).apply()
    }

    /**
     * 替换照片（补拍场景）。删除原文件由调用方负责。
     */
    fun replacePhoto(pointId: String, oldPhotoId: String, newPhoto: PhotoRecord) {
        val tasks = getAllTasks().toMutableList()
        val tIdx = tasks.indexOfFirst { it.pointId == pointId }
        if (tIdx == -1) return

        val task = tasks[tIdx]
        val pIdx = task.photos.indexOfFirst { it.id == oldPhotoId }
        if (pIdx == -1) return

        task.photos[pIdx] = newPhoto.copy(uploadStatus = UploadStatus.PENDING, remotePath = null)
        tasks[tIdx] = task.copy(updatedAt = System.currentTimeMillis())
        prefs.edit().putString(KEY, tasksToJson(tasks)).apply()
    }

    // ===== JSON 序列化 =====

    private fun tasksToJson(tasks: List<CaptureTask>): String {
        val arr = JSONArray()
        tasks.forEach { task ->
            arr.put(JSONObject().apply {
                put("point_id", task.pointId)
                put("latitude", task.latitude)
                put("longitude", task.longitude)
                put("crs", task.crs.name)
                put("scene_description", task.sceneDescription)
                put("mode", task.mode.name)
                put("upload_session_id", task.uploadSessionId ?: JSONObject.NULL)
                put("status", task.status.name)
                put("created_at", task.createdAt)
                put("updated_at", task.updatedAt)
                put("photos", JSONArray().apply {
                    task.photos.forEach { photo ->
                        put(JSONObject().apply {
                            put("id", photo.id)
                            put("file_path", photo.filePath)
                            put("bearing", photo.bearing.toDouble())
                            put("fov", photo.fov.toDouble())
                            put("description", photo.description)
                            put("label", photo.label)
                            put("direction", photo.direction ?: JSONObject.NULL)
                            put("upload_status", photo.uploadStatus.name)
                            put("remote_path", photo.remotePath ?: JSONObject.NULL)
                        })
                    }
                })
            })
        }
        return arr.toString()
    }

    private fun tasksFromJson(json: String): List<CaptureTask> {
        val arr = JSONArray(json)
        val result = mutableListOf<CaptureTask>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val photosArr = obj.optJSONArray("photos") ?: JSONArray()
            val photos = mutableListOf<PhotoRecord>()
            for (j in 0 until photosArr.length()) {
                val p = photosArr.getJSONObject(j)
                photos.add(PhotoRecord(
                    id = p.getString("id"),
                    filePath = p.getString("file_path"),
                    bearing = p.getDouble("bearing").toFloat(),
                    fov = p.optDouble("fov", 90.0).toFloat(),
                    description = p.optString("description", ""),
                    label = p.optString("label", ""),
                    direction = if (p.isNull("direction")) null else p.getString("direction"),
                    uploadStatus = try {
                        UploadStatus.valueOf(p.optString("upload_status", "PENDING"))
                    } catch (_: Exception) {
                        UploadStatus.PENDING
                    },
                    remotePath = if (p.isNull("remote_path")) null else p.getString("remote_path")
                ))
            }
            result.add(CaptureTask(
                pointId = obj.getString("point_id"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                crs = try {
                    Crs.valueOf(obj.optString("crs", "GCJ02"))
                } catch (_: Exception) {
                    Crs.GCJ02
                },
                sceneDescription = obj.getString("scene_description"),
                mode = try {
                    CaptureMode.valueOf(obj.optString("mode", "FREE"))
                } catch (_: Exception) {
                    CaptureMode.FREE
                },
                photos = photos,
                uploadSessionId = if (obj.isNull("upload_session_id")) null
                    else obj.getString("upload_session_id"),
                status = try {
                    TaskStatus.valueOf(obj.optString("status", "PENDING"))
                } catch (_: Exception) {
                    TaskStatus.PENDING
                },
                createdAt = obj.optLong("created_at", System.currentTimeMillis()),
                updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
            ))
        }
        return result
    }
}
