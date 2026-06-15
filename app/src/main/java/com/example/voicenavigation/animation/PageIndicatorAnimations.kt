package com.example.voicenavigation.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.example.voicenavigation.animation.AnimatorUtils.onEnd

/**
 * 页面指示器（小圆点 + 页面名称）动画编排器。
 *
 * 管理 ViewPager2 底部圆点指示器的所有动画：
 * - 圆点切换：选中放大 + 未选中缩小
 * - 页面名称弹出：放大弹入 → 停留 → 淡出
 * - 圆点点击心跳反馈
 *
 * 使用方式：
 * ```
 *   // 页面切换时
 *   PageIndicatorAnimations.onPageChanged(dotViews, fromPos, toPos)
 *
 *   // 点击圆点时
 *   PageIndicatorAnimations.onDotClicked(clickedDot, labelView, "自由采集")
 * ```
 */
object PageIndicatorAnimations {

    /** 存储活跃的名称淡出 Runnable，用于防重入 */
    private var labelDismissRunnable: Runnable? = null
    private var labelAnimator: AnimatorSet? = null

    // ==================== 圆点切换动画 ====================

    /**
     * 页面切换时更新所有圆点的动画。
     *
     * 选中圆点：scale 1→1.3 + alpha→1，带弹性（200ms）
     * 未选中圆点：scale 当前→1 + alpha→0.35（150ms）
     * 所有圆点同时驱动（playTogether）。
     *
     * @param dotViews  所有圆点 View 列表
     * @param position  当前选中位置
     */
    fun onPageChanged(dotViews: List<View>, position: Int) {
        val animators = dotViews.mapIndexed { index, dot ->
            val isSelected = index == position
            val targetScale = if (isSelected) 1.3f else 1f
            val targetAlpha = if (isSelected) 1f else 0.35f

            // scale X + Y
            val scaleAnimX = AnimatorUtils.floatAnimator(
                dot.scaleX, targetScale, if (isSelected) 200 else 150,
                if (isSelected) OvershootInterpolator(2f) else DecelerateInterpolator()
            ) { dot.scaleX = it }

            val scaleAnimY = AnimatorUtils.floatAnimator(
                dot.scaleY, targetScale, if (isSelected) 200 else 150,
                if (isSelected) OvershootInterpolator(2f) else DecelerateInterpolator()
            ) { dot.scaleY = it }

            // alpha
            val alphaAnim = AnimatorUtils.floatAnimator(
                dot.alpha, targetAlpha, if (isSelected) 200 else 150
            ) { dot.alpha = it }

            AnimatorSet().apply { playTogether(scaleAnimX, scaleAnimY, alphaAnim) }
        }

        AnimatorSet().apply { playTogether(animators) }.start()
    }

    // ==================== 页面名称弹出动画 ====================

    /**
     * 点击圆点时弹出页面名称标签。
     *
     * 动画序列：放大弹入（250ms）→ 停留（1200ms）→ 淡出（300ms）
     * 防重入：快速连续点击时取消前一个动画，重新开始。
     *
     * @param labelView  页面名称 TextView
     * @param text       要显示的名称
     */
    fun showLabel(labelView: TextView, text: String) {
        // 取消前一个动画（防重入）
        cancelLabelAnimation(labelView)

        labelView.text = text
        labelView.visibility = View.VISIBLE

        // 弹入：scale 0.8→1 + alpha 0→1
        val scaleIn = AnimatorUtils.floatAnimator(0.8f, 1f, 250, OvershootInterpolator(2f)) {
            labelView.scaleX = it
            labelView.scaleY = it
        }
        val alphaIn = AnimatorUtils.floatAnimator(0f, 1f, 200) {
            labelView.alpha = it
        }
        val entrance = AnimatorSet().apply { playTogether(scaleIn, alphaIn) }

        // 淡出：alpha 1→0
        val fadeOut = AnimatorUtils.floatAnimator(1f, 0f, 300, DecelerateInterpolator()) {
            labelView.alpha = it
        }.apply {
            onEnd {
                labelView.visibility = View.GONE
                labelView.scaleX = 1f
                labelView.scaleY = 1f
            }
        }

        // 组合：弹入 → (delay 1200ms) → 淡出
        val fullSequence = AnimatorSet().apply {
            playSequentially(entrance, createDelay(1200), fadeOut)
        }

        labelAnimator = fullSequence
        fullSequence.start()
    }

    /**
     * 取消页面名称动画并隐藏。
     */
    fun cancelLabel(labelView: TextView) {
        cancelLabelAnimation(labelView)
        labelView.visibility = View.GONE
        labelView.alpha = 1f
        labelView.scaleX = 1f
        labelView.scaleY = 1f
    }

    // ==================== 圆点点击心跳反馈 ====================

    /**
     * 圆点被点击时的心跳反馈：scale 1→1.4→0.9→1（200ms）。
     *
     * @param dot 被点击的圆点 View
     */
    fun onDotClicked(dot: View) {
        ValueAnimator.ofFloat(1f, 1.4f, 0.9f, 1f).apply {
            duration = 200
            addUpdateListener {
                val s = it.animatedValue as Float
                dot.scaleX = s
                dot.scaleY = s
            }
            start()
        }
    }

    // ==================== 内部工具 ====================

    private fun cancelLabelAnimation(labelView: TextView) {
        labelAnimator?.let {
            it.cancel()
            it.removeAllListeners()
        }
        labelAnimator = null
        labelView.animate().cancel()
        labelView.removeCallbacks(labelDismissRunnable)
    }

    /**
     * 创建延迟 Animator（不产生动画效果，仅作为 AnimatorSet 中的占位延迟）。
     */
    private fun createDelay(ms: Long): ValueAnimator {
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
        }
    }
}
