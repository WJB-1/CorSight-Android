package com.example.voicenavigation.animation

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import com.example.voicenavigation.DetectionOverlayView
import com.example.voicenavigation.animation.AnimatorUtils.onEnd

/**
 * 检测覆盖层动画编排器。
 *
 * 所有 DetectionOverlayView 的动画逻辑集中在此处，由动画层统一管理。
 * DetectionOverlayView 本身不包含任何动画代码，仅暴露可动画化属性。
 *
 * 使用方式：
 * ```
 *   // 更新检测结果时，触发动画过渡
 *   DetectionOverlayAnimations.animateTransition(overlayView, duration = 200)
 *
 *   // 启动风险区域呼吸闪烁
 *   val glow = DetectionOverlayAnimations.startRiskZoneGlow(overlayView)
 *   // 停止
 *   glow.cancel()
 * ```
 */
object DetectionOverlayAnimations {

    /**
     * 检测框位置过渡动画。
     *
     * 调用时机：在 [DetectionOverlayView.updateDetections] 之后调用，
     * 驱动 transitionProgress 从 0→1，实现新旧检测框之间的平滑插值。
     *
     * @param overlay  目标 DetectionOverlayView
     * @param duration 时长（毫秒）
     * @return ValueAnimator（可外部监听或取消）
     */
    fun animateTransition(overlay: DetectionOverlayView, duration: Long = 200L): ValueAnimator {
        overlay.transitionProgress = 0f
        return AnimatorUtils.floatAnimator(0f, 1f, duration, DecelerateInterpolator()) {
            overlay.transitionProgress = it
        }.apply { start() }
    }

    /**
     * 检测框淡入动画。
     *
     * 用于障碍物检测刚启动时，检测框从透明渐显。
     *
     * @param overlay  目标 DetectionOverlayView
     * @param duration 时长（毫秒）
     * @return ValueAnimator
     */
    fun fadeIn(overlay: DetectionOverlayView, duration: Long = 300L): ValueAnimator {
        overlay.boxAlpha = 0
        return AnimatorUtils.intAnimator(0, 255, duration, DecelerateInterpolator()) {
            overlay.boxAlpha = it
        }.apply { start() }
    }

    /**
     * 检测框淡出动画。
     *
     * 用于障碍物检测停止时，检测框渐隐消失。
     *
     * @param overlay  目标 DetectionOverlayView
     * @param duration 时长（毫秒）
     * @param onEnd    动画结束回调（可选）
     * @return ValueAnimator
     */
    fun fadeOut(overlay: DetectionOverlayView, duration: Long = 200L, onEnd: (() -> Unit)? = null): ValueAnimator {
        return AnimatorUtils.intAnimator(overlay.boxAlpha, 0, duration, DecelerateInterpolator()) {
            overlay.boxAlpha = it
        }.apply {
            this.onEnd { onEnd?.invoke() }
            start()
        }
    }

    /**
     * 风险区域呼吸闪烁动画（持续循环）。
     *
     * 风险区域的填充色会周期性地明暗变化，提示用户注意。
     * 参考 animatior_guidance.md §2 "呼吸发光"。
     *
     * @param overlay  目标 DetectionOverlayView
     * @param minAlpha 最低闪烁 alpha 偏移
     * @param maxAlpha 最高闪烁 alpha 偏移
     * @param duration 单次循环时长（毫秒）
     * @return ValueAnimator（调用 .cancel() 停止）
     */
    fun startRiskZoneGlow(
        overlay: DetectionOverlayView,
        minAlpha: Int = 0x00,
        maxAlpha: Int = 0x33,
        duration: Long = 1000L
    ): ValueAnimator {
        return Animations.Selection.breathingGlow(minAlpha, maxAlpha, duration) { alpha ->
            overlay.riskZoneGlowAlpha = alpha
        }.apply { start() }
    }

    /**
     * 紧急风险闪烁动画（快速闪烁，用于 HIGH 级别障碍物）。
     *
     * @param overlay  目标 DetectionOverlayView
     * @param duration 单次闪烁时长（毫秒，越短越快）
     * @return ValueAnimator
     */
    fun startUrgentFlash(
        overlay: DetectionOverlayView,
        duration: Long = 400L
    ): ValueAnimator {
        return Animations.Selection.breathingGlow(0x00, 0x66, duration) { alpha ->
            overlay.riskZoneGlowAlpha = alpha
        }.apply { start() }
    }
}
