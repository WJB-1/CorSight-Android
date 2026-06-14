package com.example.voicenavigation.collection.ui.gridmode

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
class GridCaptureViewModel @Inject constructor(
    private val taskStorage: TaskStorage
) : ViewModel() {

    companion object {
        val DIRECTIONS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val DIRECTION_ANGLES = mapOf(
            "N" to 0f, "NE" to 45f, "E" to 90f, "SE" to 135f,
            "S" to 180f, "SW" to 225f, "W" to 270f, "NW" to 315f
        )
    }

    private val _photos = MutableStateFlow<List<PhotoRecord>>(emptyList())
    val photos: StateFlow<List<PhotoRecord>> = _photos

    private val _capturedDirections = MutableStateFlow<Set<String>>(emptySet())
    val capturedDirections: StateFlow<Set<String>> = _capturedDirections

    private val _currentTarget = MutableStateFlow<String?>(null)
    val currentTarget: StateFlow<String?> = _currentTarget

    init {
        pickNextTarget()
    }

    fun addPhoto(photo: PhotoRecord) {
        val dir = photo.direction ?: return
        _photos.value = _photos.value + photo
        _capturedDirections.value = _capturedDirections.value + dir
        pickNextTarget()
    }

    val isComplete: Boolean get() = _capturedDirections.value.size >= DIRECTIONS.size
    val photoCount: Int get() = _photos.value.size

    fun pickNextTarget() {
        _currentTarget.value = DIRECTIONS.firstOrNull { it !in _capturedDirections.value }
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
            mode = CaptureMode.GRID,
            photos = _photos.value.toMutableList()
        )
        taskStorage.saveTask(task)
        reset()
        return task
    }

    private fun reset() {
        _photos.value = emptyList()
        _capturedDirections.value = emptySet()
        pickNextTarget()
    }

    private fun generatePointId(): String {
        val ts = System.currentTimeMillis()
        val rand = Random().nextInt(90000) + 10000
        return "P_${ts}_${rand}"
    }
}
