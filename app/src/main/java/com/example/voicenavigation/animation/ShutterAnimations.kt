package com.example.voicenavigation.animation

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.example.voicenavigation.animation.AnimatorUtils.onEnd

/**
 * 快门按钮动画编排器。
 *
 * 所有快门按钮的动画逻辑集中在此处，由动画层统一管理。
 * 按钮本身不包含任何动画代码。
 *
 * 使用方式：
 * ```
 *   // 方式一：手动触发按压缩放
 *   shutterBtn.setOnTouchListener { v, event ->
 *       ShutterAnimations.onTouchEvent(v, event)
 *       false
 *   }
 *
 *   // 方式二：拍照时触发闪光 + 心跳反馈
 *   ShutterAnimations.onCapture(shutterBtn)
 *
 *   // 启用/禁用动画
 *   ShutterAnimations.setEnabled(shutterBtn, true)
 *   ShutterAnimations.setEnabled(shutterBtn, false)
 * ```
 */
object ShutterAnimations {

    // ==================== 触摸按压反馈 ====================

    /**
     * 处理快门按钮的触摸事件，实现按压缩放反馈。
     *
     * 在 View.setOnTouchListener 中调用：
     * ```
     *   shutterBtn.setOnTouchListener { v, event ->
     *       ShutterAnimations.onTouchEvent(v, event)
     *   }
     * ```
     *
     * **注意：返回 true 消费事件，手动调用 performClick() 触发 onClick。**
     * 如果返回 false，ACTION_UP 不会送达，松开弹回动画无法播放。
     *
     * @param view  快门按钮 View
     * @param event 触摸事件
     * @return true（始终消费事件）
     */
    fun onTouchEvent(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 按下：缩小到 0.88，100ms
                // 使用 view.animate()（ViewPropertyAnimator），方便后续 onCapture 中取消
                view.animate()
                    .scaleX(0.88f).scaleY(0.88f)
                    .setDuration(100)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP -> {
                // 松开：弹回 1.0，150ms 带弹性
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
                // 手动触发 click（因为我们消费了事件，系统不会自动触发 onClick）
                view.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                // 取消：弹回 1.0
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .start()
            }
        }
        return true
    }

    // ==================== 拍照反馈 ====================

    /**
     * 拍照时的反馈动画：心跳 + 闪光白屏。
     *
     * 在拍照成功时调用，给用户明确的"已拍摄"反馈。
     *
     * @param view 快门按钮 View
     * @return AnimatorSet（可监听结束）
     */
    fun onCapture(view: View): AnimatorSet {
        // 先取消可能还在播放的弹回动画，避免 scaleX/Y 竞争
        view.animate().cancel()
        // 心跳：当前值 → 1.2 → 0.95 → 1
        val heartbeat = Animations.Feedback.heartbeat(view, 300)
        return AnimatorSet().apply {
            playTogether(heartbeat)
            start()
        }
    }

    // ==================== 启用/禁用动画 ====================

    /**
     * 启用/禁用快门按钮，带动画过渡。
     *
     * 替代原来的 `shutterBtn.alpha = if (enabled) 1f else 0.3f` 硬切。
     *
     * @param view    快门按钮 View
     * @param enabled 是否启用
     * @param duration 过渡时长（毫秒）
     */
    fun setEnabled(view: View, enabled: Boolean, duration: Long = 200L) {
        view.isClickable = enabled
        val targetAlpha = if (enabled) 1f else 0.3f
        if (view.alpha != targetAlpha) {
            AnimatorUtils.alphaAnimator(view, view.alpha, targetAlpha, duration).start()
        }
    }

    // ==================== 拍照闪光效果（覆盖层） ====================

    /**
     * 全屏闪光白效果。
     *
     * 在预览区域上叠加一个白色 View，快速淡出模拟闪光灯。
     * 需要布局中有一个 id 为 `shutterFlashOverlay` 的 View（半透明白色）。
     * 如果 flashView 为 null（布局中没有该 View），则静默跳过，不崩溃。
     *
     * @param flashView 闪光覆盖层 View（可为 null，向后兼容旧布局）
     * @param duration  闪光时长（毫秒）
     */
    fun flashOverlay(flashView: View?, duration: Long = 150L) {
        flashView ?: return
        flashView.visibility = View.VISIBLE
        // 使用 view.animate() 确保属性变更在同一帧内正确排队：
        // 1. 先设为不透明（白色闪烁）
        // 2. 然后淡出到透明
        // 3. 完成后隐藏
        flashView.animate().cancel()
        flashView.alpha = 0.8f
        flashView.animate()
            .alpha(0f)
            .setDuration(duration)
            .withEndAction {
                flashView.visibility = View.GONE
                flashView.alpha = 0f
            }
            .start()
    }
}
