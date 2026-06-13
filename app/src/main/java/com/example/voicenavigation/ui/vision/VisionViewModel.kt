package com.example.voicenavigation.ui.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corsight.inference.Detection
import com.corsight.vision.Frame
import com.corsight.vision.ToolRegistry
import com.corsight.vision.ToolResult
import com.example.voicenavigation.ImageQualityAnalyzer
import com.example.voicenavigation.ObstacleAlert
import com.example.voicenavigation.ObstacleAlertTracker
import com.example.voicenavigation.ObstacleRiskAnalyzer
import com.example.voicenavigation.ObstacleSpeechEvent
import com.example.voicenavigation.ObstacleUrgency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class DetectionMode {
    LOCAL,
    CLOUD
}

@HiltViewModel
class VisionViewModel @Inject constructor(
    private val obstacleAlertTracker: ObstacleAlertTracker,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    companion object {
        private const val TAG = "VisionViewModel"
        private const val LOCAL_FRAME_INTERVAL_MS = 120L
    }

    private val httpClient = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ==================== State ====================

    private val _detectionMode = MutableStateFlow(DetectionMode.LOCAL)
    val detectionMode: StateFlow<DetectionMode> = _detectionMode.asStateFlow()

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private val _alerts = MutableStateFlow<List<ObstacleAlert>>(emptyList())
    val alerts: StateFlow<List<ObstacleAlert>> = _alerts.asStateFlow()

    private val _displayText = MutableStateFlow("")
    val displayText: StateFlow<String> = _displayText.asStateFlow()

    private val _speechEvents = MutableSharedFlow<ObstacleSpeechEvent>(extraBufferCapacity = 5)
    val speechEvents: SharedFlow<ObstacleSpeechEvent> = _speechEvents.asSharedFlow()

    private val _cloudProgress = MutableStateFlow(false)
    val cloudProgress: StateFlow<Boolean> = _cloudProgress.asStateFlow()

    // ==================== Internal state ====================

    @Volatile private var cloudRequestRunning = false
    @Volatile private var localInferenceRunning = false
    @Volatile private var localToolReady = false
    private var lastInferenceAt = 0L
    private var lastFrameWidth = 1
    private var lastFrameHeight = 1
    private val smoothedHistory = ArrayDeque<List<Detection>>()

    // ==================== Public API ====================

    fun setDetectionMode(mode: DetectionMode) {
        _detectionMode.value = mode
    }

    fun ensureLocalToolReady(): Boolean {
        if (localToolReady) return true
        return try {
            // Note: ToolRegistry.activate needs Context, this should be called from Activity
            localToolReady = ToolRegistry.activeTool.value != null
            localToolReady
        } catch (e: Exception) {
            Log.e(TAG, "Local detection model check failed", e)
            _displayText.value = "本地检测模型加载失败：${e.message.orEmpty()}"
            false
        }
    }

    fun setLocalToolReady(ready: Boolean) {
        localToolReady = ready
    }

    fun processFrame(bitmap: Bitmap, rotationDegrees: Int, detectionServerUrl: String) {
        lastFrameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) bitmap.height else bitmap.width
        lastFrameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) bitmap.width else bitmap.height

        val now = SystemClock.elapsedRealtime()
        when (_detectionMode.value) {
            DetectionMode.LOCAL -> {
                if (localInferenceRunning || now - lastInferenceAt < LOCAL_FRAME_INTERVAL_MS) return
                lastInferenceAt = now
                processLocalFrame(bitmap, rotationDegrees)
            }
            DetectionMode.CLOUD -> processCloudFrame(bitmap, rotationDegrees, detectionServerUrl)
        }
    }

    fun resetTracking() {
        smoothedHistory.clear()
        obstacleAlertTracker.reset()
    }

    // ==================== Local Inference ====================

    private fun processLocalFrame(bitmap: Bitmap, rotationDegrees: Int) {
        localInferenceRunning = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val quality = ImageQualityAnalyzer.assess(bitmap)
                if (!quality.isClear) {
                    withContext(Dispatchers.Main) {
                        localInferenceRunning = false
                        renderBlurredFrame(quality)
                    }
                    return@launch
                }

                val result = ToolRegistry.activeTool.value?.process(Frame(bitmap, rotationDegrees))
                withContext(Dispatchers.Main) {
                    localInferenceRunning = false
                    renderResult(result, quality)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local detection failed", e)
                withContext(Dispatchers.Main) {
                    localInferenceRunning = false
                    _displayText.value = "本地检测失败：${e.message.orEmpty()}"
                    _detections.value = emptyList()
                    _alerts.value = emptyList()
                }
            }
        }
    }

    // ==================== Cloud Inference ====================

    private fun processCloudFrame(bitmap: Bitmap, rotationDegrees: Int, serverUrl: String) {
        if (cloudRequestRunning) return
        if (serverUrl.isEmpty()) return

        cloudRequestRunning = true
        _cloudProgress.value = true

        viewModelScope.launch(Dispatchers.Default) {
            val quality = ImageQualityAnalyzer.assess(bitmap)
            if (!quality.isClear) {
                cloudRequestRunning = false
                withContext(Dispatchers.Main) {
                    _cloudProgress.value = false
                    renderBlurredFrame(quality)
                }
                return@launch
            }
            sendCloudDetectionRequest(bitmap, rotationDegrees, quality, serverUrl)
        }
    }

    private fun sendCloudDetectionRequest(
        bitmap: Bitmap,
        rotationDegrees: Int,
        quality: ImageQualityAnalyzer.Result,
        serverUrl: String
    ) {
        val jpegBytes = bitmap.toJpegBytes()
        val imageBody = jpegBytes.toRequestBody("image/jpeg".toMediaType())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "frame.jpg", imageBody)
            .addFormDataPart("rotation", rotationDegrees.toString())
            .addFormDataPart("sharpness", quality.sharpness.toString())
            .build()
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/api/detect")
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cloudRequestRunning = false
                Log.e(TAG, "Cloud detection failed", e)
                viewModelScope.launch(Dispatchers.Main) {
                    _cloudProgress.value = false
                    _displayText.value = "云端检测失败：${e.message.orEmpty()}"
                    _detections.value = emptyList()
                    _alerts.value = emptyList()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                cloudRequestRunning = false
                val bodyText = response.body?.string().orEmpty()
                val parsedDetections = if (response.isSuccessful) parseCloudDetections(bodyText) else emptyList()
                viewModelScope.launch(Dispatchers.Main) {
                    _cloudProgress.value = false
                    if (response.isSuccessful) {
                        renderDetections(parsedDetections, quality)
                    } else {
                        _displayText.value = "云端检测失败：HTTP ${response.code}"
                        _detections.value = emptyList()
                        _alerts.value = emptyList()
                    }
                }
            }
        })
    }

    // ==================== Rendering ====================

    private fun renderBlurredFrame(quality: ImageQualityAnalyzer.Result) {
        smoothedHistory.clear()
        _displayText.value = "画面较模糊，等待清晰帧（清晰度 ${quality.sharpness.toInt()}）"
        _detections.value = emptyList()
        _alerts.value = emptyList()
    }

    private fun renderResult(result: ToolResult?, quality: ImageQualityAnalyzer.Result) {
        when (result) {
            is ToolResult.Detections -> renderDetections(result.items, quality)
            else -> renderDetections(emptyList(), quality)
        }
    }

    private fun renderDetections(items: List<Detection>, quality: ImageQualityAnalyzer.Result) {
        val stableItems = stabilizeDetections(items)
        val alertList = ObstacleRiskAnalyzer.analyze(stableItems, lastFrameWidth, lastFrameHeight)
        val speechEventList = obstacleAlertTracker.update(stableItems, alertList)

        _detections.value = stableItems
        _alerts.value = alertList

        for (event in speechEventList) {
            _speechEvents.tryEmit(event)
        }

        _displayText.value = when {
            stableItems.isEmpty() -> "未检测到明显障碍物（清晰度 ${quality.sharpness.toInt()}）"
            alertList.isEmpty() -> stableItems.take(5).joinToString("\n") {
                "${it.label}: ${(it.score * 100).toInt()}%，风险区外"
            }
            else -> alertList.sortedByDescending { it.urgency.ordinal }.take(5).joinToString("\n") {
                "${urgencyText(it.urgency)}：${it.detection.label}，重叠 ${(it.overlapRatio * 100).toInt()}%"
            }
        }
    }

    private fun urgencyText(urgency: ObstacleUrgency): String {
        return when (urgency) {
            ObstacleUrgency.LOW -> "低紧急度"
            ObstacleUrgency.MEDIUM -> "中等紧急"
            ObstacleUrgency.HIGH -> "重要告警"
        }
    }

    // ==================== Detection Stabilization ====================

    private fun stabilizeDetections(items: List<Detection>): List<Detection> {
        if (items.isEmpty()) {
            smoothedHistory.clear()
            return emptyList()
        }

        val historyFrames = smoothedHistory.toList()
        val stable = items.map { current ->
            val matched = historyFrames.asReversed()
                .flatMap { frame -> frame.filter { isSameTarget(current, it) } }
                .take(4)

            if (matched.isEmpty()) current else mergeDetections(matched + current)
        }

        smoothedHistory.addLast(stable)
        while (smoothedHistory.size > 5) {
            smoothedHistory.removeFirst()
        }
        return stable
    }

    private fun mergeDetections(items: List<Detection>): Detection {
        var left = 0f; var top = 0f; var right = 0f; var bottom = 0f; var score = 0f
        for (item in items) {
            left += item.box.left; top += item.box.top
            right += item.box.right; bottom += item.box.bottom; score += item.score
        }
        val count = items.size.coerceAtLeast(1)
        return items.first().copy(
            box = RectF(left / count, top / count, right / count, bottom / count),
            score = (score / count).coerceIn(0f, 1f)
        )
    }

    private fun isSameTarget(a: Detection, b: Detection): Boolean {
        return a.classId == b.classId && a.label == b.label && iou(a.box, b.box) >= 0.35f
    }

    private fun iou(box1: RectF, box2: RectF): Float {
        val x1 = maxOf(box1.left, box2.left)
        val y1 = maxOf(box1.top, box2.top)
        val x2 = minOf(box1.right, box2.right)
        val y2 = minOf(box1.bottom, box2.bottom)
        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val union = box1.width() * box1.height() + box2.width() * box2.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    // ==================== Cloud Response Parsing ====================

    private fun parseCloudDetections(json: String): List<Detection> {
        return try {
            val root = JSONObject(json)
            val array = when {
                root.has("detections") -> root.optJSONArray("detections")
                root.has("data") -> root.optJSONObject("data")?.optJSONArray("detections")
                root.has("items") -> root.optJSONArray("items")
                else -> JSONArray()
            } ?: JSONArray()

            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val score = item.optDouble("score", item.optDouble("confidence", 0.0)).toFloat()
                    val label = item.optString("label", item.optString("class_name", "unknown"))
                    val classId = item.optInt("class_id", item.optInt("classId", -1))
                    add(Detection(parseBox(item), score, classId, label))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse cloud detections failed", e)
            emptyList()
        }
    }

    private fun parseBox(item: JSONObject): RectF {
        val boxArray = item.optJSONArray("box") ?: item.optJSONArray("bbox")
        if (boxArray != null && boxArray.length() >= 4) {
            return normalizeBox(
                boxArray.optDouble(0).toFloat(), boxArray.optDouble(1).toFloat(),
                boxArray.optDouble(2).toFloat(), boxArray.optDouble(3).toFloat()
            )
        }
        val x1 = item.optDouble("x1", item.optDouble("left", 0.0)).toFloat()
        val y1 = item.optDouble("y1", item.optDouble("top", 0.0)).toFloat()
        val x2 = item.optDouble("x2", item.optDouble("right", 0.0)).toFloat()
        val y2 = item.optDouble("y2", item.optDouble("bottom", 0.0)).toFloat()
        val width = item.optDouble("width", 0.0).toFloat()
        val height = item.optDouble("height", 0.0).toFloat()
        if (width > 0f && height > 0f && x2 <= 1.5f && y2 <= 1.5f) {
            return normalizeBox(x1, y1, x1 + width, y1 + height)
        }
        return normalizeBox(x1, y1, x2, y2)
    }

    private fun normalizeBox(x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        return if (x2 <= 1.5f && y2 <= 1.5f) {
            RectF(x1 * lastFrameWidth, y1 * lastFrameHeight, x2 * lastFrameWidth, y2 * lastFrameHeight)
        } else {
            RectF(x1, y1, x2, y2)
        }
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 80, output)
        return output.toByteArray()
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.dispatcher.cancelAll()
    }
}
