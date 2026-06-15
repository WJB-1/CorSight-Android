package com.example.voicenavigation.collection.ui.gridmode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicenavigation.R
import com.example.voicenavigation.animation.ShutterAnimations
import com.example.voicenavigation.core.compass.CompassProvider
import com.example.voicenavigation.core.location.LocationProvider
import com.example.voicenavigation.core.location.LocationResult
import com.example.voicenavigation.collection.data.PhotoRecord
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * 八方向采集模式。
 *
 * 生命周期管理：onResume 订阅传感器 + 绑定相机，onPause 取消订阅 + 解绑相机。
 * 兼容 ViewPager2：离屏时不占用传感器和相机资源。
 *
 * 电池式方向状态栏（底部，快门上方）。
 * 手势：单击拍照（需对齐）/ 双击切广角 / 双指缩放
 */
@AndroidEntryPoint
class GridCaptureFragment : Fragment() {

    companion object {
        private const val TAG = "GridCaptureFragment"
        private const val ALIGN_TOLERANCE = 12f

        private const val STATE_DONE = 0
        private const val STATE_ACTIVE = 1
        private const val STATE_MISALIGNED = 2
        private const val STATE_PENDING = 3
    }

    @Inject lateinit var compassProvider: CompassProvider
    @Inject lateinit var locationProvider: LocationProvider

    private val viewModel: GridCaptureViewModel by viewModels()

    private lateinit var previewView: PreviewView
    private lateinit var tvDebugOverlay: TextView
    private lateinit var directionBar: LinearLayout
    private lateinit var shutterBtn: View
    private var shutterFlashOverlay: View? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var currentBearing: Float = 0f
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var gpsReady = false
    private var isAligned = false

    private val directionCells = mutableMapOf<String, Pair<View, TextView>>()

    // 传感器订阅 Job
    private var sensorJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(requireContext(), "需要相机权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_grid_capture, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        tvDebugOverlay = view.findViewById(R.id.tvDebugOverlay)
        directionBar = view.findViewById(R.id.directionBar)
        shutterBtn = view.findViewById(R.id.shutterBtn)
        shutterFlashOverlay = view.findViewById(R.id.shutterFlashOverlay)

        shutterBtn.setOnClickListener {
            if (isAligned) takePhoto()
        }
        shutterBtn.setOnTouchListener { v, event ->
            ShutterAnimations.onTouchEvent(v, event)
        }
        setShutterEnabled(false)
        setupGestures()
        buildDirectionBar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.capturedDirections.collectLatest { updateDirectionBar() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentTarget.collectLatest { updateDirectionBar() }
        }
    }

    // ===== 生命周期：onResume 订阅 / onPause 取消 =====

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

    private fun startSensors() {
        sensorJob?.cancel()
        sensorJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                compassProvider.observe().collectLatest { heading ->
                    currentBearing = heading.heading
                    checkAlignment()
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

    // ===== 电池式方向状态栏 =====

    private fun buildDirectionBar() {
        directionBar.removeAllViews()
        directionCells.clear()

        val dp = resources.displayMetrics.density
        val cellWidth = (36 * dp).toInt()
        val cellHeight = (28 * dp).toInt()
        val margin = (2 * dp).toInt()

        GridCaptureViewModel.DIRECTIONS.forEach { dir ->
            val tv = TextView(requireContext()).apply {
                text = dir
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#888888"))
            }

            val cell = FrameLayout(requireContext()).apply {
                setBackgroundResource(R.drawable.bg_direction_cell_pending)
                layoutParams = LinearLayout.LayoutParams(cellWidth, cellHeight).apply {
                    setMargins(margin, 0, margin, 0)
                }
                addView(tv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ))
            }

            directionCells[dir] = Pair(cell, tv)
            directionBar.addView(cell)
        }
    }

    private fun updateDirectionBar() {
        val captured = viewModel.capturedDirections.value
        val target = viewModel.currentTarget.value

        for ((dir, pair) in directionCells) {
            val (cell, tv) = pair
            val state = when {
                dir in captured -> STATE_DONE
                dir == target && isAligned -> STATE_ACTIVE
                dir == target -> STATE_MISALIGNED
                else -> STATE_PENDING
            }

            val bgRes = when (state) {
                STATE_DONE -> R.drawable.bg_direction_cell_done
                STATE_ACTIVE -> R.drawable.bg_direction_cell_active
                STATE_MISALIGNED -> R.drawable.bg_direction_cell_misaligned
                else -> R.drawable.bg_direction_cell_pending
            }
            cell.setBackgroundResource(bgRes)

            val textColor = when (state) {
                STATE_DONE -> Color.WHITE
                STATE_ACTIVE -> Color.BLACK
                STATE_MISALIGNED -> Color.WHITE
                else -> Color.parseColor("#888888")
            }
            tv.setTextColor(textColor)

            if (state == STATE_MISALIGNED) {
                // 预留：动画组在此处接入闪烁动画
            } else {
                cell.alpha = 1f
            }
        }
    }

    // ===== 手势（兼容 ViewPager2 滑动）=====

    private fun setupGestures() {
        val gestureDetector = GestureDetectorCompat(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isAligned) takePhoto()
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
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (scaleDetector.isInProgress) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    } else if (dx > 20 && dx > dy * 1.5f) {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    } else if (dy > 20) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            scaleDetector.isInProgress
        }
    }

    private fun toggleWideAngle() {
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

    // ===== 相机 =====

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
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

    // ===== 对齐检测 =====

    private fun checkAlignment() {
        val target = viewModel.currentTarget.value ?: return
        val targetAngle = GridCaptureViewModel.DIRECTION_ANGLES[target] ?: return

        var diff = abs(currentBearing - targetAngle)
        if (diff > 180) diff = 360 - diff

        val wasAligned = isAligned
        isAligned = diff <= ALIGN_TOLERANCE

        if (isAligned != wasAligned) {
            updateDirectionBar()
            setShutterEnabled(isAligned)
            if (isAligned) {
                val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun setShutterEnabled(enabled: Boolean) {
        ShutterAnimations.setEnabled(shutterBtn, enabled)
    }

    private fun updateDebugOverlay() {
        if (!gpsReady) return
        val cam = camera
        val zoom = cam?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val wideLabel = if (zoom < 0.9f) "WIDE" else "MAIN"
        tvDebugOverlay.visibility = View.VISIBLE
        tvDebugOverlay.setTextColor(0xFF00FF00.toInt())
        tvDebugOverlay.text = String.format(
            "✓ Bearing: %.1f°\nGPS: %.6f, %.6f\nTarget: %s  Zoom: %.1fx [%s]",
            currentBearing, currentLat, currentLng,
            viewModel.currentTarget.value ?: "-", zoom, wideLabel
        )
    }

    // ===== 拍照 =====

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val target = viewModel.currentTarget.value ?: return
        if (!gpsReady) {
            Toast.makeText(requireContext(), "GPS 未就绪，请等待定位完成", Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(requireContext().filesDir, "grid_${target}_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        ShutterAnimations.onCapture(shutterBtn)
        ShutterAnimations.flashOverlay(shutterFlashOverlay)

        ic.takePicture(options, ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val photo = PhotoRecord(
                        filePath = file.absolutePath,
                        bearing = currentBearing,
                        fov = 90f,
                        direction = target,
                        label = target
                    )
                    viewModel.addPhoto(photo)
                    Toast.makeText(requireContext(), "$target 已采集", Toast.LENGTH_SHORT).show()

                    if (viewModel.isComplete) {
                        showSaveDialog()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Take photo failed", exc)
                    Toast.makeText(requireContext(), "拍照失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ===== 保存 =====

    private fun showSaveDialog() {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_save_task, null)
        val tvCount = dialogView.findViewById<TextView>(R.id.tvPhotoCount)
        val tvCoords = dialogView.findViewById<TextView>(R.id.tvCoords)
        val etDesc = dialogView.findViewById<EditText>(R.id.etSceneDesc)

        tvCount.text = "已采集 ${viewModel.photoCount} 张照片"
        tvCoords.text = "坐标: ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLng)}"

        AlertDialog.Builder(ctx)
            .setTitle("八方向采集完成")
            .setView(dialogView)
            .setPositiveButton("确认保存") { _, _ ->
                val desc = etDesc.text.toString().trim().ifEmpty { "未描述" }
                viewModel.saveTask(currentLat, currentLng, desc)
                Toast.makeText(ctx, "已保存采样点", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("放弃") { _, _ ->
                viewModel.photos.value.forEach { File(it.filePath).delete() }
                Toast.makeText(ctx, "已放弃", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }
}
