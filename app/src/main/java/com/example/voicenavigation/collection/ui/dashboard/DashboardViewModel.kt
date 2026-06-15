package com.example.voicenavigation.collection.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenavigation.AppConfig
import com.example.voicenavigation.collection.data.CaptureTask
import com.example.voicenavigation.collection.data.PhotoRecord
import com.example.voicenavigation.collection.data.TaskStatus
import com.example.voicenavigation.collection.data.TaskStorage
import com.example.voicenavigation.collection.data.UploadStatus
import com.example.voicenavigation.collection.service.UploadService
import com.example.voicenavigation.core.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@HiltViewModel
class DashboardViewModel @Inject constructor(
    val taskStorage: TaskStorage,
    private val locationProvider: LocationProvider,
    @com.example.voicenavigation.di.BaseUrl private val baseUrl: String
) : ViewModel() {

    companion object {
        const val RETAKE_MAX_DISTANCE_M = 50.0     // 补拍允许最大偏移距离（米）
        const val RETAKE_LOCATION_TIMEOUT_MS = 5000L
        const val RETAKE_LOCATION_ACCURACY_M = 10f  // 要求 GPS 水平精度 < 10m
    }

    private val _tasks = MutableStateFlow<List<CaptureTask>>(emptyList())
    val tasks: StateFlow<List<CaptureTask>> = _tasks

    private val _photoProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val photoProgress: StateFlow<Map<String, Int>> = _photoProgress

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    init {
        refreshTasks()
    }

    fun refreshTasks() {
        _tasks.value = taskStorage.getAllTasks()
    }

    // ===== 上传 =====

    fun uploadTask(task: CaptureTask) {
        viewModelScope.launch(Dispatchers.IO) {
            _isUploading.value = true
            val service = UploadService(baseUrl, taskStorage)
            service.uploadTask(
                task = task,
                onPhotoProgress = { photoId, pct ->
                    _photoProgress.value = _photoProgress.value.toMutableMap().apply {
                        put(photoId, pct)
                    }
                },
                onPhotoResult = { _, _ -> }
            )
            _isUploading.value = false
            refreshTasks()
        }
    }

    fun retryPhoto(taskId: String, photoId: String) {
        val task = taskStorage.getTask(taskId) ?: return
        val photo = task.photos.find { it.id == photoId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val service = UploadService(baseUrl, taskStorage)
            service.uploadSinglePhoto(task, photo) { pct ->
                _photoProgress.value = _photoProgress.value.toMutableMap().apply {
                    put(photoId, pct)
                }
            }
            refreshTasks()
        }
    }

    fun uploadAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _isUploading.value = true
            val pending = taskStorage.getPendingTasks()
            val service = UploadService(baseUrl, taskStorage)
            pending.forEach { task ->
                service.uploadTask(
                    task = task,
                    onPhotoProgress = { photoId, pct ->
                        _photoProgress.value = _photoProgress.value.toMutableMap().apply {
                            put(photoId, pct)
                        }
                    }
                )
            }
            _isUploading.value = false
            refreshTasks()
        }
    }

    /**
     * 串行上传选中的照片（逐张，适合弱网环境）。
     * @param selectedPhotos (taskId, photoId) 列表
     */
    fun uploadSelectedPhotos(selectedPhotos: List<Pair<String, String>>) {
        viewModelScope.launch(Dispatchers.IO) {
            _isUploading.value = true
            val service = UploadService(baseUrl, taskStorage)

            for ((taskId, photoId) in selectedPhotos) {
                val task = taskStorage.getTask(taskId) ?: continue
                val photo = task.photos.find { it.id == photoId } ?: continue
                if (photo.uploadStatus == UploadStatus.UPLOADED) continue

                service.uploadSinglePhoto(task, photo) { pct ->
                    _photoProgress.value = _photoProgress.value.toMutableMap().apply {
                        put(photoId, pct)
                    }
                }
                refreshTasks()
            }

            _isUploading.value = false
        }
    }

    /**
     * 删除单张照片。
     */
    fun deletePhoto(taskId: String, photoId: String) {
        val task = taskStorage.getTask(taskId) ?: return
        val photo = task.photos.find { it.id == photoId }
        photo?.filePath?.let { File(it).delete() }
        task.photos.removeAll { it.id == photoId }
        taskStorage.saveTask(task)
        refreshTasks()
    }

    // ===== 定位锁 =====

    /**
     * 检查是否允许补拍。
     * @return null 表示允许；否则返回错误消息。
     */
    suspend fun checkRetakeEligibility(taskId: String): String? {
        val task = taskStorage.getTask(taskId) ?: return "任务不存在"
        val current = locationProvider.getLastLocation(RETAKE_LOCATION_TIMEOUT_MS)
            ?: return "无法获取当前位置，请检查 GPS 信号"

        if (current.accuracy > RETAKE_LOCATION_ACCURACY_M) {
            return "GPS 精度不足（${current.accuracy.toInt()}m），请等待信号稳定"
        }

        val distance = haversine(task.latitude, task.longitude, current.latitude, current.longitude)
        return if (distance > RETAKE_MAX_DISTANCE_M) {
            "当前位置距采集点 ${distance.toInt()}m，超出允许范围（${RETAKE_MAX_DISTANCE_M.toInt()}m），请返回采集点附近"
        } else {
            null
        }
    }

    // ===== 补拍 =====

    fun replacePhoto(taskId: String, oldPhotoId: String, newPhoto: PhotoRecord) {
        // 删除旧文件
        val task = taskStorage.getTask(taskId)
        val oldPhoto = task?.photos?.find { it.id == oldPhotoId }
        oldPhoto?.filePath?.let { File(it).delete() }

        taskStorage.replacePhoto(taskId, oldPhotoId, newPhoto)
        refreshTasks()
    }

    // ===== 删除 =====

    fun deleteTask(taskId: String) {
        val task = taskStorage.getTask(taskId)
        // 清理本地图片文件
        task?.photos?.forEach { File(it.filePath).delete() }
        taskStorage.deleteTask(taskId)
        refreshTasks()
    }

    fun clearSuccessTasks() {
        // 清理已完成任务的图片
        taskStorage.getAllTasks()
            .filter { it.status == TaskStatus.SUCCESS }
            .forEach { task -> task.photos.forEach { File(it.filePath).delete() } }
        taskStorage.clearSuccessTasks()
        refreshTasks()
    }

    // ===== 工具 =====

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
