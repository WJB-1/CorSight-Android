package com.example.voicenavigation.collection.ui.hub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.voicenavigation.R
import com.example.voicenavigation.animation.PageIndicatorAnimations
import com.example.voicenavigation.core.compass.CompassProvider
import com.example.voicenavigation.core.location.LocationProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 采样工具主入口 Activity。
 *
 * ViewPager2 承载三个页面（自由采集 · 八方向 · 后台管理），
 * 底部小圆点指示器 + 点击显示页面名称。
 * 处理屏幕旋转并转发给 CompassProvider。
 * 负责统一请求 CAMERA + LOCATION 权限。
 */
@AndroidEntryPoint
class CaptureHubActivity : AppCompatActivity() {

    @Inject lateinit var compassProvider: CompassProvider
    @Inject lateinit var locationProvider: LocationProvider

    private lateinit var viewPager: ViewPager2
    private lateinit var dotsContainer: LinearLayout
    private lateinit var tvPageLabel: TextView

    private val dotViews = mutableListOf<View>()
    private var currentPage = 0

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

        if (locationGranted) {
            checkLocationServicesEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_hub)

        compassProvider.setScreenRotation(windowManager.defaultDisplay.rotation)

        viewPager = findViewById(R.id.viewPager)
        dotsContainer = findViewById(R.id.dotsContainer)
        tvPageLabel = findViewById(R.id.tvPageLabel)

        // ViewPager2 + Adapter
        val adapter = CapturePagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 2  // 保持 3 页全部存活

        // 圆点指示器
        buildDots(adapter.itemCount)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                updateDots(position)
            }
        })

        requestPermissionsIfNeeded()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        compassProvider.setScreenRotation(windowManager.defaultDisplay.rotation)
    }

    // ===== 圆点指示器 =====

    private fun buildDots(count: Int) {
        dotViews.clear()
        dotsContainer.removeAllViews()
        val dp = resources.displayMetrics.density
        val size = (10 * dp).toInt()
        val margin = (6 * dp).toInt()

        for (i in 0 until count) {
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.bg_dot)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, 0, margin, 0)
                }
                tag = i
                setOnClickListener { onDotClicked(i) }
            }
            dotViews.add(dot)
            dotsContainer.addView(dot)
        }
        updateDots(0)
    }

    private fun updateDots(position: Int) {
        PageIndicatorAnimations.onPageChanged(dotViews, position)
    }

    private fun onDotClicked(position: Int) {
        // 圆点点击心跳反馈
        dotViews.getOrNull(position)?.let { PageIndicatorAnimations.onDotClicked(it) }
        if (position != currentPage) {
            viewPager.setCurrentItem(position, true)
        }
        // 页面名称弹出动画（放大弹入 → 停留 → 淡出）
        PageIndicatorAnimations.showLabel(
            tvPageLabel,
            CapturePagerAdapter.PAGE_TITLES.getOrElse(position) { "" }
        )
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
}
