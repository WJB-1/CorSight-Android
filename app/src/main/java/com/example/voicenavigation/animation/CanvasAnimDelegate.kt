package com.example.voicenavigation.animation

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator

/**
 * Canvas 绘制动画委托。
 *
 * 为自定义 Canvas View（如 RingMenuView、DetectionOverlayView）提供动画状态管理。
 * View 上没有可被 ObjectAnimator 直接驱动的属性，此委托管理一组命名的浮点/整数状态变量，
 * 通过 ValueAnimator 驱动它们变化，并在每帧回调中触发 View 刷新。
 *
 * 使用方式：
 * ```
 *   private val animDelegate = CanvasAnimDelegate(this)
 *
 *   // 在 onDraw 中读取：
 *   val scale = animDelegate.getFloat("menuScale", 0f)
 *
 *   // 触发动画：
 *   animDelegate.animateFloat("menuScale", 0f, 1f, 350, OvershootInterpolator()) { invalidate() }
 * ```
 */
class CanvasAnimDelegate(private val view: View) {

    /** 当前动画值快照（每个 key 对应一个 Float 或 Int 状态） */
    private val floatValues = mutableMapOf<String, Float>()
    private val intValues = mutableMapOf<String, Int>()

    /** 活跃的动画实例，用于取消和防重入 */
    private val activeAnimators = mutableMapOf<String, ValueAnimator>()

    // ==================== 读取当前值 ====================

    /** 获取当前动画 Float 值，不存在则返回 [default]。 */
    fun getFloat(key: String, default: Float = 0f): Float {
        return floatValues.getOrDefault(key, default)
    }

    /** 获取当前动画 Int 值，不存在则返回 [default]。 */
    fun getInt(key: String, default: Int = 0): Int {
        return intValues.getOrDefault(key, default)
    }

    // ==================== 发起动画 ====================

    /**
     * 动画化一个 Float 属性。
     *
     * 如果同一 key 已有动画在运行，会先取消旧动画再启动新动画（防重入）。
     *
     * @param key          属性标识（同一 View 内唯一）
     * @param from         起始值
     * @param to           结束值
     * @param duration     时长（毫秒）
     * @param interpolator 插值器
     * @param onFrame      每帧回调（通常传 { view.invalidate() }）
     * @return 启动的 ValueAnimator 实例
     */
    fun animateFloat(
        key: String,
        from: Float,
        to: Float,
        duration: Long = 300L,
        interpolator: Interpolator = AccelerateDecelerateInterpolator(),
        onFrame: (() -> Unit)? = null
    ): ValueAnimator {
        cancel(key)
        floatValues[key] = from
        val animator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                floatValues[key] = it.animatedValue as Float
                onFrame?.invoke()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    activeAnimators.remove(key)
                }
            })
        }
        activeAnimators[key] = animator
        animator.start()
        return animator
    }

    /**
     * 动画化一个 Int 属性（颜色等）。
     */
    fun animateInt(
        key: String,
        from: Int,
        to: Int,
        duration: Long = 200L,
        interpolator: Interpolator = AccelerateDecelerateInterpolator(),
        onFrame: (() -> Unit)? = null
    ): ValueAnimator {
        cancel(key)
        intValues[key] = from
        val animator = ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                intValues[key] = it.animatedValue as Int
                onFrame?.invoke()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    activeAnimators.remove(key)
                }
            })
        }
        activeAnimators[key] = animator
        animator.start()
        return animator
    }

    // ==================== 取消控制 ====================

    /** 取消指定 key 的动画（如果有）。 */
    fun cancel(key: String) {
        activeAnimators.remove(key)?.let {
            it.removeAllListeners()
            it.cancel()
        }
    }

    /** 取消所有活跃动画。 */
    fun cancelAll() {
        activeAnimators.values.forEach {
            it.removeAllListeners()
            it.cancel()
        }
        activeAnimators.clear()
    }

    /** 是否有动画正在运行。 */
    fun isAnimating(): Boolean = activeAnimators.isNotEmpty()

    /**
     * 手动设置一个 Float 值（不触发动画，用于初始化或瞬时更新）。
     */
    fun setFloat(key: String, value: Float) {
        floatValues[key] = value
    }

    /**
     * 手动设置一个 Int 值（不触发动画）。
     */
    fun setInt(key: String, value: Int) {
        intValues[key] = value
    }
}
