package com.example.voicenavigation.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.example.voicenavigation.animation.AnimatorUtils.cancelAndClear
import com.example.voicenavigation.animation.AnimatorUtils.onEnd

/**
 * View 可见性过渡动画集。
 *
 * 替代项目中所有 `view.visibility = VISIBLE / GONE` 的硬切操作。
 * 每个函数内部处理 visibility 设置，调用方不需要手动管理。
 * 支持自动防抖动：对同一 View 快速连续调用时，会先取消前一个动画。
 */
object ViewTransition {

    /** 存储 View 正在运行的过渡动画，用于防重入 */
    private val activeTransitions = mutableMapOf<View, Animator>()

    private fun cancelPrevious(view: View) {
        activeTransitions.remove(view)?.cancelAndClear()
    }

    private fun trackAnimator(view: View, animator: Animator) {
        activeTransitions[view] = animator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                activeTransitions.remove(view)
            }
        })
    }

    // ==================== 淡入/淡出 ====================

    /**
     * 淡入显示。
     *
     * @param view      目标 View
     * @param duration  时长（毫秒）
     * @param fromAlpha 起始透明度（默认 0f）
     * @param onEnd     动画结束回调（可选）
     */
    fun fadeIn(
        view: View,
        duration: Long = 200L,
        fromAlpha: Float = 0f,
        onEnd: (() -> Unit)? = null
    ) {
        cancelPrevious(view)
        view.alpha = fromAlpha
        view.visibility = View.VISIBLE
        val animator = AnimatorUtils.alphaAnimator(view, fromAlpha, 1f, duration).apply {
            this.onEnd { onEnd?.invoke() }
        }
        trackAnimator(view, animator)
        animator.start()
    }

    /**
     * 淡出隐藏（动画结束后设 GONE）。
     *
     * @param view      目标 View
     * @param duration  时长（毫秒）
     * @param toAlpha   结束透明度（默认 0f）
     * @param onEnd     动画结束回调（可选）
     */
    fun fadeOut(
        view: View,
        duration: Long = 200L,
        toAlpha: Float = 0f,
        onEnd: (() -> Unit)? = null
    ) {
        cancelPrevious(view)
        val animator = AnimatorUtils.alphaAnimator(view, view.alpha, toAlpha, duration).apply {
            this.onEnd {
                view.visibility = View.GONE
                onEnd?.invoke()
            }
        }
        trackAnimator(view, animator)
        animator.start()
    }

    // ==================== 滑入/滑出 ====================

    /**
     * 从下方滑入显示。
     *
     * @param view     目标 View
     * @param duration 时长（毫秒）
     * @param distance 滑动距离（默认取 View 高度，需在 View 已布局后调用）
     * @param onEnd    动画结束回调（可选）
     */
    fun slideUp(
        view: View,
        duration: Long = 250L,
        distance: Float = 0f,
        onEnd: (() -> Unit)? = null
    ) {
        cancelPrevious(view)
        val slideDistance = if (distance != 0f) distance else view.height.toFloat().takeIf { it > 0f } ?: 300f
        view.translationY = slideDistance
        view.visibility = View.VISIBLE
        val animator = AnimatorUtils.translationYAnimator(
            view, slideDistance, 0f, duration, DecelerateInterpolator()
        ).apply {
            this.onEnd { onEnd?.invoke() }
        }
        trackAnimator(view, animator)
        animator.start()
    }

    /**
     * 向下方滑出隐藏（动画结束后设 GONE）。
     *
     * @param view     目标 View
     * @param duration 时长（毫秒）
     * @param distance 滑动距离（默认取 View 高度）
     * @param onEnd    动画结束回调（可选）
     */
    fun slideDown(
        view: View,
        duration: Long = 250L,
        distance: Float = 0f,
        onEnd: (() -> Unit)? = null
    ) {
        cancelPrevious(view)
        val slideDistance = if (distance != 0f) distance else view.height.toFloat().takeIf { it > 0f } ?: 300f
        val animator = AnimatorUtils.translationYAnimator(
            view, view.translationY, slideDistance, duration, DecelerateInterpolator()
        ).apply {
            this.onEnd {
                view.visibility = View.GONE
                view.translationY = 0f
                onEnd?.invoke()
            }
        }
        trackAnimator(view, animator)
        animator.start()
    }

    // ==================== 缩放弹出/收起 ====================

    /**
     * 缩放弹出（从 0 到 1，带弹性回弹）。
     *
     * @param view     目标 View
     * @param duration 时长（毫秒）
     * @param pivotX   缩放中心 X（默认 View 中心）
     * @param pivotY   缩放中心 Y（默认 View 中心）
     * @param onEnd    动画结束回调（可选）
     */
    fun scaleIn(
        view: View,
        duration: Long = 350L,
        pivotX: Float = 0f,
        pivotY: Float = 0f,
        onEnd: (() -> Unit)? = null
    ) {
        cancelPrevious(view)
        if (pivotX != 0f || pivotY != 0f) {
            view.pivotX = pivotX
            view.pivotY = pivotY
        }
        view.scaleX = 0f
        view.scaleY = 0f
        view.alpha = 0f
        view.visibility = View.VISIBLE
        val scaleAnim = AnimatorUtils.scaleAnimator(view, 0f, 1f, duration, OvershootInterpolator(1.5f))
        val alphaAnim = AnimatorUtils.alphaAnimator(view, 0f, 1f, (duration * 0.6).toLong())
        val set = AnimatorSet().apply {
            playTogether(scaleAnim, alphaAnim)
            this.onEnd { onEnd?.invoke() }
        }
        trackAnimator(view, set)
        set.start()
    }

    /**
     * 从指定触摸坐标缩放弹出（用于环形菜单等从触摸点展开的场景）。
     *
     * @param view     目标 View
     * @param pivotX   触摸点 X（相对于 View）
     * @param pivotY   触摸点 Y（相对于 View）
     * @param duration 时长（毫秒）
     * @param onEnd    动画结束回调（可选）
     */
    fun scaleInFrom(
        view: View,
        pivotX: Float,
        pivotY: Float,
        duration: Long = 350L,
        onEnd: (() -> Unit)? = null
    ) {
        scaleIn(view, duration, pivotX, pivotY, onEnd)
    }

    /**
     * 缩放收起（从 1 到 0，动画结束后设 GONE）。
     *
     * @param view     目标 View
     * @param duration 时长（毫秒）
     * @param onEnd    动画结束回调（可选）
     */
    fun scaleOut(
        view: View,
        duration: Long = 200L,
        onEnd: (() -> Unit)? = null
    ) {
        cancelPrevious(view)
        val scaleAnim = AnimatorUtils.scaleAnimator(view, view.scaleX, 0f, duration)
        val alphaAnim = AnimatorUtils.alphaAnimator(view, view.alpha, 0f, duration)
        val set = AnimatorSet().apply {
            playTogether(scaleAnim, alphaAnim)
            this.onEnd {
                view.visibility = View.GONE
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 1f
                onEnd?.invoke()
            }
        }
        trackAnimator(view, set)
        set.start()
    }
}
