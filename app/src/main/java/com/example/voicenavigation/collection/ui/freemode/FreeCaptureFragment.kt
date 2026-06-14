package com.example.voicenavigation.collection.ui.freemode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
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
import com.example.voicenavigation.core.camera.CaptureMetadata
import com.example.voicenavigation.core.compass.CompassProvider
import com.example.voicenavigation.core.location.LocationProvider
import com.example.voicenavigation.collection.data.PhotoRecord
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 自由拍照模式。
 *
 * 无方向约束，用户按需拍摄有意义的街景（天桥、复杂路口等）。
 * 拍照后弹出标注对话框，选择场景类型和描述。
 */
@AndroidEntryPoint
class FreeCaptureFragment : Fragment() {

    @Inject lateinit var compassProvider: CompassProvider
    @Inject lateinit var locationProvider: LocationProvider

    private val viewModel: FreeCaptureViewModel by viewModels()

    private lateinit var previewView: PreviewView
    private lateinit var tvDebugOverlay: TextView
    private lateinit var tvPhotoCount: TextView
    private lateinit var shutterBtn: View

    private var imageCapture: ImageCapture? = null
    private var currentBearing: Float = 0f
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var currentFovX: Float = 90f

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
        shutterBtn = view.findViewById(R.id.shutterBtn)

        shutterBtn.setOnClickListener { takePhoto() }

        // 观察照片数量
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photos.collectLatest { photos ->
                tvPhotoCount.text = "已拍: ${photos.size}"
            }
        }

        // 启动传感器
        startCompass()
        startLocation()

        // 启动相机
        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
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
                // 获取 FOV
                val camInfo = provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview
                ).cameraInfo
                // CameraX 不直接暴露 FOV，用默认值
                currentFovX = 90f
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun startCompass() {
        viewLifecycleOwner.lifecycleScope.launch {
            compassProvider.observe().collectLatest { heading ->
                currentBearing = heading.heading
                updateDebugOverlay()
            }
        }
    }

    private fun startLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            locationProvider.observe(3000L).collectLatest { loc ->
                currentLat = loc.latitude
                currentLng = loc.longitude
                updateDebugOverlay()
            }
        }
    }

    private fun updateDebugOverlay() {
        tvDebugOverlay.visibility = View.VISIBLE
        tvDebugOverlay.text = String.format(
            "Bearing: %.1f°\nGPS: %.6f, %.6f\nFOV: %.0f°",
            currentBearing, currentLat, currentLng, currentFovX
        )
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val file = File(requireContext().filesDir, "free_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

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
                        zoomRatio = 1f,
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

    companion object {
        private const val TAG = "FreeCaptureFragment"
    }
}
