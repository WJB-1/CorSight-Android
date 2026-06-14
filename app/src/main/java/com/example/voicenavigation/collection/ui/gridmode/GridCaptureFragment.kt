package com.example.voicenavigation.collection.ui.gridmode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.example.voicenavigation.core.compass.CompassProvider
import com.example.voicenavigation.core.location.LocationProvider
import com.example.voicenavigation.collection.data.PhotoRecord
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * 八方向采集模式。
 *
 * 相机内显示方向提示，未对准时禁用快门。
 * 不固定从 N 开始，自动找第一个未拍方向。
 * 拍满 8 张后弹出保存对话框。
 */
@AndroidEntryPoint
class GridCaptureFragment : Fragment() {

    companion object {
        private const val TAG = "GridCaptureFragment"
        private const val ALIGN_TOLERANCE = 12f  // 对齐容差 ±12°
    }

    @Inject lateinit var compassProvider: CompassProvider
    @Inject lateinit var locationProvider: LocationProvider

    private val viewModel: GridCaptureViewModel by viewModels()

    private lateinit var previewView: PreviewView
    private lateinit var tvDebugOverlay: TextView
    private lateinit var tvDirectionHint: TextView
    private lateinit var directionProgress: LinearLayout
    private lateinit var shutterBtn: View

    private var imageCapture: ImageCapture? = null
    private var currentBearing: Float = 0f
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var isAligned = false

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
        tvDirectionHint = view.findViewById(R.id.tvDirectionHint)
        directionProgress = view.findViewById(R.id.directionProgress)
        shutterBtn = view.findViewById(R.id.shutterBtn)

        shutterBtn.setOnClickListener {
            if (isAligned) takePhoto()
        }
        setShutterEnabled(false)

        buildDirectionIndicators()

        // 观察已拍方向变化，更新进度指示器
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.capturedDirections.collectLatest { updateDirectionIndicators(it) }
        }
        // 观察当前目标方向
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentTarget.collectLatest { target ->
                target?.let { tvDirectionHint.text = "请对准 $it" }
            }
        }

        startCompass()
        startLocation()

        if (hasCameraPermission()) startCamera()
        else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    // ===== 方向指示器 =====

    private fun buildDirectionIndicators() {
        directionProgress.removeAllViews()
        GridCaptureViewModel.DIRECTIONS.forEach { dir ->
            val tv = TextView(requireContext()).apply {
                text = dir
                textSize = 12f
                setPadding(8, 4, 8, 4)
                tag = dir
                setBackgroundResource(android.R.drawable.editbox_background)
            }
            directionProgress.addView(tv)
        }
    }

    private fun updateDirectionIndicators(captured: Set<String>) {
        for (i in 0 until directionProgress.childCount) {
            val tv = directionProgress.getChildAt(i) as TextView
            val dir = tv.tag as String
            if (dir in captured) {
                tv.setBackgroundColor(Color.parseColor("#4CAF50"))
                tv.setTextColor(Color.WHITE)
            } else {
                tv.setBackgroundColor(Color.parseColor("#333333"))
                tv.setTextColor(Color.GRAY)
            }
        }
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

    // ===== 传感器 =====

    private fun startCompass() {
        viewLifecycleOwner.lifecycleScope.launch {
            compassProvider.observe().collectLatest { heading ->
                currentBearing = heading.heading
                checkAlignment()
                updateDebugOverlay()
            }
        }
    }

    private fun startLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            locationProvider.observe(3000L).collectLatest { loc ->
                currentLat = loc.latitude
                currentLng = loc.longitude
            }
        }
    }

    private fun checkAlignment() {
        val target = viewModel.currentTarget.value ?: return
        val targetAngle = GridCaptureViewModel.DIRECTION_ANGLES[target] ?: return

        var diff = abs(currentBearing - targetAngle)
        if (diff > 180) diff = 360 - diff

        val wasAligned = isAligned
        isAligned = diff <= ALIGN_TOLERANCE

        if (isAligned) {
            tvDirectionHint.text = "已对准 $target ✓"
            tvDirectionHint.setTextColor(Color.parseColor("#4CAF50"))
            setShutterEnabled(true)
            // 首次对准时振动
            if (!wasAligned) {
                val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            tvDirectionHint.text = "请对准 $target"
            tvDirectionHint.setTextColor(Color.parseColor("#FF9800"))
            setShutterEnabled(false)
        }
    }

    private fun setShutterEnabled(enabled: Boolean) {
        shutterBtn.alpha = if (enabled) 1.0f else 0.3f
        shutterBtn.isClickable = enabled
    }

    private fun updateDebugOverlay() {
        tvDebugOverlay.text = String.format(
            "Bearing: %.1f°\nGPS: %.6f, %.6f\nTarget: %s",
            currentBearing, currentLat, currentLng,
            viewModel.currentTarget.value ?: "-"
        )
    }

    // ===== 拍照 =====

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val target = viewModel.currentTarget.value ?: return

        val file = File(requireContext().filesDir, "grid_${target}_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

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
                // 删除已拍文件
                viewModel.photos.value.forEach { File(it.filePath).delete() }
                Toast.makeText(ctx, "已放弃", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }
}
