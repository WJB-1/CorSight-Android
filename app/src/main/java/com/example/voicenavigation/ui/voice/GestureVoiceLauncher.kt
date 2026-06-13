package com.example.voicenavigation.ui.voice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.example.voicenavigation.voice.VoiceInteractionManager

/**
 * 全局单指长按语音助手唤醒器。
 *
 * 用法：在任意 Activity 的 onCreate 中调用：
 * ```
 * GestureVoiceLauncher.attach(this, voiceInteractionManager)
 * ```
 *
 * 用户长按屏幕任意位置 500ms → 震动反馈 → 启动语音助手 COMMAND 模式。
 * 地图滑动/缩放等短按手势不受影响。
 */
object GestureVoiceLauncher {

    private const val TAG = "GestureVoiceLauncher"
    private const val LONG_PRESS_DURATION_MS = 500L

    @SuppressLint("StaticFieldLeak")
    private var attachedView: View? = null
    private var vibrator: Vibrator? = null
    private var voiceInteractionManager: VoiceInteractionManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private var longPressRunnable: Runnable? = null
    private var isLongPressing = false
    private var startX = 0f
    private var startY = 0f

    /**
     * 在 Activity 的根布局上附加长按语音唤醒。
     *
     * @param activity 目标 Activity
     * @param vim 语音交互管理器（Hilt 注入或手动传入）
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(activity: Activity, vim: VoiceInteractionManager) {
        voiceInteractionManager = vim
        vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        attachToView(rootView)
    }

    /**
     * 直接在指定 View 上附加（用于 Fragment 等场景）。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachToView(view: View) {
        detach()

        attachedView = view
        view.setOnTouchListener { _, event ->
            handleTouchEvent(event)
        }
        Log.d(TAG, "GestureVoiceLauncher attached to ${view.javaClass.simpleName}")
    }

    /**
     * 移除手势监听，恢复原 View 的触摸行为。
     */
    fun detach() {
        attachedView?.setOnTouchListener(null)
        attachedView = null
        cancelLongPress()
        Log.d(TAG, "GestureVoiceLauncher detached")
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isLongPressing = false
                scheduleLongPress()
                // 返回 false 让事件继续传递给子 View（地图滑动等正常工作）
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                // 如果手指移动超过 50px，取消长按（判定为滑动）
                val dx = event.x - startX
                val dy = event.y - startY
                if (dx * dx + dy * dy > 50 * 50) {
                    cancelLongPress()
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                return false
            }
        }
        return false
    }

    private fun scheduleLongPress() {
        cancelLongPress()
        longPressRunnable = Runnable {
            isLongPressing = true
            onLongPressTriggered()
        }
        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION_MS)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun onLongPressTriggered() {
        val vim = voiceInteractionManager ?: return
        val ctx = attachedView?.context ?: return

        Log.d(TAG, "Long press detected — launching voice assistant")

        // 震动反馈
        vibrator?.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )

        // TTS 提示
        vim.speakFeedback("语音助手已就绪，请说话")

        // 启动语音助手 COMMAND 模式
        vim.startListening(VoiceInteractionManager.Mode.COMMAND)
    }
}
