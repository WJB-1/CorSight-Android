package com.example.voicenavigation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.corsight.inference.Detection
import com.corsight.inference.ModelRegistry
import com.corsight.vision.Frame
import com.corsight.vision.ImageSource
import com.corsight.vision.ToolRegistry
import com.corsight.vision.ToolResult
import com.corsight.vision.tools.GenericDetectionTool
import com.example.voicenavigation.databinding.ActivityVisionTestBinding
import com.example.voicenavigation.stt.BaiduTtsManager
import kotlinx.coroutines.*
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VisionTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisionTestBinding
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var destroyed = false
    @Volatile private var cloudRequestRunning = false
    @Volatile private var localInferenceRunning = false
    @Volatile private var localToolReady = false
    private var currentSource: ImageSource? = null
    private var activeMode = DetectionMode.LOCAL
    private var lastFrameWidth = 1
    private var lastFrameHeight = 1
    private var lastInferenceAt = 0L
    private var baiduTts: BaiduTtsManager? = null
    @Volatile private var ttsReady = false
    private val pendingSpeechMessages = LinkedList<String>()
    private val obstacleAlertTracker = ObstacleAlertTracker()
    private val smoothedHistory = ArrayDeque<List<Detection>>()
    private val cameraSource by lazy {
        CameraSource(this, this, binding.previewView, cameraExecutor)
    }

    // UDP 自动发现相关
    private var udpSocket: DatagramSocket? = null
    private var udpReceiveThread: Thread? = null
    private val UDP_DISCOVERY_PORT = 8888
    private val AUTO_DISCOVERY_TIMEOUT_MS = 5000L

    companion object {
        private const val TAG = "VisionTest"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val LOCAL_FRAME_INTERVAL_MS = 120L
        private const val MODEL_INPUT_SIZE = 640
        private const val DEFAULT_STREAM_PORT = 8080
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private enum class DetectionMode {
        LOCAL,
        CLOUD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ToolRegistry.register(GenericDetectionTool())
        initTts()
        setupUI()

        val useExternal = getSharedPreferences("corsight_config", MODE_PRIVATE)
            .getBoolean("use_external_device", false)

        if (useExternal) {
            binding.tvDetections.text = "正在寻找外设..."
            startUdpAutoDiscovery(onFound = { ip ->
                connectToNetworkSource(ip, DEFAULT_STREAM_PORT)
            }, onTimeout = {
                Toast.makeText(this, "未找到外设，退回本机相机", Toast.LENGTH_SHORT).show()
                startCameraOrRequestPermission()
            })
        } else {
            startCameraOrRequestPermission()
        }
    }

    private fun initTts() {
        baiduTts = BaiduTtsManager(
            this,
            getString(R.string.baidu_speech_api_key),
            getString(R.string.baidu_speech_secret_key)
        ).apply {
            setCallback(object : BaiduTtsManager.TtsCallback {
                override fun onTtsReady() {
                    ttsReady = true
                    flushPendingSpeechMessages()
                    Log.d(TAG, "Obstacle TTS ready")
                }
                override fun onTtsError(error: String) {
                    Log.e(TAG, "Obstacle TTS error: $error")
                }
            })
            init()
        }
    }

    private fun setupUI() {
        binding.btnSourceCamera.setOnClickListener {
            activeMode = DetectionMode.LOCAL
            binding.layoutNetworkConfig.visibility = View.GONE
            if (cameraSource.allPermissionsGranted()) {
                switchToSource(cameraSource)
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        }

        binding.btnSourceNetwork.setOnClickListener {
            val serverUrl = getDetectionServerUrl()
            binding.layoutNetworkConfig.visibility = View.VISIBLE
            binding.tvDetectionServer.text = if (serverUrl.isEmpty()) {
                "检测服务地址未配置，请在设置中填写"
            } else {
                "检测服务：$serverUrl"
            }
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请先在设置中保存检测服务地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activeMode = DetectionMode.CLOUD
            if (cameraSource.allPermissionsGranted()) {
                switchToSource(cameraSource)
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        }

        binding.btnSourceNetwork.setOnLongClickListener {
            val text = binding.etStreamIp.text.toString().trim()
            val parts = text.split(":")
            val ip = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: DEFAULT_STREAM_PORT else DEFAULT_STREAM_PORT

            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            connectToNetworkSource(ip, port)
            true
        }

        val savedUrl = getDetectionServerUrl()
        binding.tvDetectionServer.text = if (savedUrl.isEmpty()) {
            "检测服务地址未配置"
        } else {
            "检测服务：$savedUrl"
        }
    }

    private fun startCameraOrRequestPermission() {
        if (cameraSource.allPermissionsGranted()) {
            switchToSource(cameraSource)
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    // ==================== UDP 自动发现 ====================

    private fun startUdpAutoDiscovery(
        onFound: ((String) -> Unit)? = null,
        onTimeout: (() -> Unit)? = null
    ) {
        if (udpReceiveThread != null && udpReceiveThread!!.isAlive) {
            Toast.makeText(this, "正在自动发现中，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressConnecting.visibility = View.VISIBLE
        binding.tvDetections.text = "等待设备广播..."

        udpReceiveThread = Thread {
            try {
                udpSocket = DatagramSocket(UDP_DISCOVERY_PORT).apply {
                    soTimeout = 1000
                }
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)

                val startTime = System.currentTimeMillis()
                var found = false

                while (!found && System.currentTimeMillis() - startTime < AUTO_DISCOVERY_TIMEOUT_MS) {
                    try {
                        udpSocket?.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        Log.d(TAG, "UDP 收到广播: $msg")

                        val ip = extractIpFromMessage(msg)
                        if (ip != null) {
                            runOnUiThread {
                                binding.progressConnecting.visibility = View.GONE
                                binding.tvDetections.text = "发现设备: $ip"
                                binding.etStreamIp.setText(ip)
                                if (onFound != null) {
                                    onFound(ip)
                                } else {
                                    connectToNetworkSource(ip, DEFAULT_STREAM_PORT)
                                }
                            }
                            found = true
                        }
                    } catch (e: SocketTimeoutException) {
                        // 超时继续下一次循环
                    }
                }

                if (!found) {
                    runOnUiThread {
                        binding.progressConnecting.visibility = View.GONE
                        binding.tvDetections.text = "自动发现超时"
                        if (onTimeout != null) {
                            onTimeout()
                        } else {
                            Toast.makeText(this@VisionTestActivity,
                                "未收到设备广播，请确保 ESP32 已连接热点并正在发送广播", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP 接收错误", e)
                runOnUiThread {
                    binding.progressConnecting.visibility = View.GONE
                    binding.tvDetections.text = "自动发现失败"
                    if (onTimeout != null) {
                        onTimeout()
                    } else {
                        Toast.makeText(this@VisionTestActivity, "UDP 监听失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                closeUdpSocket()
                udpReceiveThread = null
            }
        }
        udpReceiveThread?.start()
    }

    private fun extractIpFromMessage(message: String): String? {
        val ipPattern = Regex("""IP=(\d+\.\d+\.\d+\.\d+)""")
        val match = ipPattern.find(message)
        return match?.groupValues?.get(1)
    }

    private fun closeUdpSocket() {
        try {
            udpSocket?.close()
            udpSocket = null
        } catch (e: Exception) { }
    }

    // ==================== 网络流连接 ====================

    private fun connectToNetworkSource(ip: String, port: Int) {
        val newSource = NetworkSource(ip, port)
        switchToSource(newSource)
        binding.layoutNetworkConfig.visibility = View.GONE
    }

    // ==================== 源切换 ====================

    private fun switchToSource(source: ImageSource) {
        currentSource?.stop()
        currentSource = null

        binding.tvPreviewHint.visibility = View.GONE
        binding.previewView.visibility = if (source is CameraSource) View.VISIBLE else View.GONE
        binding.ivNetwork.visibility = if (source !is CameraSource) View.VISIBLE else View.GONE
        smoothedHistory.clear()
        obstacleAlertTracker.reset()

        binding.btnSourceCamera.setBackgroundColor(
            ContextCompat.getColor(this,
                if (activeMode == DetectionMode.LOCAL) R.color.purple_700 else R.color.gray))
        binding.btnSourceNetwork.setBackgroundColor(
            ContextCompat.getColor(this,
                if (activeMode == DetectionMode.CLOUD) R.color.purple_700 else R.color.gray))

        if (activeMode == DetectionMode.LOCAL && !ensureLocalToolReady()) {
            return
        }

        val ok = source.start { bitmap, rotation -> processFrame(bitmap, rotation) }
        if (ok) {
            currentSource = source
            binding.tvDetections.text = if (activeMode == DetectionMode.LOCAL) {
                "本地检测已启动"
            } else {
                "云端检测已启动"
            }
        } else {
            Toast.makeText(this, "启动 ${source.displayName} 失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureLocalToolReady(): Boolean {
        if (localToolReady) return true
        return try {
            ToolRegistry.activate(this, "generic_detection")
            localToolReady = ToolRegistry.activeTool.value != null
            if (!localToolReady) {
                binding.tvDetections.text = "本地检测模型未就绪"
            }
            localToolReady
        } catch (e: Exception) {
            Log.e(TAG, "Local detection model activation failed", e)
            binding.tvDetections.text = "本地检测模型加载失败：${e.message.orEmpty()}"
            Toast.makeText(this, "本地检测模型加载失败", Toast.LENGTH_LONG).show()
            false
        }
    }

    // ==================== 帧处理 ====================

    private fun processFrame(bitmap: Bitmap, rotationDegrees: Int) {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        lastFrameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) bitmap.height else bitmap.width
        lastFrameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) bitmap.width else bitmap.height
        runOnUiThread {
            binding.overlayView.setSourceImageSize(lastFrameWidth, lastFrameHeight)
        }

        when (activeMode) {
            DetectionMode.LOCAL -> {
                if (localInferenceRunning || now - lastInferenceAt < LOCAL_FRAME_INTERVAL_MS) return
                lastInferenceAt = now
                processLocalFrame(bitmap, rotationDegrees)
            }
            DetectionMode.CLOUD -> processCloudFrame(bitmap, rotationDegrees)
        }
    }

    private fun processLocalFrame(bitmap: Bitmap, rotationDegrees: Int) {
        localInferenceRunning = true
        inferenceExecutor.execute {
            try {
                val quality = ImageQualityAnalyzer.assess(bitmap)
                if (!quality.isClear) {
                    runOnUiThread {
                        localInferenceRunning = false
                        renderBlurredFrame(quality)
                    }
                    return@execute
                }

                val result = ToolRegistry.activeTool.value?.process(Frame(bitmap, rotationDegrees))
                runOnUiThread {
                    localInferenceRunning = false
                    renderResult(result, quality)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local detection failed", e)
                runOnUiThread {
                    localInferenceRunning = false
                    binding.tvDetections.text = "本地检测失败：${e.message.orEmpty()}"
                    binding.overlayView.updateDetections(emptyList())
                    ObstacleWarningNotifier.dispatch(emptyList())
                }
            }
        }
    }

    private fun processCloudFrame(bitmap: Bitmap, rotationDegrees: Int) {
        if (cloudRequestRunning) return
        val serverUrl = getDetectionServerUrl()
        if (serverUrl.isEmpty()) return

        cloudRequestRunning = true
        runOnUiThread { binding.progressConnecting.visibility = View.VISIBLE }

        inferenceExecutor.execute {
            val quality = ImageQualityAnalyzer.assess(bitmap)
            if (!quality.isClear) {
                cloudRequestRunning = false
                runOnUiThread {
                    binding.progressConnecting.visibility = View.GONE
                    renderBlurredFrame(quality)
                }
                return@execute
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
        val uploadBitmap = bitmap.rotateForDisplay(rotationDegrees)
        val jpegBytes = uploadBitmap.toJpegBytes()
        if (uploadBitmap !== bitmap && !uploadBitmap.isRecycled) {
            uploadBitmap.recycle()
        }
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
                runOnUiThread {
                    binding.progressConnecting.visibility = View.GONE
                    binding.tvDetections.text = "云端检测失败：${e.message.orEmpty()}"
                    binding.overlayView.updateDetections(emptyList())
                    ObstacleWarningNotifier.dispatch(emptyList())
                }
            }

            override fun onResponse(call: Call, response: Response) {
                cloudRequestRunning = false
                val bodyText = response.body?.string().orEmpty()
                val detections = if (response.isSuccessful) parseCloudDetections(bodyText) else emptyList()
                runOnUiThread {
                    binding.progressConnecting.visibility = View.GONE
                    if (response.isSuccessful) {
                        renderDetections(detections, quality)
                    } else {
                        binding.tvDetections.text = "云端检测失败：HTTP ${response.code}"
                        binding.overlayView.updateDetections(emptyList())
                        ObstacleWarningNotifier.dispatch(emptyList())
                    }
                }
            }
        })
    }

    private fun renderBlurredFrame(quality: ImageQualityAnalyzer.Result) {
        smoothedHistory.clear()
        binding.tvDetections.text = "画面较模糊，等待清晰帧（清晰度 ${quality.sharpness.toInt()}）"
        binding.overlayView.updateDetections(emptyList())
        ObstacleWarningNotifier.dispatch(emptyList())
    }

    private fun renderResult(result: ToolResult?, quality: ImageQualityAnalyzer.Result) {
        when (result) {
            is ToolResult.Detections -> renderDetections(result.items, quality)
            else -> renderDetections(emptyList(), quality)
        }
    }

    private fun renderDetections(items: List<Detection>, quality: ImageQualityAnalyzer.Result) {
        val stableItems = stabilizeDetections(items)
        val alerts = ObstacleRiskAnalyzer.analyze(stableItems, lastFrameWidth, lastFrameHeight)
        val speechEvents = obstacleAlertTracker.update(stableItems, alerts)
        ObstacleWarningNotifier.dispatch(alerts)
        dispatchSpeechEvents(speechEvents)

        binding.tvDetections.text = when {
            stableItems.isEmpty() -> "未检测到明显障碍物（清晰度 ${quality.sharpness.toInt()}）"
            alerts.isEmpty() -> stableItems.take(5).joinToString("\n") {
                "${it.label}: ${(it.score * 100).toInt()}%，风险区外"
            }
            else -> alerts.sortedByDescending { it.urgency.ordinal }.take(5).joinToString("\n") {
                "${urgencyText(it.urgency)}：${it.detection.label}，重叠 ${(it.overlapRatio * 100).toInt()}%"
            }
        }

        binding.overlayView.updateDetections(stableItems, alerts)
    }

    private fun dispatchSpeechEvents(events: List<ObstacleSpeechEvent>) {
        for (event in events) {
            ObstacleWarningNotifier.dispatchSpeech(event)
            speakObstacleMessage(event.message)
        }
    }

    private fun speakObstacleMessage(message: String) {
        val tts = baiduTts
        if (ttsReady && tts != null) {
            tts.speak(message)
        } else {
            pendingSpeechMessages.add(message)
        }
    }

    private fun flushPendingSpeechMessages() {
        val tts = baiduTts ?: return
        while (pendingSpeechMessages.isNotEmpty()) {
            tts.speak(pendingSpeechMessages.removeFirst())
        }
    }

    private fun urgencyText(urgency: ObstacleUrgency): String {
        return when (urgency) {
            ObstacleUrgency.LOW -> "低紧急度"
            ObstacleUrgency.MEDIUM -> "中等紧急"
            ObstacleUrgency.HIGH -> "重要告警"
        }
    }

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
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        var score = 0f
        for (item in items) {
            left += item.box.left
            top += item.box.top
            right += item.box.right
            bottom += item.box.bottom
            score += item.score
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
                boxArray.optDouble(0).toFloat(),
                boxArray.optDouble(1).toFloat(),
                boxArray.optDouble(2).toFloat(),
                boxArray.optDouble(3).toFloat()
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
            RectF(
                x1 * lastFrameWidth,
                y1 * lastFrameHeight,
                x2 * lastFrameWidth,
                y2 * lastFrameHeight
            )
        } else {
            RectF(x1, y1, x2, y2)
        }
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 80, output)
        return output.toByteArray()
    }

    private fun Bitmap.rotateForDisplay(rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return this
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun getDetectionServerUrl(): String {
        return AppConfig.normalizeBaseUrl(
            AppConfig.prefs(this)
                .getString(AppConfig.KEY_DETECTION_SERVER_BASE_URL, "")
                .orEmpty()
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                switchToSource(cameraSource)
            } else {
                Toast.makeText(this, "相机权限未授予", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        currentSource?.stop()
        currentSource = null
        ttsReady = false
        pendingSpeechMessages.clear()
        baiduTts?.destroy()
        baiduTts = null
        scope.cancel()
        cameraExecutor.shutdownNow()
        inferenceExecutor.shutdownNow()
        try {
            cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)
            inferenceExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}
        ToolRegistry.releaseAll()
        ModelRegistry.releaseAll()
        httpClient.dispatcher.cancelAll()
        closeUdpSocket()
        udpReceiveThread?.interrupt()
        super.onDestroy()
    }
}
