package com.example.voicenavigation.collection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.example.voicenavigation.AppConfig
import com.example.voicenavigation.BuildConfig
import com.example.voicenavigation.R
import com.example.voicenavigation.network.TripPreviewService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.Random

class DataCollectionActivity : AppCompatActivity() {

    private val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private val capturedStatus = mutableMapOf<String, Boolean>()
    private val imagePaths = mutableListOf<Pair<String, String>>()

    private lateinit var compassService: CompassService
    private lateinit var taskStorage: TaskStorage
    private var locationClient: AMapLocationClient? = null
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var chunkId = "未计算"
    private var targetDirection = "N"
    private var lastAlignedState = false
    private var pendingPhotoFile: File? = null
    private var retakeDirection: String? = null
    private var previewDialog: AlertDialog? = null
    private var pendingSceneDesc: String = ""

    private lateinit var tvHeading: TextView
    private lateinit var tvCurrentDir: TextView
    private lateinit var tvTargetDir: TextView
    private lateinit var tvAligned: TextView
    private lateinit var btnCapture: Button
    private lateinit var gridDirections: GridLayout
    private lateinit var tvChunkId: TextView
    private lateinit var tvCoords: TextView
    private lateinit var btnSync: Button

    companion object {
        private const val LOCATION_PERMISSION = 200
        private const val CAMERA_REQUEST = 201
        private const val RETAKE_REQUEST = 202
        private const val CAMERA_PERMISSION = 203
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_collection)
        title = "数据采集"

        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        if (hasValidAmapKey()) {
            MapsInitializer.setApiKey(BuildConfig.AMAP_API_KEY)
            AMapLocationClient.setApiKey(BuildConfig.AMAP_API_KEY)
            ServiceSettings.getInstance().setApiKey(BuildConfig.AMAP_API_KEY)
        }

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
        tvChunkId = findViewById(R.id.tvChunkId)
        tvCoords = findViewById(R.id.tvCoords)
        btnSync = findViewById(R.id.btnSync)

        btnCapture.setOnClickListener { takePhoto(CAMERA_REQUEST, targetDirection) }
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
        if (!hasValidAmapKey()) {
            Toast.makeText(this, "高德Key未配置，无法定位采集点", Toast.LENGTH_LONG).show()
            initCompass()
            return
        }
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), LOCATION_PERMISSION)
        } else {
            initLocation()
            initCompass()
        }
    }

    private fun initLocation() {
        if (!hasValidAmapKey()) {
            Toast.makeText(this, "高德Key未配置，无法刷新位置", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            locationClient = AMapLocationClient(this)
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
            }
            locationClient?.setLocationOption(option)
            locationClient?.setLocationListener { location ->
                if (location != null && location.errorCode == 0) {
                    updateLocation(location.latitude, location.longitude)
                }
            }
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

    private fun initCompass() {
        compassService.start { heading, direction, isAligned ->
            tvHeading.text = "${heading.toInt()}°"
            tvCurrentDir.text = direction
            tvTargetDir.text = targetDirection

            if (isAligned) {
                tvAligned.text = "已对准"
                tvAligned.setTextColor(Color.parseColor("#4CAF50"))
                btnCapture.isEnabled = true
                btnCapture.setBackgroundColor(ContextCompat.getColor(this, R.color.vision_green))
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

            btnCapture.text = "拍摄 $targetDirection 方向"
        }
        compassService.setTargetDirection(targetDirection)
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    private fun takePhoto(requestCode: Int, direction: String) {
        if (requestCode == CAMERA_REQUEST && capturedStatus[direction] == true) {
            Toast.makeText(this, "该方向已拍摄", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
            return
        }

        val file = File(filesDir, "capture_${System.currentTimeMillis()}.jpg")
        pendingPhotoFile = file
        retakeDirection = if (requestCode == RETAKE_REQUEST) direction else null

        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, requestCode)
        } else {
            pendingPhotoFile = null
            retakeDirection = null
            Toast.makeText(this, "相机不可用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            pendingPhotoFile = null
            retakeDirection = null
            return
        }

        val file = pendingPhotoFile
        pendingPhotoFile = null
        if (file == null || !file.exists() || file.length() == 0L) {
            retakeDirection = null
            Toast.makeText(this, "拍照失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }

        if (requestCode == RETAKE_REQUEST) {
            val direction = retakeDirection ?: return
            retakeDirection = null
            val index = imagePaths.indexOfFirst { it.first == direction }
            if (index >= 0) {
                imagePaths[index] = direction to file.absolutePath
            }
            previewDialog?.findViewById<GridLayout>(R.id.previewGrid)?.let { refreshPreviewGrid(it) }
            return
        }

        imagePaths.add(targetDirection to file.absolutePath)
        capturedStatus[targetDirection] = true
        updateGridColors()

        if (imagePaths.size == directions.size) {
            showPreviewDialog()
        } else {
            switchTarget()
        }
    }

    private fun switchTarget() {
        val currentIndex = directions.indexOf(targetDirection)
        var nextIndex = (currentIndex + 1) % directions.size
        while (capturedStatus[directions[nextIndex]] == true && nextIndex != currentIndex) {
            nextIndex = (nextIndex + 1) % directions.size
        }
        if (nextIndex == currentIndex) {
            Toast.makeText(this, "8 个方向已完成", Toast.LENGTH_SHORT).show()
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
        imagePaths.forEach { (dir, path) -> imagesMap[dir] = path }

        val task = CaptureTask(
            pointId = pointId,
            chunkId = chunkId,
            latitude = currentLat,
            longitude = currentLon,
            sceneDescription = pendingSceneDesc.ifEmpty { "未描述" },
            images = imagesMap
        )

        taskStorage.saveTask(task)
        Toast.makeText(this, "任务已保存", Toast.LENGTH_SHORT).show()
        imagePaths.clear()
        initCaptureStatus()
        targetDirection = "N"
        compassService.setTargetDirection(targetDirection)
        pendingSceneDesc = ""
        updateSyncButton()
    }

    private fun updateSyncButton() {
        val pending = taskStorage.getPendingTasks().size
        btnSync.text = "同步到云端($pending)"
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
            .setPositiveButton("确定") { _, _ -> doSync(pending) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSync(tasks: List<CaptureTask>) {
        val prefs = AppConfig.prefs(this)
        val baseUrl = AppConfig.normalizeBaseUrl(
            prefs.getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL)
        )
        if (baseUrl.isEmpty()) {
            Toast.makeText(this, "请先在设置中填写后端服务地址", Toast.LENGTH_SHORT).show()
            return
        }
        val uploadService = UploadService(baseUrl)

        Toast.makeText(this, "开始同步...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            var lastError = ""
            for (task in tasks) {
                try {
                    if (uploadService.uploadTask(task)) {
                        taskStorage.updateStatus(task.pointId, "success")
                        successCount++
                    } else {
                        taskStorage.updateStatus(task.pointId, "failed")
                        lastError = uploadService.lastError
                    }
                } catch (e: Exception) {
                    Log.e("DataCollection", "Upload task ${task.pointId} failed", e)
                    taskStorage.updateStatus(task.pointId, "failed")
                    lastError = e.message ?: "未知错误"
                }
            }
            withContext(Dispatchers.Main) {
                val message = if (successCount == tasks.size) {
                    "上传成功 $successCount/${tasks.size}"
                } else {
                    "上传 $successCount/${tasks.size}，失败：$lastError"
                }
                Toast.makeText(this@DataCollectionActivity, message, Toast.LENGTH_LONG).show()
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

        val items = tasks.map { task ->
            "${task.pointId}\n${task.chunkId} | ${task.status} | ${task.images.size}/8张"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("本地任务 (${tasks.size})")
            .setItems(items) { _, _ -> }
            .setPositiveButton("清空已完成") { _, _ ->
                taskStorage.clearSuccessTasks()
                updateSyncButton()
                Toast.makeText(this, "已清空完成任务", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION -> {
                var locationGranted = false
                var cameraGranted = false
                permissions.forEachIndexed { index, perm ->
                    if (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED) {
                        when (perm) {
                            Manifest.permission.ACCESS_FINE_LOCATION -> locationGranted = true
                            Manifest.permission.CAMERA -> cameraGranted = true
                        }
                    }
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) locationGranted = true
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) cameraGranted = true

                if (locationGranted) {
                    initLocation()
                    initCompass()
                } else {
                    showPermissionDeniedDialog("位置权限", "需要位置权限来获取当前坐标")
                }
                if (!cameraGranted) {
                    showPermissionDeniedDialog("相机权限", "需要相机权限来拍摄街景照片")
                }
            }
            CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (retakeDirection != null) {
                        takePhoto(RETAKE_REQUEST, retakeDirection!!)
                    } else {
                        takePhoto(CAMERA_REQUEST, targetDirection)
                    }
                } else {
                    showPermissionDeniedDialog("相机权限", "需要相机权限来拍摄街景照片")
                }
            }
        }
    }

    private fun showPermissionDeniedDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("$title 未授予")
            .setMessage("$message。请在系统设置中开启权限。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
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

    private fun showPreviewDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_preview, null)
        val grid = dialogView.findViewById<GridLayout>(R.id.previewGrid)
        refreshPreviewGrid(grid)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        previewDialog = dialog

        dialogView.findViewById<Button>(R.id.btnPreviewCancel).setOnClickListener {
            dialog.dismiss()
            previewDialog = null
            imagePaths.forEach { File(it.second).delete() }
            imagePaths.clear()
            initCaptureStatus()
            targetDirection = "N"
            compassService.setTargetDirection(targetDirection)
            Toast.makeText(this, "已放弃，请重新采集", Toast.LENGTH_SHORT).show()
        }

        dialogView.findViewById<Button>(R.id.btnPreviewSave).setOnClickListener {
            val descInput = dialogView.findViewById<EditText>(R.id.etPreviewSceneDesc)
            pendingSceneDesc = descInput?.text?.toString()?.trim() ?: ""
            dialog.dismiss()
            previewDialog = null
            saveCaptureTask()
        }

        dialog.show()
    }

    private fun refreshPreviewGrid(grid: GridLayout) {
        grid.removeAllViews()
        val margin = (8 * resources.displayMetrics.density).toInt()
        val size = ((resources.displayMetrics.widthPixels - 48 * resources.displayMetrics.density) / 2).toInt()

        for ((dir, path) in imagePaths) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    setMargins(margin, margin, margin, margin)
                }
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, (size * 0.75).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(Uri.fromFile(File(path)))
                setOnClickListener { takePhoto(RETAKE_REQUEST, dir) }
            }
            val label = TextView(this).apply {
                text = dir
                textSize = 14f
                setTextColor(Color.parseColor("#666666"))
                gravity = android.view.Gravity.CENTER
            }
            container.addView(image)
            container.addView(label)
            grid.addView(container)
        }
    }

    private fun hasValidAmapKey(): Boolean {
        return BuildConfig.AMAP_API_KEY != null && BuildConfig.AMAP_API_KEY.trim().isNotEmpty()
    }
}
