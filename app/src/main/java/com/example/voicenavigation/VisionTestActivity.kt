package com.example.voicenavigation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.corsight.inference.ModelRegistry
import com.corsight.vision.Frame
import com.corsight.vision.ImageSource
import com.corsight.vision.ToolRegistry
import com.corsight.vision.ToolResult
import com.corsight.vision.tools.GenericDetectionTool
import com.example.voicenavigation.databinding.ActivityVisionTestBinding
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class VisionTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisionTestBinding
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    private var currentSource: ImageSource? = null
    private val cameraSource by lazy {
        CameraSource(this, this, binding.previewView, cameraExecutor)
    }

    companion object {
        private const val TAG = "VisionTest"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val MODEL_INPUT_SIZE = 640
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ToolRegistry.register(GenericDetectionTool())
        ToolRegistry.activate(this, "generic_detection")

        setupUI()

        if (cameraSource.allPermissionsGranted()) {
            switchToSource(cameraSource)
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupUI() {
        binding.btnSourceCamera.setOnClickListener {
            if (cameraSource.allPermissionsGranted()) {
                switchToSource(cameraSource)
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        }

        binding.btnSourceNetwork.setOnClickListener {
            binding.layoutNetworkConfig.visibility = android.view.View.VISIBLE
        }

        binding.btnConnectStream.setOnClickListener {
            val text = binding.etStreamIp.text.toString().trim()
            val parts = text.split(":")
            val ip = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 8080 else 8080

            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newSource = NetworkSource(ip, port)
            switchToSource(newSource)
            binding.layoutNetworkConfig.visibility = android.view.View.GONE
        }
    }

    private fun switchToSource(source: ImageSource) {
        currentSource?.stop()
        currentSource = null

        binding.previewView.visibility =
            if (source is CameraSource) android.view.View.VISIBLE else android.view.View.GONE
        binding.ivNetwork.visibility =
            if (source !is CameraSource) android.view.View.VISIBLE else android.view.View.GONE

        binding.btnSourceCamera.setBackgroundColor(
            ContextCompat.getColor(this,
                if (source is CameraSource) R.color.purple_700 else R.color.gray))
        binding.btnSourceNetwork.setBackgroundColor(
            ContextCompat.getColor(this,
                if (source !is CameraSource) R.color.purple_700 else R.color.gray))

        val ok = source.start { bitmap, rotation -> processFrame(bitmap, rotation, source) }
        if (ok) {
            currentSource = source
            binding.tvDetections.text = "源已切换: ${source.displayName}"
        } else {
            Toast.makeText(this, "启动 ${source.displayName} 失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 统一帧处理入口。
     * 相机：直接走检测管线（CameraX 的 analyzer 已在后台线程）。
     * 网络流：先显示原图，再在后台线程跑推理，结果回来叠加。
     */
    private fun processFrame(bitmap: Bitmap, rotationDegrees: Int, source: ImageSource) {
        if (source is CameraSource) {
            // CameraX 已经在后台线程，直接推理
            val frame = Frame(bitmap, rotationDegrees)
            val result = ToolRegistry.activeTool.value?.process(frame)
            runOnUiThread { renderResult(result, bitmap, rotationDegrees) }
        } else {
            // 网络流：先显示原图，再异步推理
            runOnUiThread { binding.ivNetwork.setImageBitmap(bitmap) }

            inferenceExecutor.execute {
                val frame = Frame(bitmap, rotationDegrees)
                val result = ToolRegistry.activeTool.value?.process(frame)
                runOnUiThread { renderResult(result, bitmap, rotationDegrees) }
            }
        }
    }

    /** 渲染检测结果到 UI（必须在主线程调用） */
    private fun renderResult(result: ToolResult?, bitmap: Bitmap, rotationDegrees: Int) {
        when (result) {
            is ToolResult.Detections -> {
                val items = result.items
                binding.tvDetections.text = if (items.isEmpty()) {
                    "未检测到目标"
                } else {
                    items.take(3).joinToString("\n") {
                        "${it.label}: ${(it.score * 100).toInt()}%"
                    }
                }

                val previewW = binding.overlayView.width
                val previewH = binding.overlayView.height
                if (previewW > 0 && previewH > 0) {
                    binding.overlayView.setTransformations(
                        MODEL_INPUT_SIZE, previewW, previewH, rotationDegrees
                    )
                    binding.overlayView.updateDetections(items)
                }
            }
            else -> {
                binding.tvDetections.text = "处理中..."
                binding.overlayView.updateDetections(emptyList())
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                switchToSource(cameraSource)
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentSource?.stop()
        cameraExecutor.shutdown()
        inferenceExecutor.shutdown()
        ToolRegistry.releaseAll()
        ModelRegistry.releaseAll()
        scope.cancel()
    }
}
