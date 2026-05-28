package com.example.voicenavigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.View
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class VisionTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisionTestBinding
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var destroyed = false
    private var currentSource: ImageSource? = null
    private val cameraSource by lazy {
        CameraSource(this, this, binding.previewView, cameraExecutor)
    }

    // UDP 自动发现相关
    private var udpSocket: DatagramSocket? = null
    private var udpReceiveThread: Thread? = null
    private val UDP_DISCOVERY_PORT = 8888          // 与 ESP32 广播端口一致
    private val AUTO_DISCOVERY_TIMEOUT_MS = 5000L  // 等待广播超时时间

    companion object {
        private const val TAG = "VisionTest"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val MODEL_INPUT_SIZE = 640
        private const val DEFAULT_STREAM_PORT = 8080
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ToolRegistry.register(GenericDetectionTool())
        ToolRegistry.activate(this, "generic_detection")

        setupUI()

        val useExternal = getSharedPreferences("corsight_config", MODE_PRIVATE)
            .getBoolean("use_external_device", false)

        if (useExternal) {
            // 优先尝试外设：启动 UDP 发现，超时后退回相机
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

    private fun startCameraOrRequestPermission() {
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

        // 网络流按钮：点击时展开配置面板，并同时启动自动发现（不阻塞UI）
        binding.btnSourceNetwork.setOnClickListener {
            if (binding.layoutNetworkConfig.visibility != View.VISIBLE) {
                binding.layoutNetworkConfig.visibility = View.VISIBLE
            }
            startUdpAutoDiscovery(
                onFound = { ip -> connectToNetworkSource(ip, DEFAULT_STREAM_PORT) },
                onTimeout = {
                    Toast.makeText(this, "未找到外设，请手动输入 IP", Toast.LENGTH_SHORT).show()
                }
            )
        }

        binding.btnConnectStream.setOnClickListener {
            val text = binding.etStreamIp.text.toString().trim()
            val parts = text.split(":")
            val ip = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: DEFAULT_STREAM_PORT else DEFAULT_STREAM_PORT

            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectToNetworkSource(ip, port)
        }
    }

    // ==================== UDP 自动发现 ====================

    /**
     * 启动 UDP 广播监听，等待 ESP32 发送身份消息。
     * @param onFound 发现设备后的回调（在主线程执行）
     * @param onTimeout 超时后的回调（在主线程执行）
     */
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

    /**
     * 解析广播消息中的 IP 地址
     * 支持格式: "ESP32_CAM IP=192.168.43.10 TCP=8080"
     */
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

        binding.previewView.visibility =
            if (source is CameraSource) View.VISIBLE else View.GONE
        binding.ivNetwork.visibility =
            if (source !is CameraSource) View.VISIBLE else View.GONE

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

    // ==================== 帧处理 ====================

    /**
     * 统一帧处理入口。
     * 相机：直接走检测管线（CameraX 的 analyzer 已在后台线程）。
     * 网络流：先显示原图，再在后台线程跑推理，结果回来叠加。
     */
    private fun processFrame(bitmap: Bitmap, rotationDegrees: Int, source: ImageSource) {
        if (destroyed) return
        if (source is CameraSource) {
            val frame = Frame(bitmap, rotationDegrees)
            val result = ToolRegistry.activeTool.value?.process(frame)
            runOnUiThread { renderResult(result, bitmap, rotationDegrees) }
        } else {
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

    // ==================== 权限与生命周期 ====================

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
        destroyed = true
        currentSource?.stop()
        currentSource = null
        scope.cancel()

        // 强制停止正在排队的推理任务，再等待完成
        cameraExecutor.shutdownNow()
        inferenceExecutor.shutdownNow()
        try {
            cameraExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
            inferenceExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}

        // 执行器全部停止后再释放 ORT 资源
        ToolRegistry.releaseAll()
        ModelRegistry.releaseAll()

        closeUdpSocket()
        udpReceiveThread?.interrupt()
        super.onDestroy()
    }
}