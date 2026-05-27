package com.example.voicenavigation.collection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.example.voicenavigation.R
import com.example.voicenavigation.network.TripPreviewService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.*

class DataCollectionActivity : AppCompatActivity() {

    private val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private var targetDirection = "N"
    private val capturedStatus = mutableMapOf<String, Boolean>()
    private val imagePaths = mutableListOf<Pair<String, String>>()

    private lateinit var compassService: CompassService
    private lateinit var taskStorage: TaskStorage
    private var locationClient: AMapLocationClient? = null

    private var currentLat = 0.0
    private var currentLon = 0.0
    private var chunkId = "未计算"

    private lateinit var tvHeading: TextView
    private lateinit var tvCurrentDir: TextView
    private lateinit var tvTargetDir: TextView
    private lateinit var tvAligned: TextView
    private lateinit var btnCapture: Button
    private lateinit var gridDirections: GridLayout
    private lateinit var etSceneDesc: EditText
    private lateinit var tvChunkId: TextView
    private lateinit var tvCoords: TextView
    private lateinit var btnSync: Button

    private val LOCATION_PERMISSION = 200
    private val CAMERA_REQUEST = 201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_collection)

        title = "数据采集"

        compassService = CompassService(this)
        taskStorage = TaskStorage(this)

        initViews()
        initCaptureStatus()
        checkLocationPermission()
    }

    private fun initViews() {
        tvHeading = findViewById(R.id.tvHeading)
        tvCurrentDir = findViewById(R.id.tvCurrentDir)
        tvTargetDir = findViewById(R.id.tvTargetDir)
        tvAligned = findViewById(R.id.tvAligned)
        btnCapture = findViewById(R.id.btnCapture)
        gridDirections = findViewById(R.id.gridDirections)
        etSceneDesc = findViewById(R.id.etSceneDesc)
        tvChunkId = findViewById(R.id.tvChunkId)
        tvCoords = findViewById(R.id.tvCoords)
        btnSync = findViewById(R.id.btnSync)

        btnCapture.setOnClickListener { takePhoto() }
        btnSync.setOnClickListener { syncToCloud() }
        findViewById<Button>(R.id.btnViewTasks).setOnClickListener { viewTasks() }
        findViewById<Button>(R.id.btnRefreshLocation).setOnClickListener { refreshLocation() }

        updateSyncButton()
    }

    private fun initCaptureStatus() {
        directions.forEach { capturedStatus[it] = false }
        updateGridColors()
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            initLocation()
            initCompass()
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION)
        }
    }

    private fun initLocation() {
        try {
            locationClient = AMapLocationClient(this)
            val option = AMapLocationClientOption()
            option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            option.isOnceLocation = true
            locationClient?.setLocationOption(option)
            locationClient?.setLocationListener(AMapLocationListener { location ->
                if (location != null && location.errorCode == 0) {
                    updateLocation(location.latitude, location.longitude)
                }
            })
            locationClient?.startLocation()
        } catch (e: Exception) {
            Toast.makeText(this, "定位初始化失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshLocation() {
        Toast.makeText(this, "刷新位置...", Toast.LENGTH_SHORT).show()
        initLocation()
    }

    private fun updateLocation(lat: Double, lon: Double) {
        currentLat = lat
        currentLon = lon
        chunkId = GridUtils.getChunkId(currentLat, currentLon)

        tvChunkId.text = chunkId
        tvCoords.text = String.format(Locale.US, "%.6f, %.6f", currentLat, currentLon)
    }

    private var lastAlignedState = false

    private fun initCompass() {
        compassService.start { heading, direction, isAligned ->
            tvHeading.text = "${heading.toInt()}°"
            tvCurrentDir.text = direction
            tvTargetDir.text = targetDirection

            if (isAligned) {
                tvAligned.text = "✓ 已对准"
                tvAligned.setTextColor(Color.parseColor("#4CAF50"))
                btnCapture.isEnabled = true
                btnCapture.setBackgroundColor(ContextCompat.getColor(this, R.color.vision_green))

                // 首次进入对准状态时触发振动
                if (!lastAlignedState) {
                    vibrate()
                    lastAlignedState = true
                }
            } else {
                tvAligned.text = ""
                btnCapture.isEnabled = false
                btnCapture.setBackgroundColor(ContextCompat.getColor(this, R.color.gray))
                lastAlignedState = false
            }

            btnCapture.text = "拍摄 ${targetDirection} 方向"
        }
        compassService.setTargetDirection(targetDirection)
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    it.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(150)
                }
            }
        } catch (e: Exception) {
            // 振动失败不影响主流程
        }
    }

    private fun takePhoto() {
        if (!capturedStatus.containsKey(targetDirection) || capturedStatus[targetDirection] == true) {
            Toast.makeText(this, "该方向已拍摄", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, CAMERA_REQUEST)
        } else {
            Toast.makeText(this, "相机不可用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST && resultCode == RESULT_OK) {
            val bitmap = data?.extras?.get("data") as? android.graphics.Bitmap ?: return

            val file = File(filesDir, "capture_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }

            imagePaths.add(Pair(targetDirection, file.absolutePath))
            capturedStatus[targetDirection] = true
            updateGridColors()

            if (imagePaths.size == 8) {
                Toast.makeText(this, "一组采集完成", Toast.LENGTH_LONG).show()
                saveCaptureTask()
            } else {
                switchTarget()
            }
        }
    }

    private fun switchTarget() {
        val currentIndex = directions.indexOf(targetDirection)
        val newStatus = capturedStatus.toMutableMap()
        var nextIndex = (currentIndex + 1) % 8
        while (newStatus[directions[nextIndex]] == true && nextIndex != currentIndex) {
            nextIndex = (nextIndex + 1) % 8
        }

        if (nextIndex == currentIndex) {
            Toast.makeText(this, "8个方向已完成", Toast.LENGTH_SHORT).show()
            return
        }

        targetDirection = directions[nextIndex]
        compassService.setTargetDirection(targetDirection)
        updateGridColors()
    }

    private fun updateGridColors() {
        for (i in 0 until gridDirections.childCount) {
            val child = gridDirections.getChildAt(i) as TextView
            val dir = child.text.toString()
            when {
                capturedStatus[dir] == true -> {
                    child.setBackgroundColor(Color.parseColor("#4CAF50"))
                    child.setTextColor(Color.WHITE)
                }
                dir == targetDirection -> {
                    child.setBackgroundColor(Color.parseColor("#FF9800"))
                    child.setTextColor(Color.WHITE)
                }
                else -> {
                    child.setBackgroundColor(Color.parseColor("#EEEEEE"))
                    child.setTextColor(Color.BLACK)
                }
            }
        }
    }

    private fun saveCaptureTask() {
        val pointId = "P_${System.currentTimeMillis()}_${Random().nextInt(99999).toString().padStart(5, '0')}"
        val imagesMap = mutableMapOf<String, String>()
        imagePaths.forEach { (dir, path) ->
            imagesMap[dir] = path
        }

        val task = CaptureTask(
            pointId = pointId,
            chunkId = chunkId,
            latitude = currentLat,
            longitude = currentLon,
            sceneDescription = etSceneDesc.text.toString().ifEmpty { "未描述" },
            images = imagesMap
        )

        taskStorage.saveTask(task)
        Toast.makeText(this, "任务已保存", Toast.LENGTH_SHORT).show()

        imagePaths.clear()
        initCaptureStatus()
        targetDirection = "N"
        compassService.setTargetDirection("N")
        etSceneDesc.setText("")
        updateSyncButton()
    }

    private fun updateSyncButton() {
        val pending = taskStorage.getPendingTasks().size
        btnSync.text = "同步到云端 ($pending)"
    }

    private fun syncToCloud() {
        val pending = taskStorage.getPendingTasks()
        if (pending.isEmpty()) {
            Toast.makeText(this, "没有待上传任务", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("同步确认")
            .setMessage("确定要上传 ${pending.size} 个任务吗？")
            .setPositiveButton("确定") { _, _ ->
                doSync(pending)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSync(tasks: List<CaptureTask>) {
        val prefs = getSharedPreferences("corsight_config", MODE_PRIVATE)
        val baseUrl = prefs.getString("server_base_url", TripPreviewService.DEFAULT_BASE_URL)

        val uploadService = UploadService(baseUrl ?: TripPreviewService.DEFAULT_BASE_URL)

        Toast.makeText(this, "开始同步...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            var lastErrorMsg = ""
            for (task in tasks) {
                val ok = uploadService.uploadTask(task)
                if (ok) {
                    taskStorage.updateStatus(task.pointId, "success")
                    successCount++
                } else {
                    taskStorage.updateStatus(task.pointId, "failed")
                    lastErrorMsg = uploadService.lastError
                }
            }
            withContext(Dispatchers.Main) {
                val msg = if (successCount == tasks.size) {
                    "上传成功 $successCount/${tasks.size}"
                } else {
                    "上传 $successCount/${tasks.size}, 失败: $lastErrorMsg"
                }
                Toast.makeText(this@DataCollectionActivity, msg, Toast.LENGTH_LONG).show()
                updateSyncButton()
            }
        }
    }

    private fun viewTasks() {
        val tasks = taskStorage.getAllTasks()
        if (tasks.isEmpty()) {
            Toast.makeText(this, "暂无任务", Toast.LENGTH_SHORT).show()
            return
        }

        val items = tasks.map { t ->
            "${t.pointId}\n${t.chunkId} | ${t.status} | ${t.images.size}/8张"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("本地任务 (${tasks.size})")
            .setItems(items) { _, _ -> }
            .setPositiveButton("清空已完成") { _, _ ->
                taskStorage.clearSuccessTasks()
                updateSyncButton()
                Toast.makeText(this, "已清空已完成任务", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initLocation()
                    initCompass()
                } else {
                    Toast.makeText(this, "需要位置权限", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::compassService.isInitialized && !compassService.isRunning) {
            initCompass()
        }
        updateSyncButton()
    }

    override fun onPause() {
        super.onPause()
        if (::compassService.isInitialized) {
            compassService.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationClient?.onDestroy()
    }
}
