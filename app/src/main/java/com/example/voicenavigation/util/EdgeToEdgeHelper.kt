package com.example.voicenavigation.util

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-Edge 适配工具。
 *
 * 使用方式：
 * ```kotlin
 * // onCreate 中，setContentView 之后
 * EdgeToEdgeHelper.apply(this)
 * EdgeToEdgeHelper.padStatusBar(myTopBar)  // 给需要避开状态栏的 View 加内边距
 * ```
 */
object EdgeToEdgeHelper {

    /**
     * 启用 Edge-to-Edge 显示 + 白色状态栏图标。
     * 在 Activity.onCreate() 的 setContentView() 之前或之后调用均可。
     */
    fun apply(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = false
    }

    /**
     * 给 View 的顶部加上状态栏高度的 padding，使其内容不被状态栏遮挡。
     * 适用于工具栏、标题栏、header 等固定在顶部的元素。
     */
    fun padStatusBar(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = insets.top)
            windowInsets
        }
    }

    /**
     * 给 View 的底部加上导航栏高度的 padding，使其内容不被导航栏遮挡。
     * 适用于底部按钮栏、BottomSheet 等固定在底部的元素。
     */
    fun padNavigationBar(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    /**
     * 同时给顶部和底部加 padding。适用于全屏内容区域。
     */
    fun padSystemBars(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
            v.updatePadding(top = insets.top, bottom = insets.bottom)
            windowInsets
        }
    }
}
