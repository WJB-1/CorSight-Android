package com.example.voicenavigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.corsight.inference.ModelRegistry
import com.corsight.vision.Frame
import com.corsight.vision.ToolRegistry
import com.corsight.vision.ToolResult
import com.corsight.vision.tools.GenericDetectionTool
import com.example.voicenavigation.databinding.ActivityVisionTestBinding
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VisionTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisionTestBinding
    private lateinit var cameraExecutor: ExecutorService
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    companion object {
        private const val TAG = "VisionTest"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            setupAnalyzer(imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupAnalyzer(imageAnalysis: ImageAnalysis) {
        // 注册并激活通用检测工具
        ToolRegistry.register(GenericDetectionTool())
        ToolRegistry.activate(this, "generic_detection")

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmap = imageProxy.toBitmap()
            val frame = Frame(bitmap, imageProxy.imageInfo.rotationDegrees)

            val result = ToolRegistry.activeTool.value?.process(frame)

            runOnUiThread {
                when (result) {
                    is ToolResult.Detections -> {
                        val top3 = result.items.take(3)
                        val text = if (top3.isEmpty()) {
                            "未检测到目标"
                        } else {
                            top3.joinToString("\n") {
                                "${it.label}: ${(it.score * 100).toInt()}%"
                            }
                        }
                        binding.tvDetections.text = text
                    }
                    else -> {
                        binding.tvDetections.text = "处理中..."
                    }
                }
            }

            imageProxy.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ToolRegistry.releaseAll()
        ModelRegistry.releaseAll()
        scope.cancel()
    }
}
