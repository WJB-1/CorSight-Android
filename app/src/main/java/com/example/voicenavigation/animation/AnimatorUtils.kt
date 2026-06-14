package com.example.voicenavigation.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator

/**
 * 通用动画工具集。
 *
 * 提供创建 ValueAnimator / AnimatorSet 的便捷函数，所有函数返回 Animator 实例，
 * 调用方自行决定何时 start()。不持有任何状态，纯工具 object。
 */
object AnimatorUtils {

    // ==================== 数值动画原语 ====================

    /**
     * 创建 Float 动画。
     *
     * @param from      起始值
     * @param to        结束值
     * @param duration  时长（毫秒）
     * @param interpolator 插值器
     * @param onUpdate  每帧回调，参数为当前动画值
     */
    fun floatAnimator(
        from: Float,
        to: Float,
        duration: Long = 300L,
        interpolator: Interpolator = AccelerateDecelerateInterpolator(),
        onUpdate: (Float) -> Unit
    ): ValueAnimator {
        return ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { onUpdate(it.animatedValue as Float) }
        }
    }

    /**
     * 创建 Int 动画。
     */
    fun intAnimator(
        from: Int,
        to: Int,
        duration: Long = 300L,
        interpolator: Interpolator = AccelerateDecelerateInterpolator(),
        onUpdate: (Int) -> Unit
    ): ValueAnimator {
        return ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { onUpdate(it.animatedValue as Int) }
        }
    }

    /**
     * 创建颜色过渡动画 (ofArgb)。
     *
     * @param fromColor 起始颜色（ARGB int）
     * @param toColor   结束颜色（ARGB int）
     * @param duration  时长（毫秒）
     * @param onUpdate  每帧回调，参数为当前插值颜色
     */
    fun argbAnimator(
        fromColor: Int,
        toColor: Int,
        duration: Long = 200L,
        onUpdate: (Int) -> Unit
    ): ValueAnimator {
        return ValueAnimator.ofArgb(fromColor, toColor).apply {
            this.duration = duration
            addUpdateListener { onUpdate(it.animatedValue as Int) }
        }
    }

    // ==================== View 属性动画原语 ====================

    /**
     * View scaleX/Y 同步缩放动画。返回 AnimatorSet（同时播放 X 和 Y）。
     */
    fun scaleAnimator(
        view: View,
        from: Float,
        to: Float,
        duration: Long = 300L,
        interpolator: Interpolator = OvershootInterpolator(1.5f)
    ): AnimatorSet {
        val animX = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { view.scaleX = it.animatedValue as Float }
        }
        val animY = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { view.scaleY = it.animatedValue as Float }
        }
        return AnimatorSet().apply { playTogether(animX, animY) }
    }

    /**
     * View alpha 淡入淡出动画。
     */
    fun alphaAnimator(
        view: View,
        from: Float,
        to: Float,
        duration: Long = 200L
    ): ValueAnimator {
        return ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            addUpdateListener { view.alpha = it.animatedValue as Float }
        }
    }

    /**
     * View translationY 平移动画。
     */
    fun translationYAnimator(
        view: View,
        from: Float,
        to: Float,
        duration: Long = 250L,
        interpolator: Interpolator = DecelerateInterpolator()
    ): ValueAnimator {
        return ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { view.translationY = it.animatedValue as Float }
        }
    }

    // ==================== 组合工具 ====================

    /**
     * 同时播放多个动画。
     */
    fun playTogether(vararg animators: Animator): AnimatorSet {
        return AnimatorSet().apply { playTogether(*animators) }
    }

    /**
     * 顺序播放多个动画。
     */
    fun playSequentially(vararg animators: Animator): AnimatorSet {
        return AnimatorSet().apply { playSequentially(*animators) }
    }

    // ==================== 扩展函数 ====================

    /** 动画结束回调。 */
    fun Animator.onEnd(action: () -> Unit): Animator {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                action()
            }
        })
        return this
    }

    /** 动画开始回调。 */
    fun Animator.onStart(action: () -> Unit): Animator {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                action()
            }
        })
        return this
    }

    /** 取消并清理监听器（防泄漏）。 */
    fun Animator.cancelAndClear() {
        cancel()
        removeAllListeners()
    }
}
