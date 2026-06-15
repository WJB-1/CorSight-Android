package com.example.voicenavigation.collection.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicenavigation.R
import com.example.voicenavigation.animation.ShutterAnimations
import com.example.voicenavigation.core.camera.CaptureMetadata
import com.example.voicenavigation.core.compass.CompassProvider
import com.example.voicenavigation.core.location.LocationProvider
import com.example.voicenavigation.collection.data.PhotoRecord
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 补拍 Fragment。
 *
 * 功能：
 * 1. 获取当前位置，与原采样点做 Haversine 距离校验（定位锁）
 * 2. 距离 > 50m 或 GPS 精度不足时禁用快门
 * 3. 拍照成功后替换原 PhotoRecord
 */
@AndroidEntryPoint
class RetakeFragment : Fragment() {

    companion object {
        private const val TAG = "RetakeFragment"
        private const val ARG_TASK_ID = "task_id"
        private const val ARG_PHOTO_ID = "photo_id"
        private const val ARG_ORIG_BEARING = "orig_bearing"

        fun newInstance(taskId: String, photoId: String, origBearing: Float): RetakeFragment {
            return RetakeFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TASK_ID, taskId)
                    putString(ARG_PHOTO_ID, photoId)
                    putFloat(ARG_ORIG_BEARING, origBearing)
                }
            }
        }
    }

    @Inject lateinit var compassProvider: CompassProvider
    @Inject lateinit var locationProvider: LocationProvider

    private val viewModel: DashboardViewModel by viewModels({ requireParentFragment() })

    private lateinit var previewView: PreviewView
    private lateinit var tvRetakeInfo: TextView
    private lateinit var tvLocationCheck: TextView
    private lateinit var shutterBtn: View
    private lateinit var shutterFlashOverlay: View

    private var imageCapture: ImageCapture? = null
    private var currentBearing: Float = 0f
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0

    private var taskId: String = ""
    private var photoId: String = ""
    private var origBearing: Float = 0f
    private var canCapture = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(requireContext(), "需要相机权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = arguments?.getString(ARG_TASK_ID) ?: ""
        photoId = arguments?.getString(ARG_PHOTO_ID) ?: ""
        origBearing = arguments?.getFloat(ARG_ORIG_BEARING) ?: 0f
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_retake, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        tvRetakeInfo = view.findViewById(R.id.tvRetakeInfo)
        tvLocationCheck = view.findViewById(R.id.tvLocationCheck)
        shutterBtn = view.findViewById(R.id.shutterBtn)
        shutterFlashOverlay = view.findViewById(R.id.shutterFlashOverlay)

        tvRetakeInfo.text = "补拍 — 原方位角: ${String.format("%.1f", origBearing)}°"
        shutterBtn.setOnClickListener {
            if (canCapture) takePhoto()
        }
        // 快门按压反馈动画
        shutterBtn.setOnTouchListener { v, event ->
            ShutterAnimations.onTouchEvent(v, event)
            false
        }
        setShutterEnabled(false)

        startCompass()
        startLocationCheck()

        if (hasCameraPermission()) startCamera()
        else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(1920, 1080))
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun startCompass() {
        viewLifecycleOwner.lifecycleScope.launch {
            compassProvider.observe().collectLatest { heading ->
                currentBearing = heading.heading
            }
        }
    }

    /**
     * 定位锁：获取当前位置 → 校验精度 → 计算距离 → 决定是否允许拍摄
     */
    private fun startLocationCheck() {
        viewLifecycleOwner.lifecycleScope.launch {
            tvLocationCheck.text = "正在校验位置..."
            tvLocationCheck.setTextColor(android.graphics.Color.WHITE)

            val task = viewModel.taskStorage.getTask(taskId)
            if (task == null) {
                tvLocationCheck.text = "任务不存在"
                tvLocationCheck.setTextColor(android.graphics.Color.RED)
                return@launch
            }

            val current = locationProvider.getLastLocation(DashboardViewModel.RETAKE_LOCATION_TIMEOUT_MS)
            if (current == null) {
                tvLocationCheck.text = "⚠ 无法获取 GPS，请检查信号"
                tvLocationCheck.setTextColor(android.graphics.Color.RED)
                setShutterEnabled(false)
                return@launch
            }

            currentLat = current.latitude
            currentLng = current.longitude

            // 精度校验
            if (current.accuracy > DashboardViewModel.RETAKE_LOCATION_ACCURACY_M) {
                tvLocationCheck.text = "⚠ GPS 精度不足（${current.accuracy.toInt()}m），请等待信号稳定"
                tvLocationCheck.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                setShutterEnabled(false)
                return@launch
            }

            // 距离校验
            val distance = haversine(task.latitude, task.longitude, currentLat, currentLng)
            if (distance > DashboardViewModel.RETAKE_MAX_DISTANCE_M) {
                tvLocationCheck.text = "🚫 距采集点 ${distance.toInt()}m，超出允许范围（${DashboardViewModel.RETAKE_MAX_DISTANCE_M.toInt()}m）\n请返回采集点附近"
                tvLocationCheck.setTextColor(android.graphics.Color.RED)
                setShutterEnabled(false)
                canCapture = false
            } else {
                tvLocationCheck.text = "✓ 距采集点 ${distance.toInt()}m，可以补拍"
                tvLocationCheck.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                setShutterEnabled(true)
                canCapture = true
            }
        }
    }

    private fun setShutterEnabled(enabled: Boolean) {
        ShutterAnimations.setEnabled(shutterBtn, enabled)
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val file = File(requireContext().filesDir, "retake_${photoId}_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        // 拍照反馈动画：心跳 + 闪光
        ShutterAnimations.onCapture(shutterBtn)
        ShutterAnimations.flashOverlay(shutterFlashOverlay)

        ic.takePicture(options, ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val newPhoto = PhotoRecord(
                        filePath = file.absolutePath,
                        bearing = currentBearing,
                        fov = 90f,
                        label = "补拍"
                    )
                    viewModel.replacePhoto(taskId, photoId, newPhoto)
                    Toast.makeText(requireContext(), "补拍成功，照片已替换", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Take photo failed", exc)
                    Toast.makeText(requireContext(), "拍照失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
        return R * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
}
