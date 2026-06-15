package com.example.voicenavigation.collection.ui.freemode

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
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
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
import com.example.voicenavigation.core.camera.CaptureMetadata
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

/**
 * 自由拍照模式。
 *
 * 生命周期管理：onResume 订阅传感器 + 绑定相机，onPause 取消订阅 + 解绑相机。
 * 兼容 ViewPager2：离屏时不占用传感器和相机资源。
 *
 * 手势：单击拍照 / 双击切广角 / 双指缩放
 * 触摸事件：未消费时返回 false，让 ViewPager2 处理横向滑动。
 */
@AndroidEntryPoint
class FreeCaptureFragment : Fragment() {

    @Inject lateinit var compassProvider: CompassProvider
    @Inject lateinit var locationProvider: LocationProvider

    private val viewModel: FreeCaptureViewModel by viewModels()

    private lateinit var previewView: PreviewView
    private lateinit var tvDebugOverlay: TextView
    private lateinit var tvPhotoCount: TextView
    private lateinit var btnSaveTask: View
    private lateinit var shutterBtn: View
    private var shutterFlashOverlay: View? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var currentBearing: Float = 0f
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var currentFovX: Float = 90f
    private var gpsReady = false

    // 传感器订阅 Job
    private var sensorJob: Job? = null

    private val sceneLabels = listOf("天桥", "复杂路口", "斑马线", "公交站台", "隧道/地道", "普通道路", "其他")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "需要相机权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_free_capture, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        tvDebugOverlay = view.findViewById(R.id.tvDebugOverlay)
        tvPhotoCount = view.findViewById(R.id.tvPhotoCount)
        btnSaveTask = view.findViewById(R.id.btnSaveTask)
        shutterBtn = view.findViewById(R.id.shutterBtn)
        shutterFlashOverlay = view.findViewById(R.id.shutterFlashOverlay)

        shutterBtn.setOnClickListener { takePhoto() }
        shutterBtn.setOnTouchListener { v, event ->
            ShutterAnimations.onTouchEvent(v, event)
            false
        }
        btnSaveTask.setOnClickListener { showSaveDialog() }
        setupGestures()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photos.collectLatest { photos ->
                tvPhotoCount.text = "已拍: ${photos.size}"
                btnSaveTask.visibility = if (photos.isNotEmpty()) View.VISIBLE else View.GONE
            }
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

    // ===== 手势（兼容 ViewPager2 滑动）=====

    private fun setupGestures() {
        val gestureDetector = GestureDetectorCompat(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    takePhoto()
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

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            // 双指缩放进行中时拦截，否则让 ViewPager2 处理横向滑动
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
                currentFovX = 90f
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ===== UI 更新 =====

    private fun updateDebugOverlay() {
        if (!gpsReady) return
        val cam = camera
        val zoom = cam?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val wideLabel = if (zoom < 0.9f) "WIDE" else "MAIN"
        tvDebugOverlay.visibility = View.VISIBLE
        tvDebugOverlay.setTextColor(0xFF00FF00.toInt())
        tvDebugOverlay.text = String.format(
            "✓ Bearing: %.1f°\nGPS: %.6f, %.6f\nFOV: %.0f°  Zoom: %.1fx [%s]",
            currentBearing, currentLat, currentLng, currentFovX, zoom, wideLabel
        )
    }

    // ===== 拍照 =====

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (!gpsReady) {
            Toast.makeText(requireContext(), "GPS 未就绪，请等待定位完成", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(requireContext().filesDir, "free_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val isWide = currentZoom < 0.9f

        ShutterAnimations.onCapture(shutterBtn)
        ShutterAnimations.flashOverlay(shutterFlashOverlay)

        ic.takePicture(options, ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val meta = CaptureMetadata(
                        bearing = currentBearing,
                        latitude = currentLat,
                        longitude = currentLng,
                        fovX = currentFovX,
                        fovY = currentFovX * 0.75f,
                        focalLength = 0f,
                        zoomRatio = currentZoom,
                        isWide = isWide,
                        timestamp = System.currentTimeMillis()
                    )
                    val photo = PhotoRecord(
                        filePath = file.absolutePath,
                        bearing = meta.bearing,
                        fov = meta.fovX
                    )
                    showLabelDialog(photo)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Take photo failed", exc)
                    Toast.makeText(requireContext(), "拍照失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ===== 对话框 =====

    private fun showLabelDialog(photo: PhotoRecord) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_photo_label, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerLabel)
        val tvBearing = dialogView.findViewById<TextView>(R.id.tvBearing)
        val etDesc = dialogView.findViewById<EditText>(R.id.etDescription)

        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, sceneLabels)
        tvBearing.text = "方位角: ${String.format("%.1f", photo.bearing)}°"

        AlertDialog.Builder(ctx)
            .setTitle("场景标注")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val label = spinner.selectedItem.toString()
                val description = etDesc.text.toString().trim()
                val finalPhoto = photo.copy(
                    label = label,
                    description = description.ifEmpty { label }
                )
                viewModel.addPhoto(finalPhoto)
                Toast.makeText(ctx, "已保存 ($label)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("放弃") { _, _ ->
                File(photo.filePath).delete()
            }
            .setCancelable(false)
            .show()
    }

    private fun showSaveDialog() {
        val ctx = requireContext()
        val photos = viewModel.photos.value
        if (photos.isEmpty()) {
            Toast.makeText(ctx, "还未拍摄任何照片", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_save_task, null)
        val tvCount = dialogView.findViewById<TextView>(R.id.tvPhotoCount)
        val tvCoords = dialogView.findViewById<TextView>(R.id.tvCoords)
        val etDesc = dialogView.findViewById<EditText>(R.id.etSceneDesc)

        tvCount.text = "已采集 ${photos.size} 张照片"
        tvCoords.text = "坐标: ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLng)}"

        AlertDialog.Builder(ctx)
            .setTitle("保存采样点")
            .setView(dialogView)
            .setPositiveButton("确认保存") { _, _ ->
                val desc = etDesc.text.toString().trim().ifEmpty { "未描述" }
                viewModel.saveTask(currentLat, currentLng, desc)
                Toast.makeText(ctx, "已保存采样点（${photos.size} 张）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("放弃全部") { _, _ ->
                photos.forEach { File(it.filePath).delete() }
                viewModel.clearPhotos()
                Toast.makeText(ctx, "已放弃 ${photos.size} 张照片", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("继续拍摄", null)
            .setCancelable(true)
            .show()
    }

    companion object {
        private const val TAG = "FreeCaptureFragment"
    }
}
