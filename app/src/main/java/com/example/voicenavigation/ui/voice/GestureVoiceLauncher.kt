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
 * 全局单指长按语音助手唤醒器。
 * 通过 Activity.dispatchTouchEvent() 捕获事件，可穿透 MapView/PreviewView 等子 View 的事件消费。
 *
 * 用法：
 * 1. 在 Activity.onCreate 中调用：
 *    GestureVoiceLauncher.attach(this, voiceInteractionManager)
 * 2. 在 Activity 中重写：
 *    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
 *        GestureVoiceLauncher.onDispatchTouchEvent(ev)
 *        return super.dispatchTouchEvent(ev)
 *    }
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
     * 在 Activity 中初始化。
     */
    fun attach(activity: Activity, vim: VoiceInteractionManager) {
        voiceInteractionManager = vim
        vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        attachedView = activity.findViewById(android.R.id.content)
        Log.d(TAG, "GestureVoiceLauncher attached to Activity dispatch")
    }

    /**
     * 在 Activity.dispatchTouchEvent() 中调用。
     * 返回 false 表示不拦截，事件继续正常传递。
     */
    fun onDispatchTouchEvent(event: MotionEvent): Boolean {
        return handleTouchEvent(event)
    }

    /**
     * 移除监听。
     */
    fun detach() {
        cancelLongPress()
        attachedView = null
        voiceInteractionManager = null
        vibrator = null
        Log.d(TAG, "GestureVoiceLauncher detached")
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
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

        vibrator?.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )

        vim.speakFeedback("语音助手已就绪，请说话")
        vim.startListening(VoiceInteractionManager.Mode.COMMAND)
    }
}