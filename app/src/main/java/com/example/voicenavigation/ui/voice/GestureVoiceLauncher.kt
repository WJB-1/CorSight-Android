package com.example.voicenavigation.ui.voice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.example.voicenavigation.voice.VoiceInteractionManager

/**
 * 全局单指长按手势检测器。
 *
 * 交互逻辑：
 * - 长按 500ms → 震动 → 弹出环形菜单
 * - 手指不抬起，在菜单上滑动选择功能 → 松手执行
 * - 如果长按后手指没移动直接松手 → 启动语音助手
 *
 * 用法：
 * 1. 在 Activity.onCreate 中调用 GestureVoiceLauncher.attach(this, callback)
 * 2. 在 Activity 中重写 dispatchTouchEvent
 */
object GestureVoiceLauncher {

    private const val TAG = "GestureVoiceLauncher"
    private const val LONG_PRESS_DURATION_MS = 500L

    private var vibrator: Vibrator? = null
    private var voiceInteractionManager: VoiceInteractionManager? = null
    private var callback: GestureCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private var longPressRunnable: Runnable? = null
    private var isLongPressing = false
    private var startX = 0f
    private var startY = 0f

    interface GestureCallback {
        /** 长按后手指没动直接松手 → 启动语音助手 */
        fun onVoiceAssistant()
        /** 长按触发 → 弹出环形菜单，传入按住位置 */
        fun onRingMenuShow(centerX: Float, centerY: Float)
        /** 手指松开 → 确认执行当前选中项 */
        fun onRingMenuConfirm()
        /** 取消（手指滑出范围等） */
        fun onCancel()
    }

    fun attach(activity: Activity, vim: VoiceInteractionManager, cb: GestureCallback) {
        voiceInteractionManager = vim
        vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        callback = cb
        Log.d(TAG, "GestureVoiceLauncher attached")
    }

    fun detach() {
        cancelLongPress()
        voiceInteractionManager = null
        callback = null
        vibrator = null
        isLongPressing = false
        Log.d(TAG, "GestureVoiceLauncher detached")
    }

    /**
     * 在 Activity.dispatchTouchEvent() 中调用。
     * 返回 false 不拦截事件，让它正常传递给子 View。
     */
    fun onDispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isLongPressing = false
                scheduleLongPress()
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (dx * dx + dy * dy > 50 * 50 && !isLongPressing) {
                    // 手指移动超过 50px 且还没触发长按 → 普通滑动，取消
                    cancelLongPress()
                }
                return false
            }

            MotionEvent.ACTION_UP -> {
                if (isLongPressing) {
                    // B1 修复：判断手指是否有移动
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val moved = dx * dx + dy * dy > 50 * 50
                    if (moved) {
                        // 手指滑动过 → 确认菜单选中项
                        callback?.onRingMenuConfirm()
                    } else {
                        // 手指没有移动 → 启动语音助手
                        callback?.onVoiceAssistant()
                    }
                    isLongPressing = false
                    return true
                } else {
                    cancelLongPress()
                    return false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isLongPressing) {
                    callback?.onCancel()
                }
                cancelLongPress()
                isLongPressing = false
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
        Log.d(TAG, "Long press detected — showing ring menu")

        // 震动反馈
        vibrator?.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )

        // 弹出环形菜单
        callback?.onRingMenuShow(startX, startY)
    }
}
