package com.example.voicenavigation.collection.ui.hub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.voicenavigation.R
import com.example.voicenavigation.collection.ui.dashboard.DashboardFragment
import com.example.voicenavigation.collection.ui.freemode.FreeCaptureFragment
import com.example.voicenavigation.collection.ui.gridmode.GridCaptureFragment
import com.example.voicenavigation.core.compass.CompassProvider
import com.example.voicenavigation.core.location.LocationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 采样工具主入口 Activity。
 *
 * 三个 Tab：自由采集 · 八方向 · 后台管理
 * 处理屏幕旋转并将旋转事件转发给 CompassProvider。
 * 负责统一请求 CAMERA + LOCATION 权限。
 */
@AndroidEntryPoint
class CaptureHubActivity : AppCompatActivity() {

    @Inject lateinit var compassProvider: CompassProvider
    @Inject lateinit var locationProvider: LocationProvider

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val cameraGranted = perms[Manifest.permission.CAMERA] == true
        val locationGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true

        if (!cameraGranted) {
            AlertDialog.Builder(this)
                .setTitle("需要相机权限")
                .setMessage("采样功能需要使用相机，请在设置中授权")
                .setPositiveButton("确定", null)
                .show()
        }

        if (!locationGranted) {
            AlertDialog.Builder(this)
                .setTitle("需要位置权限")
                .setMessage("采样功能需要 GPS 定位，请在设置中授权")
                .setPositiveButton("确定", null)
                .show()
        }

        // 权限检查完成后，检查 GPS 开关
        if (locationGranted) {
            checkLocationServicesEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_hub)

        // 初始化罗盘的屏幕方向
        compassProvider.setScreenRotation(windowManager.defaultDisplay.rotation)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_free -> switchFragment(FreeCaptureFragment())
                R.id.nav_grid -> switchFragment(GridCaptureFragment())
                R.id.nav_dashboard -> switchFragment(DashboardFragment())
                else -> false
            }
        }

        // 请求所需权限
        requestPermissionsIfNeeded()

        // 默认选中自由采集
        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_free
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕旋转时通知罗盘更新坐标系映射
        compassProvider.setScreenRotation(windowManager.defaultDisplay.rotation)
    }

    // ===== 权限 =====

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            // 已有全部权限，检查 GPS 开关
            checkLocationServicesEnabled()
        }
    }

    private fun checkLocationServicesEnabled() {
        if (!locationProvider.isLocationEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("位置服务未开启")
                .setMessage("采样功能需要 GPS 定位，请开启位置服务")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ===== Fragment 切换 =====

    private fun switchFragment(fragment: Fragment): Boolean {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        return true
    }
}
