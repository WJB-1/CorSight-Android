package com.example.voicenavigation.collection.ui.base

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicenavigation.R
import com.example.voicenavigation.animation.ShutterAnimations
import com.example.voicenavigation.core.compass.CompassProvider
import com.example.voicenavigation.core.location.LocationProvider
import com.example.voicenavigation.core.location.LocationResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * 采集 Fragment 基类。
 *
 * 抽离所有采集模式的共同逻辑：
 * - CameraX 生命周期管理（onResume 绑定 / onPause 解绑）
 * - 罗盘 + 定位传感器订阅（共享流）
 * - 手势裁判（单击 / 双击广角 / 双指缩放 / 兼容 ViewPager2 横滑）
 * - 权限管理
 * - 快门按钮动画
 * - DebugOverlay 更新
 * - 保存对话框
 *
 * 子类只需实现：
 * - [layoutResId] — 布局资源 ID
 * - [onPreviewViewReady] — 绑定 Fragment 特有的 UI 控件
 * - [canTakePhoto] — 单击时是否允许拍照
 * - [onPhotoTaken] — 拍照成功后的处理（标注/方向记录等）
 * - [onCompassUpdated] — 航向更新回调（可选重写，八方向用）
 * - [onDebugOverlayText] — 格式化 DebugOverlay 文本（可选重写）
 */
abstract class BaseCaptureFragment : Fragment() {

    companion object {
        private const val TAG = "BaseCaptureFragment"
    }

    @Inject lateinit var compassProvider: CompassProvider
    @Inject lateinit var locationProvider: LocationProvider

    // ===== 子类需实现 =====

    /** 布局资源 ID */
    abstract val layoutResId: Int

    /** previewView 就绪后，子类绑定自己的 UI 控件 */
    abstract fun onPreviewViewReady(view: View)

    /** 单击快门时是否允许拍照（自由模式 true，八方向需 isAligned） */
    abstract fun canTakePhoto(): Boolean

    /** 拍照成功后的处理（子类负责构建 PhotoRecord 并存储） */
    abstract fun onPhotoTaken(file: File, bearing: Float, fov: Float, zoom: Float, isWide: Boolean)

    /** 航向数据更新（可选重写，八方向模式用于对齐检测） */
    open fun onCompassUpdated(heading: Float) {}

    /** 生成 DebugOverlay 文本（可选重写，提供自定义信息） */
    open fun onDebugOverlayText(): String? = null

    // ===== 共享状态 =====

    protected lateinit var previewView: PreviewView
    protected lateinit var tvDebugOverlay: TextView
    protected lateinit var shutterBtn: View
    protected var shutterFlashOverlay: View? = null

    protected var cameraProvider: ProcessCameraProvider? = null
    protected var camera: Camera? = null
    protected var imageCapture: ImageCapture? = null
    protected var currentBearing: Float = 0f
    protected var currentLat: Double = 0.0
    protected var currentLng: Double = 0.0
    protected var gpsReady = false

    private var sensorJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "需要相机权限", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 生命周期 =====

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(layoutResId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        tvDebugOverlay = view.findViewById(R.id.tvDebugOverlay)
        shutterBtn = view.findViewById(R.id.shutterBtn)
        shutterFlashOverlay = view.findViewById(R.id.shutterFlashOverlay)

        shutterBtn.setOnClickListener {
            if (canTakePhoto()) takePhoto()
        }
        shutterBtn.setOnTouchListener { v, event ->
            ShutterAnimations.onTouchEvent(v, event)
        }

        setupGestures()
        onPreviewViewReady(view)
    }

    override fun onResume() {
        super.onResume()
        startSensors()
        if (hasCameraPermission()) startCamera()
    }

    override fun onPause() {
        super.onPause()
        stopSensors()
        cameraProvider?.unbindAll()
    }

    // ===== 传感器 =====

    private fun startSensors() {
        sensorJob?.cancel()
        sensorJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                compassProvider.observe().collectLatest { heading ->
                    currentBearing = heading.heading
                    onCompassUpdated(currentBearing)
                    updateDebugOverlay()
                }
            }
            launch {
                locationProvider.observe().collectLatest { result ->
                    when (result) {
                        is LocationResult.Success -> {
                            currentLat = result.location.latitude
                            currentLng = result.location.longitude
                            gpsReady = true
                            updateDebugOverlay()
                        }
                        is LocationResult.Error -> {
                            gpsReady = false
                            tvDebugOverlay.visibility = View.VISIBLE
                            tvDebugOverlay.text = "⚠ GPS 错误: ${result.message}"
                            tvDebugOverlay.setTextColor(0xFFF44336.toInt())
                        }
                    }
                }
            }
        }
        tvDebugOverlay.visibility = View.VISIBLE
        tvDebugOverlay.text = "⏳ 正在定位..."
        tvDebugOverlay.setTextColor(0xFFFF9800.toInt())
    }

    private fun stopSensors() {
        sensorJob?.cancel()
        sensorJob = null
    }

    // ===== 手势裁判（修复 ViewPager2 冲突 + 双指缩放）=====

    private fun setupGestures() {
        val gestureDetector = GestureDetectorCompat(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (canTakePhoto()) takePhoto()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleWideAngle()
                    return true
                }
            })

        val scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    cam.cameraControl.setZoomRatio(currentZoom * detector.scaleFactor)
                    return true
                }
            })

        var downX = 0f
        var downY = 0f

        previewView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                // 关键：双指落下的瞬间立即锁死事件，防止 ViewPager2 抢走
                MotionEvent.ACTION_POINTER_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    // 双指缩放中或屏幕上有两根手指 → 保持拦截
                    if (scaleDetector.isInProgress || event.pointerCount >= 2) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    // 单指：判断是横向滑动（交给 ViewPager2）还是纵向/点击（自己处理）
                    else {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        if (dx > 20 && dx > dy * 1.5f) {
                            v.parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            // 消费事件条件：缩放进行中或多指触控
            scaleDetector.isInProgress || event.pointerCount >= 2
        }
    }

    // ===== 相机 =====

    protected fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    protected fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            cameraProvider = future.get()
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ===== 广角切换 =====

    protected fun toggleWideAngle() {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val isCurrentlyWide = zoomState.zoomRatio < 0.9f
        val targetZoom = if (isCurrentlyWide) 1.0f else zoomState.minZoomRatio
        cam.cameraControl.setZoomRatio(targetZoom)
        Toast.makeText(
            requireContext(),
            if (isCurrentlyWide) "标准镜头" else "广角模式",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ===== 拍照 =====

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (!gpsReady) {
            Toast.makeText(requireContext(), "GPS 未就绪，请等待定位完成", Toast.LENGTH_SHORT).show()
            return
        }

        val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val isWide = currentZoom < 0.9f
        val fov = 90f

        val file = File(requireContext().filesDir, "img_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        ShutterAnimations.onCapture(shutterBtn)
        ShutterAnimations.flashOverlay(shutterFlashOverlay)

        ic.takePicture(options, ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onPhotoTaken(file, currentBearing, fov, currentZoom, isWide)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Take photo failed", exc)
                    Toast.makeText(requireContext(), "拍照失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ===== 快门状态 =====

    protected fun setShutterEnabled(enabled: Boolean) {
        ShutterAnimations.setEnabled(shutterBtn, enabled)
    }

    // ===== DebugOverlay =====

    private fun updateDebugOverlay() {
        if (!gpsReady) return
        val cam = camera
        val zoom = cam?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val wideLabel = if (zoom < 0.9f) "WIDE" else "MAIN"

        val customText = onDebugOverlayText()
        val baseText = String.format(
            "✓ Bearing: %.1f°\nGPS: %.6f, %.6f\nZoom: %.1fx [%s]",
            currentBearing, currentLat, currentLng, zoom, wideLabel
        )
        tvDebugOverlay.visibility = View.VISIBLE
        tvDebugOverlay.setTextColor(0xFF00FF00.toInt())
        tvDebugOverlay.text = if (customText != null) "$baseText\n$customText" else baseText
    }

    // ===== 保存对话框 =====

    protected fun showSaveDialog(
        title: String = "保存采样点",
        photoCount: Int,
        onSave: (sceneDescription: String) -> Unit,
        onDiscard: () -> Unit
    ) {
        val ctx = requireContext()
        if (photoCount == 0) {
            Toast.makeText(ctx, "还未拍摄任何照片", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_save_task, null)
        val tvCount = dialogView.findViewById<TextView>(R.id.tvPhotoCount)
        val tvCoords = dialogView.findViewById<TextView>(R.id.tvCoords)
        val etDesc = dialogView.findViewById<EditText>(R.id.etSceneDesc)

        tvCount.text = "已采集 $photoCount 张照片"
        tvCoords.text = "坐标: ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLng)}"

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("确认保存") { _, _ ->
                val desc = etDesc.text.toString().trim().ifEmpty { "未描述" }
                onSave(desc)
                Toast.makeText(ctx, "已保存采样点（${photoCount} 张）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("放弃全部") { _, _ ->
                onDiscard()
                Toast.makeText(ctx, "已放弃 $photoCount 张照片", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("继续拍摄", null)
            .setCancelable(true)
            .show()
    }
}
