我懂你意思。现在的 `DataCollectionActivity` 是一个** god class **，把罗盘、定位、拍照、预览、上传、任务管理全塞在一起，而且调用的是系统相机（`MediaStore.ACTION_IMAGE_CAPTURE`），这导致：

1. **无法内嵌参数浮层**（系统相机界面你控制不了）
2. **无法做方向画布提示**（不能往系统相机上画 UI）
3. **无法获取 FOV/焦距**（系统相机不返回这些元数据）
4. **上传和采集耦合**（想重传一张图必须回到采集界面）

重构思路：**拆成 3 个独立 Fragment + 1 个 Hub Activity，相机内嵌为可复用 CameraX Fragment**。

---

## 一、新架构总览

```
┌─────────────────────────────────────────────┐
│           CaptureHubActivity                  │
│  ┌─────────┬─────────┬─────────────────────┐│
│  │ 自由采集 │ 八方向  │    上传管理          ││  ← BottomNavigation
│  │  (Tab1) │  (Tab2) │    (Tab3)           ││
│  └─────────┴─────────┴─────────────────────┘│
│                                               │
│  ┌─────────────────────────────────────────┐  │
│  │         CameraFragment (复用)            │  │
│  │  • PreviewView 全屏预览                  │  │
│  │  • DebugOverlayView 左上角参数             │  │
│  │  • 手势：单击拍照 / 双击切广角 / 双指缩放   │  │
│  │  • 实时采集：方位(横屏校正) + GPS + FOV      │  │
│  └─────────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

---

## 二、数据层重构（先确定，后端对齐时再改字段名）

### 1. 新增 `CaptureMetadata.kt`
把拍照前一刻的传感器数据打包，每张照片独立一份：

```kotlin
package com.example.voicenavigation.collection

data class CaptureMetadata(
    val bearing: Float,      // 0-360°，已做横屏校正
    val lat: Double,
    val lng: Double,
    val fovX: Float,         // 水平视场角
    val fovY: Float,         // 垂直视场角
    val focalLength: Float,  // 当前焦距(mm)
    val zoomRatio: Float,    // 当前变焦倍率
    val isWide: Boolean,     // 是否广角
    val timestamp: Long,
    val screenRotation: Int    // Surface.ROTATION_*
)
```

### 2. 新增 `PhotoRecord.kt`
单张照片的完整记录，替代原来 `Map<String, String>`：

```kotlin
package com.example.voicenavigation.collection

data class PhotoRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val filePath: String,
    val metadata: CaptureMetadata,
    val direction: String? = null,   // 八方向模式用："N"/"NE"...
    val label: String = "",          // 自由模式标注："天桥"/"复杂路口"
    var uploadStatus: String = "pending", // pending / uploaded / failed
    var remoteUrl: String? = null
)
```

### 3. 修改 `CaptureTask.kt`
支持两种模式，用 `photos` 列表替代 `images` Map：

```kotlin
package com.example.voicenavigation.collection

data class CaptureTask(
    val pointId: String,
    val chunkId: String,
    val latitude: Double,
    val longitude: Double,
    val sceneDescription: String,
    val mode: String, // "free" 或 "grid"
    val photos: MutableList<<PhotoRecord> = mutableListOf(),
    var status: String = "pending",
    val createdAt: String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()),
    var updatedAt: String = createdAt,
    var uploadedAt: String? = null
)
```

### 4. 升级 `TaskStorage.kt`
兼容旧数据迁移，新增版本标记：

```kotlin
package com.example.voicenavigation.collection

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class TaskStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("capture_tasks_v2", Context.MODE_PRIVATE)
    private val key = "semantic_map_tasks"

    fun saveTask(task: CaptureTask) {
        val tasks = getAllTasks().toMutableList()
        // 如果存在同 pointId 的旧任务，先移除（更新逻辑）
        tasks.removeAll { it.pointId == task.pointId }
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

    fun updatePhotoStatus(pointId: String, photoId: String, status: String, remoteUrl: String? = null) {
        val tasks = getAllTasks().toMutableList()
        val tIndex = tasks.indexOfFirst { it.pointId == pointId }
        if (tIndex == -1) return
        val task = tasks[tIndex]
        val pIndex = task.photos.indexOfFirst { it.id == photoId }
        if (pIndex == -1) return
        
        task.photos[pIndex] = task.photos[pIndex].copy(
            uploadStatus = status,
            remoteUrl = remoteUrl
        )
        // 如果全部上传完成，更新任务状态
        if (task.photos.all { it.uploadStatus == "uploaded" }) {
            tasks[tIndex] = task.copy(status = "success", uploadedAt = formatDate())
        } else if (task.photos.any { it.uploadStatus == "failed" }) {
            tasks[tIndex] = task.copy(status = "failed", updatedAt = formatDate())
        } else {
            tasks[tIndex] = task.copy(updatedAt = formatDate())
        }
        prefs.edit().putString(key, tasksToJson(tasks)).apply()
    }

    fun updateTaskStatus(pointId: String, status: String) {
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

    // === JSON ===

    private fun tasksToJson(tasks: List<CaptureTask>): String {
        val arr = JSONArray()
        tasks.forEach { task ->
            val obj = JSONObject().apply {
                put("point_id", task.pointId)
                put("chunk_id", task.chunkId)
                put("latitude", task.latitude)
                put("longitude", task.longitude)
                put("scene_description", task.sceneDescription)
                put("mode", task.mode)
                put("status", task.status)
                put("createdAt", task.createdAt)
                put("updatedAt", task.updatedAt)
                put("uploadedAt", task.uploadedAt ?: JSONObject.NULL)
                put("photos", JSONArray().apply {
                    task.photos.forEach { photo ->
                        put(JSONObject().apply {
                            put("id", photo.id)
                            put("file_path", photo.filePath)
                            put("direction", photo.direction ?: JSONObject.NULL)
                            put("label", photo.label)
                            put("upload_status", photo.uploadStatus)
                            put("remote_url", photo.remoteUrl ?: JSONObject.NULL)
                            put("metadata", JSONObject().apply {
                                put("bearing", photo.metadata.bearing)
                                put("lat", photo.metadata.lat)
                                put("lng", photo.metadata.lng)
                                put("fov_x", photo.metadata.fovX)
                                put("fov_y", photo.metadata.fovY)
                                put("focal_length", photo.metadata.focalLength)
                                put("zoom_ratio", photo.metadata.zoomRatio)
                                put("is_wide", photo.metadata.isWide)
                                put("timestamp", photo.metadata.timestamp)
                                put("screen_rotation", photo.metadata.screenRotation)
                            })
                        })
                    }
                })
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
            val photosArr = obj.optJSONArray("photos") ?: JSONArray()
            val photos = mutableListOf<<PhotoRecord>()
            for (j in 0 until photosArr.length()) {
                val p = photosArr.getJSONObject(j)
                val m = p.getJSONObject("metadata")
                photos.add(PhotoRecord(
                    id = p.getString("id"),
                    filePath = p.getString("file_path"),
                    direction = if (p.isNull("direction")) null else p.getString("direction"),
                    label = p.optString("label", ""),
                    uploadStatus = p.optString("upload_status", "pending"),
                    remoteUrl = if (p.isNull("remote_url")) null else p.getString("remote_url"),
                    metadata = CaptureMetadata(
                        bearing = m.getDouble("bearing").toFloat(),
                        lat = m.getDouble("lat"),
                        lng = m.getDouble("lng"),
                        fovX = m.getDouble("fov_x").toFloat(),
                        fovY = m.getDouble("fov_y").toFloat(),
                        focalLength = m.getDouble("focal_length").toFloat(),
                        zoomRatio = m.getDouble("zoom_ratio").toFloat(),
                        isWide = m.getBoolean("is_wide"),
                        timestamp = m.getLong("timestamp"),
                        screenRotation = m.optInt("screen_rotation", 0)
                    )
                ))
            }
            result.add(CaptureTask(
                pointId = obj.getString("point_id"),
                chunkId = obj.getString("chunk_id"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                sceneDescription = obj.getString("scene_description"),
                mode = obj.getString("mode"),
                photos = photos,
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
```

---

## 三、上传层重构：支持单图 + 进度

核心是给 OkHttp 包一个 `CountingRequestBody`，然后 `UploadService` 暴露单图上传和批量进度上传。

```kotlin
package com.example.voicenavigation.collection

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class UploadService(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    var lastError: String = ""
        private set

    companion object {
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    // ========== 旧批量接口保留（兼容）==========
    suspend fun uploadTask(task: CaptureTask): Boolean {
        // ... 原有逻辑基本不变，内部改为调用 uploadSingleImage ...
        // 为节省篇幅，这里省略，需要时可直接复用你现有代码
        return true
    }

    // ========== 新增：单图上传（支持进度）==========
    /**
     * 上传单张图片
     * @param pointId 采样点ID
     * @param photo 照片记录
     * @param progress 进度回调 (0-100)
     */
    suspend fun uploadSingleImage(
        pointId: String,
        photo: PhotoRecord,
        progress: (Int) -> Unit
    ): Boolean {
        lastError = ""
        val file = File(photo.filePath)
        if (!file.exists()) {
            lastError = "文件不存在: ${photo.filePath}"
            return false
        }

        return uploadWithRetry("单图上传 ${photo.id}") {
            val body = file.asRequestBody("image/jpeg".toMediaType())
            val progressBody = CountingRequestBody(body) { bytesSent, total ->
                if (total > 0) {
                    val pct = (bytesSent * 100 / total).toInt()
                    progress(pct.coerceIn(0, 100))
                }
            }

            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("point_id", pointId)
                .addFormDataPart("photo_id", photo.id)
                .addFormDataPart("direction", photo.direction ?: "free")
                .addFormDataPart("label", photo.label)
                .addFormDataPart("metadata", JSONObject().apply {
                    put("bearing", photo.metadata.bearing)
                    put("lat", photo.metadata.lat)
                    put("lng", photo.metadata.lng)
                    put("fov_x", photo.metadata.fovX)
                    put("fov_y", photo.metadata.fovY)
                    put("focal_length", photo.metadata.focalLength)
                    put("zoom_ratio", photo.metadata.zoomRatio)
                    put("is_wide", photo.metadata.isWide)
                    put("timestamp", photo.metadata.timestamp)
                }.toString())
                .addFormDataPart("image", file.name, progressBody)

            val request = Request.Builder()
                .url("$baseUrl/api/upload/image") // 后端单图接口
                .post(builder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.d("UploadService", "单图上传成功: ${photo.id}")
                    true
                } else {
                    lastError = "上传失败 ${response.code}: $body"
                    Log.e("UploadService", lastError)
                    false
                }
            }
        }
    }

    // ========== 新增：批量上传带单图进度 ==========
    suspend fun uploadTaskWithProgress(
        task: CaptureTask,
        onPhotoProgress: (photoId: String, percent: Int) -> Unit,
        onPhotoComplete: (photoId: String, success: Boolean) -> Unit
    ): Boolean {
        lastError = ""
        var allSuccess = true

        for (photo in task.photos) {
            if (photo.uploadStatus == "uploaded") {
                onPhotoComplete(photo.id, true)
                continue
            }

            val ok = uploadSingleImage(task.pointId, photo) { pct ->
                onPhotoProgress(photo.id, pct)
            }

            onPhotoComplete(photo.id, ok)
            if (!ok) {
                allSuccess = false
                // 继续上传其他图片，不中断
            }
        }
        return allSuccess
    }

    // ========== 重试包装器 ==========
    private inline fun uploadWithRetry(tag: String, block: () -> Boolean): Boolean {
        var attempt = 0
        while (attempt < MAX_RETRY) {
            attempt++
            try {
                if (block()) return true
                Log.w("UploadService", "$tag 第 $attempt 次失败，准备重试...")
            } catch (e: IOException) {
                Log.w("UploadService", "$tag 第 $attempt 次网络异常: ${e.message}")
            }
            if (attempt < MAX_RETRY) Thread.sleep(RETRY_DELAY_MS)
        }
        Log.e("UploadService", "$tag 重试 $MAX_RETRY 次后失败")
        return false
    }
}

// ========== 进度包装器 ==========
class CountingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: okio.BufferedSink) {
        val countingSink = object : okio.ForwardingSink(sink) {
            var bytesWritten = 0L
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten, contentLength())
            }
        }
        val bufferedSink = okio.buffer(countingSink)
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}
```

---

## 四、相机层：可复用 CameraFragment

这是重构的核心。用 **CameraX 内嵌预览** 替代系统相机，才能做左上角参数浮层和画布方向提示。

### 1. `fragment_camera.xml`（基础相机布局）

```xml
<?xml version="1.0" encoding="utf-8"?>
<<FrameLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black">

    <!-- 全屏预览 -->
    <androidx.camera.view.PreviewView
        android:id="@+id/previewView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- 左上角调试参数 -->
    <com.example.voicenavigation.collection.DebugOverlayView
        android:id="@+id/debugOverlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- 中央方向提示（八方向模式用，自由模式可隐藏） -->
    <TextView
        android:id="@+id/tvDirectionHint"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="请对准 N"
        android:textSize="48sp"
        android:textStyle="bold"
        android:textColor="#80FFFFFF"
        android:visibility="gone" />

    <!-- 底部快门 -->
    <View
        android:id="@+id/shutterBtn"
        android:layout_width="72dp"
        android:layout_height="72dp"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="40dp"
        android:background="@drawable/circle_shutter" />

</FrameLayout>
```

### 2. `DebugOverlayView.kt`（之前给过，精简版）

```kotlin
package com.example.voicenavigation.collection

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class DebugOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint().apply { color = Color.parseColor("#AA000000") }
    private val textPaint = Paint().apply {
        color = Color.GREEN; textSize = 30f; typeface = Typeface.MONOSPACE; isAntiAlias = true
    }
    private val strokePaint = Paint().apply {
        color = Color.BLACK; textSize = 30f; typeface = Typeface.MONOSPACE
        style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private var lines: List<String> = emptyList()

    fun update(meta: CaptureMetadata?) {
        meta?.let { m ->
            lines = listOf(
                String.format("FOV: %.1f°×%.1f°", m.fovX, m.fovY),
                String.format("Zoom: %.1fx [%s]", m.zoomRatio, if (m.isWide) "WIDE" else "MAIN"),
                String.format("Bearing: %.1f°", m.bearing),
                String.format("GPS: %.6f, %.6f", m.lat, m.lng),
                String.format("Time: %tT", m.timestamp)
            )
        } ?: run { lines = emptyList() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return
        val lineH = 40f
        val startY = 70f
        val maxW = lines.maxOf { textPaint.measureText(it) }
        canvas.drawRect(0f, 0f, maxW + 40f, startY + lines.size * lineH, bgPaint)
        lines.forEachIndexed { i, t ->
            val y = startY + i * lineH
            canvas.drawText(t, 20f, y, strokePaint)
            canvas.drawText(t, 20f, y, textPaint)
        }
    }
}
```

### 3. `CameraFragment.kt`（核心复用类）

```kotlin
package com.example.voicenavigation.collection

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicenavigation.databinding.FragmentCameraBinding
import com.google.android.gms.location.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@ExperimentalCamera2Interop
abstract class CameraFragment : Fragment(), SensorEventListener {

    private var _binding: FragmentCameraBinding? = null
    protected val binding get() = _binding!!

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // 传感器
    private lateinit var sensorManager: SensorManager
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    
    // 定位
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    // 实时元数据（拍照前一刻深拷贝）
    @Volatile
    protected var liveMeta: CaptureMetadata? = null

    // 回调：拍照完成
    protected var onPhotoTaken: ((PhotoRecord) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        if (allPermissionsGranted()) {
            startCamera()
            startSensors()
            startLocation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), 10)
        }

        // 手势
        setupGestures()
        
        // 定时刷新浮层
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                binding.debugOverlay.update(liveMeta)
                onMetadataUpdated(liveMeta)
                delay(100)
            }
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                updateFov()
            } catch (e: Exception) {
                Log.e("CameraFragment", "绑定失败", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun updateFov() {
        val cam = camera ?: return
        val c2Info = Camera2CameraInfo.from(cam.cameraInfo)
        val chars = c2Info.cameraCharacteristics
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        
        if (focalLengths != null && sensorSize != null && focalLengths.isNotEmpty()) {
            val f = focalLengths[0]
            val fovX = Math.toDegrees(2 * kotlin.math.atan((sensorSize.width / (2 * f)).toDouble())).toFloat()
            val fovY = Math.toDegrees(2 * kotlin.math.atan((sensorSize.height / (2 * f)).toDouble())).toFloat()
            liveMeta = liveMeta?.copy(fovX = fovX, fovY = fovY, focalLength = f)
                ?: CaptureMetadata(0f, 0.0, 0.0, fovX, fovY, f, 1f, false, 0L, 0)
        }
    }

    private fun startSensors() {
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    private fun startLocation() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 100L).build()
        fusedLocationClient.requestLocationUpdates(
            request, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { loc ->
                        liveMeta = liveMeta?.copy(lat = loc.latitude, lng = loc.longitude)
                            ?: CaptureMetadata(0f, loc.latitude, loc.longitude, 0f, 0f, 0f, 1f, false, 0L, 0)
                    }
                }
            }, Looper.getMainLooper()
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        
        // 横屏校正：假设 Activity 是 landscape
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_Y,
            SensorManager.AXIS_MINUS_X,
            remappedMatrix
        )
        SensorManager.getOrientation(remappedMatrix, orientation)
        
        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val bearing = (azimuth + 360) % 360
        
        liveMeta = liveMeta?.copy(bearing = bearing)
            ?: CaptureMetadata(bearing, 0.0, 0.0, 0f, 0f, 0f, 1f, false, 0L, 0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ========== 手势 ==========
    private fun setupGestures() {
        val gesture = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                takePhoto()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleWideAngle()
                return true
            }
        })
        val scale = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                camera?.cameraControl?.setZoomRatio(zoom * detector.scaleFactor)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
            scale.onTouchEvent(event)
            true
        }
    }

    private fun toggleWideAngle() {
        val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
        val isWide = (zoomState.zoomRatio < 0.9f)
        val target = if (isWide) 1.0f else zoomState.minZoomRatio
        camera?.cameraControl?.setZoomRatio(target)
    }

    // ========== 拍照 ==========
    protected fun takePhoto() {
        val meta = liveMeta?.copy(timestamp = System.currentTimeMillis()) ?: return
        val ic = imageCapture ?: return
        val file = File(requireContext().filesDir, "img_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        ic.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val photo = PhotoRecord(
                    filePath = file.absolutePath,
                    metadata = meta.copy(
                        zoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f,
                        isWide = (camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f) < 0.9f
                    )
                )
                requireActivity().runOnUiThread {
                    onPhotoTaken?.invoke(photo)
                    Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraFragment", "拍照失败", exc)
            }
        })
    }

    // 子类重写：元数据更新时回调（用于八方向对齐判断）
    protected open fun onMetadataUpdated(meta: CaptureMetadata?) {}

    protected fun setDirectionHint(text: String, color: Int) {
        binding.tvDirectionHint.apply {
            visibility = View.VISIBLE
            this.text = text
            setTextColor(color)
        }
    }

    protected fun hideDirectionHint() {
        binding.tvDirectionHint.visibility = View.GONE
    }

    protected fun setShutterEnabled(enabled: Boolean) {
        binding.shutterBtn.alpha = if (enabled) 1.0f else 0.3f
        binding.shutterBtn.isClickable = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(object : LocationCallback() {})
        cameraExecutor.shutdown()
        _binding = null
    }
}
```

---

## 五、两种采集模式 Fragment

### 1. `FreeCaptureFragment.kt`（自由采集）

```kotlin
package com.example.voicenavigation.collection

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

@ExperimentalCamera2Interop
class FreeCaptureFragment : CameraFragment() {

    private val photos = mutableListOf<<PhotoRecord>()
    private lateinit var taskStorage: TaskStorage

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        taskStorage = TaskStorage(requireContext())
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideDirectionHint() // 自由模式不需要方向提示
        
        onPhotoTaken = { photo ->
            showLabelDialog(photo)
        }
    }

    private fun showLabelDialog(photo: PhotoRecord) {
        val ctx = requireContext()
        val spinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf(
                "天桥", "复杂路口", "斑马线", "公交站台", "隧道/地道", "普通道路", "其他"
            ))
        }
        val edit = EditText(ctx).apply { hint = "补充描述（可选）" }

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(spinner)
            addView(edit)
        }

        AlertDialog.Builder(ctx)
            .setTitle("场景标注")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val label = spinner.selectedItem.toString() + if (edit.text.isNotEmpty()) " - ${edit.text}" else ""
                val finalPhoto = photo.copy(label = label)
                photos.add(finalPhoto)
                // 这里可以实时保存到 TaskStorage，或等用户手动打包成 Task
            }
            .setNegativeButton("放弃") { _, _ ->
                File(photo.filePath).delete()
            }
            .show()
    }

    /** 打包为 Task 保存 */
    fun saveAsTask(pointId: String, chunkId: String, lat: Double, lng: Double, desc: String) {
        val task = CaptureTask(
            pointId = pointId,
            chunkId = chunkId,
            latitude = lat,
            longitude = lng,
            sceneDescription = desc,
            mode = "free",
            photos = photos.toMutableList()
        )
        taskStorage.saveTask(task)
        photos.clear()
    }
}
```

### 2. `GridCaptureFragment.kt`（八方向采集）

```kotlin
package com.example.voicenavigation.collection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.content.ContextCompat
import com.example.voicenavigation.R
import kotlin.math.abs

@ExperimentalCamera2Interop
class GridCaptureFragment : CameraFragment() {

    private val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private val captured = mutableSetOf<String>()
    private var currentTarget: String = directions[0]
    private val ALIGN_TOLERANCE = 12f

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pickNextTarget() // 不固定从N开始，找第一个未拍方向

        onPhotoTaken = { photo ->
            val directedPhoto = photo.copy(direction = currentTarget)
            captured.add(currentTarget)
            Toast.makeText(requireContext(), "$currentTarget 已采集", Toast.LENGTH_SHORT).show()
            
            if (captured.size == directions.size) {
                Toast.makeText(requireContext(), "八方向采集完成！", Toast.LENGTH_LONG).show()
                // TODO: 弹出保存 Task 对话框
            } else {
                pickNextTarget()
            }
        }
    }

    override fun onMetadataUpdated(meta: CaptureMetadata?) {
        meta ?: return
        val targetAngle = directions.indexOf(currentTarget) * 45f
        var diff = abs(meta.bearing - targetAngle)
        if (diff > 180) diff = 360 - diff

        val aligned = diff <= ALIGN_TOLERANCE
        if (aligned) {
            setDirectionHint("已对准 $currentTarget", ContextCompat.getColor(requireContext(), R.color.vision_green))
            setShutterEnabled(true)
        } else {
            setDirectionHint("请对准 $currentTarget", ContextCompat.getColor(requireContext(), R.color.orange))
            setShutterEnabled(false)
        }
    }

    private fun pickNextTarget() {
        currentTarget = directions.firstOrNull { !captured.contains(it) } ?: return
    }
}
```

---

## 六、上传管理界面 `UploadManagerFragment`

```kotlin
package com.example.voicenavigation.collection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicenavigation.AppConfig
import com.example.voicenavigation.network.TripPreviewService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UploadManagerFragment : Fragment() {

    private lateinit var taskStorage: TaskStorage
    private lateinit var uploadService: UploadService
    private lateinit var listView: ListView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = ScrollView(requireContext())
        listView = ListView(requireContext())
        scroll.addView(listView)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        taskStorage = TaskStorage(requireContext())
        refreshList()
    }

    private fun refreshList() {
        val tasks = taskStorage.getAllTasks()
        val adapter = object : ArrayAdapter<CaptureTask>(requireContext(), android.R.layout.simple_list_item_1, tasks) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val task = getItem(position)!!
                val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

                // 任务标题
                container.addView(TextView(requireContext()).apply {
                    text = "${task.pointId} [${task.mode}] (${task.status})"
                    textSize = 16f
                    setPadding(20, 20, 20, 10)
                })

                // 每张照片的进度
                task.photos.forEach { photo ->
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(40, 5, 20, 5)
                    }
                    val label = TextView(requireContext()).apply {
                        text = photo.direction ?: photo.label
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    }
                    val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                        layoutParams = LinearLayout.LayoutParams(200, 50)
                        progress = if (photo.uploadStatus == "uploaded") 100 else 0
                    }
                    val btnRetry = Button(requireContext()).apply {
                        text = if (photo.uploadStatus == "uploaded") "已完成" else "重传"
                        isEnabled = photo.uploadStatus != "uploaded"
                        setOnClickListener {
                            uploadSingle(task.pointId, photo, progressBar)
                        }
                    }
                    row.addView(label)
                    row.addView(progressBar)
                    row.addView(btnRetry)
                    container.addView(row)
                }

                // 整任务上传按钮
                container.addView(Button(requireContext()).apply {
                    text = "上传全部"
                    setOnClickListener { uploadTask(task) }
                })

                return container
            }
        }
        listView.adapter = adapter
    }

    private fun uploadSingle(pointId: String, photo: PhotoRecord, progressBar: ProgressBar) {
        val prefs = AppConfig.prefs(requireContext())
        val baseUrl = AppConfig.normalizeBaseUrl(
            prefs.getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL)
        )
        if (baseUrl.isEmpty()) return
        uploadService = UploadService(baseUrl)

        lifecycleScope.launch(Dispatchers.IO) {
            val ok = uploadService.uploadSingleImage(pointId, photo) { pct ->
                requireActivity().runOnUiThread { progressBar.progress = pct }
            }
            withContext(Dispatchers.Main) {
                taskStorage.updatePhotoStatus(pointId, photo.id, if (ok) "uploaded" else "failed")
                refreshList()
            }
        }
    }

    private fun uploadTask(task: CaptureTask) {
        // 类似逻辑，调用 uploadTaskWithProgress
        // 省略...
    }
}
```

---

## 七、主入口 `CaptureHubActivity`

```kotlin
package com.example.voicenavigation.collection

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.fragment.app.Fragment
import com.example.voicenavigation.R
import com.google.android.material.bottomnavigation.BottomNavigationView

@ExperimentalCamera2Interop
class CaptureHubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_hub)

        val nav = findViewById<<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_free -> switchFragment(FreeCaptureFragment())
                R.id.nav_grid -> switchFragment(GridCaptureFragment())
                R.id.nav_upload -> switchFragment(UploadManagerFragment())
            }
            true
        }
        nav.selectedItemId = R.id.nav_free
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
```

`activity_capture_hub.xml`：

```xml
<<LinearLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <FrameLayout
        android:id="@+id/fragmentContainer"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNav"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:menu="@menu/capture_nav_menu" />
</LinearLayout>
```

`res/menu/capture_nav_menu.xml`：

```xml
<<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/nav_free" android:title="自由采集" android:icon="@drawable/ic_camera" />
    <item android:id="@+id/nav_grid" android:title="八方向" android:icon="@drawable/ic_grid" />
    <item android:id="@+id/nav_upload" android:title="上传管理" android:icon="@drawable/ic_upload" />
</menu>
```

---

## 八、你需要做的迁移 checklist

| 步骤 | 操作 |
|------|------|
| 1 | `build.gradle` 添加 CameraX 依赖 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) |
| 2 | 替换 `CaptureTask.kt` 和 `TaskStorage.kt`（新 JSON 结构） |
| 3 | 替换 `UploadService.kt`（新增单图 + 进度） |
| 4 | 新建 `CaptureMetadata.kt`, `PhotoRecord.kt`, `DebugOverlayView.kt` |
| 5 | 新建 `CameraFragment.kt`, `FreeCaptureFragment.kt`, `GridCaptureFragment.kt` |
| 6 | 新建 `UploadManagerFragment.kt` |
| 7 | 新建 `CaptureHubActivity.kt` + 布局 + menu |
| 8 | 把 `DataCollectionActivity` 里的高德定位逻辑提取为 `LocationHelper` 复用（如果你还想用高德） |
| 9 | 旧数据：如果 `prefs` 文件名从 `capture_tasks` 改为 `capture_tasks_v2`，旧数据会自动隔离，不影响新系统 |

---

**核心思路总结**：
- **相机内嵌**：用 CameraX 替代系统相机，才能拿到 FOV、做画布提示、做参数浮层
- **数据原子化**：每张照片带独立 `CaptureMetadata`，不再只是 `Map<<方向, 路径>`
- **上传解耦**：采集和上传完全分开，上传界面能看到每张图的进度条，支持单图重传
- **模式分离**：自由采集和八方向是两个 Fragment，通过 BottomNavigation 切换，互不干扰
