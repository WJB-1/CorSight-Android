package com.example.voicenavigation.collection.ui.hub

import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.voicenavigation.R
import com.example.voicenavigation.collection.ui.dashboard.DashboardFragment
import com.example.voicenavigation.collection.ui.freemode.FreeCaptureFragment
import com.example.voicenavigation.collection.ui.gridmode.GridCaptureFragment
import com.example.voicenavigation.core.compass.CompassProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 采样工具主入口 Activity。
 *
 * 三个 Tab：自由采集 · 八方向 · 后台管理
 * 处理屏幕旋转并将旋转事件转发给 CompassProvider。
 */
@AndroidEntryPoint
class CaptureHubActivity : AppCompatActivity() {

    @Inject lateinit var compassProvider: CompassProvider

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

    private fun switchFragment(fragment: Fragment): Boolean {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        return true
    }
}
