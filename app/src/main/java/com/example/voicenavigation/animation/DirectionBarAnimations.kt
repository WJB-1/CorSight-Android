package com.example.voicenavigation.animation

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator

private const val TAG_DIR = "DirBarAnim"

/**
 * 八方向电池条动画编排器。
 *
 * 管理方向状态栏中每个格子的动画：
 * - 正在采集·未对准：呼吸 + 缩放脉冲（scale 1→1.15，alpha 1→0.6，800ms 循环）
 * - 正在采集·已对准就绪：静态（无动画，颜色醒目即可）
 * - 已采集 / 未采集：静态
 *
 * 使用方式：
 * ```
 *   // 当格子进入"未对准"状态时
 *   val anim = DirectionBarAnimations.startPulsing(cell)
 *   // 状态切换时停止
 *   DirectionBarAnimations.stopPulsing(cell, anim)
 * ```
 */
object DirectionBarAnimations {

    /** 脉冲动画 tag key，存入 View.tag 以便识别和取消 */
    private const val TAG_PULSE_ANIM = "dir_pulse_anim"

    /**
     * 启动呼吸+缩放脉冲动画（未对准状态）。
     *
     * scale 1→1.15→1 循环，alpha 1→0.6→1 循环，800ms 一个周期。
     * 调用前会自动取消该 View 上已有的脉冲动画（防重入）。
     *
     * @param cell     方向格子 View
     * @param duration 单次循环时长（毫秒）
     * @return AnimatorSet（用于外部持有引用以便停止）
     */
    fun startPulsing(cell: View, duration: Long = 800L): AnimatorSet {
        Log.d(TAG_DIR, "startPulsing: cell=$cell, attached=${cell.isAttachedToWindow}, scaleX=${cell.scaleX}, alpha=${cell.alpha}, w=${cell.width}, h=${cell.height}")
        // 先停止已有的
        stopPulsing(cell)

        // 缩放脉冲
        val scaleAnim = ValueAnimator.ofFloat(1f, 1.15f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val s = it.animatedValue as Float
                cell.scaleX = s
                cell.scaleY = s
            }
        }

        // alpha 呼吸
        val alphaAnim = ValueAnimator.ofFloat(1f, 0.6f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                cell.alpha = it.animatedValue as Float
            }
        }

        val set = AnimatorSet().apply {
            playTogether(scaleAnim, alphaAnim)
        }

        // 存入 tag 以便后续停止
        cell.setTag(TAG_PULSE_ANIM.hashCode(), set)
        set.start()
        Log.d(TAG_DIR, "startPulsing: AnimatorSet started, isRunning=${set.isRunning}")
        return set
    }

    /**
     * 停止指定格子上的脉冲动画，恢复默认状态。
     *
     * @param cell 方向格子 View
     */
    fun stopPulsing(cell: View) {
        @Suppress("UNCHECKED_CAST")
        val existing = cell.getTag(TAG_PULSE_ANIM.hashCode()) as? AnimatorSet
        existing?.let {
            Log.d(TAG_DIR, "stopPulsing: cancelling existing animator for $cell")
            it.cancel()
            it.removeAllListeners()
        }
        cell.scaleX = 1f
        cell.scaleY = 1f
        cell.alpha = 1f
    }
}
