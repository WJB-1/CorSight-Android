package com.example.voicenavigation.collection.ui.freemode

import androidx.lifecycle.ViewModel
import com.example.voicenavigation.collection.data.CaptureMode
import com.example.voicenavigation.collection.data.CaptureTask
import com.example.voicenavigation.collection.data.PhotoRecord
import com.example.voicenavigation.collection.data.TaskStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Random
import javax.inject.Inject

@HiltViewModel
class FreeCaptureViewModel @Inject constructor(
    private val taskStorage: TaskStorage
) : ViewModel() {

    private val _photos = MutableStateFlow<List<PhotoRecord>>(emptyList())
    val photos: StateFlow<List<PhotoRecord>> = _photos

    fun addPhoto(photo: PhotoRecord) {
        _photos.value = _photos.value + photo
    }

    fun removePhoto(photoId: String) {
        _photos.value = _photos.value.filter { it.id != photoId }
    }

    val photoCount: Int get() = _photos.value.size

    fun clearPhotos() {
        _photos.value = emptyList()
    }

    /**
     * 将当前照片打包为 CaptureTask 并保存到 TaskStorage。
     */
    fun saveTask(latitude: Double, longitude: Double, sceneDescription: String): CaptureTask {
        val pointId = generatePointId()
        val task = CaptureTask(
            pointId = pointId,
            latitude = latitude,
            longitude = longitude,
            sceneDescription = sceneDescription,
            mode = CaptureMode.FREE,
            photos = _photos.value.toMutableList()
        )
        taskStorage.saveTask(task)
        _photos.value = emptyList()
        return task
    }

    private fun generatePointId(): String {
        val ts = System.currentTimeMillis()
        val rand = Random().nextInt(90000) + 10000
        return "P_${ts}_${rand}"
    }
}
