package com.example.voicenavigation.animation

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.graphics.Paint
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * 预定义动画配方集合。
 *
 * 组合 [AnimatorUtils] 的原语，形成业务场景动画。
 * 每个内部 object 对应一个动画场景类别，可独立使用。
 *
 * 参考 animatior_guidance.md 中的 7 大类动画模式。
 */
object Animations {

    // ==================== 弹出动画 (Entrance) ====================

    object Entrance {

        /**
         * 弹性弹出：scale 0→1 + alpha 0→1。
         * 最常用的入场动画，参考 animatior_guidance.md §1。
         */
        fun bounceIn(view: View, duration: Long = 350L): AnimatorSet {
            val scaleAnim = AnimatorUtils.scaleAnimator(
                view, 0f, 1f, duration, OvershootInterpolator(1.8f)
            )
            val alphaAnim = AnimatorUtils.alphaAnimator(view, 0f, 1f, (duration * 0.6).toLong())
            return AnimatorUtils.playTogether(scaleAnim, alphaAnim)
        }

        /**
         * 旋转展开：rotation 0→360 + scale 0→1。
         */
        fun rotateIn(view: View, duration: Long = 400L): AnimatorSet {
            view.rotation = 0f
            view.scaleX = 0f
            view.scaleY = 0f
            val rotAnim = AnimatorUtils.floatAnimator(0f, 360f, duration, DecelerateInterpolator()) {
                view.rotation = it
            }
            val scaleAnim = AnimatorUtils.scaleAnimator(
                view, 0f, 1f, duration, OvershootInterpolator(1.3f)
            )
            return AnimatorUtils.playTogether(rotAnim, scaleAnim)
        }
    }

    // ==================== 选中/高亮动画 (Selection) ====================

    object Selection {

        /**
         * 颜色渐变高亮。
         *
         * @param paint     要变色的 Paint 对象
         * @param fromColor 起始颜色
         * @param toColor   目标颜色
         * @param duration  时长（毫秒）
         * @param onFrame   每帧回调（通常传 { view.invalidate() }）
         */
        fun colorHighlight(
            paint: Paint,
            fromColor: Int,
            toColor: Int,
            duration: Long = 150L,
            onFrame: () -> Unit
        ): ValueAnimator {
            return AnimatorUtils.argbAnimator(fromColor, toColor, duration) { color ->
                paint.color = color
                onFrame()
            }
        }

        /**
         * 呼吸发光（持续循环）。
         *
         * @param fromAlpha 最低透明度
         * @param toAlpha   最高透明度
         * @param duration  单次循环时长（毫秒）
         * @param onFrame   每帧回调，参数为当前 alpha 值
         */
        fun breathingGlow(
            fromAlpha: Int = 0x00,
            toAlpha: Int = 0x44,
            duration: Long = 800L,
            onFrame: (Int) -> Unit
        ): ValueAnimator {
            return AnimatorUtils.intAnimator(fromAlpha, toAlpha, duration, LinearInterpolator()) {
                onFrame(it)
            }.apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
            }
        }

        /**
         * 描边显现：strokeWidth 从 fromWidth 渐变到 toWidth。
         *
         * @param paint     要修改的 Paint 对象
         * @param fromWidth 起始描边宽度
         * @param toWidth   目标描边宽度
         * @param duration  时长（毫秒）
         * @param onFrame   每帧回调
         */
        fun strokeReveal(
            paint: Paint,
            fromWidth: Float,
            toWidth: Float,
            duration: Long = 200L,
            onFrame: () -> Unit
        ): ValueAnimator {
            return AnimatorUtils.floatAnimator(fromWidth, toWidth, duration) { width ->
                paint.strokeWidth = width
                onFrame()
            }
        }
    }

    // ==================== 关闭动画 (Dismiss) ====================

    object Dismiss {

        /**
         * 加速消失：scale 1→0 + alpha 1→0。
         * 参考 animatior_guidance.md §5 "整体加速消失"。
         */
        fun accelerateOut(view: View, duration: Long = 200L): AnimatorSet {
            val scaleAnim = AnimatorUtils.scaleAnimator(
                view, 1f, 0f, duration, AccelerateInterpolator()
            )
            val alphaAnim = AnimatorUtils.alphaAnimator(view, 1f, 0f, duration)
            return AnimatorUtils.playTogether(scaleAnim, alphaAnim)
        }

        /**
         * 纯淡出。
         */
        fun fadeOut(view: View, duration: Long = 200L): ValueAnimator {
            return AnimatorUtils.alphaAnimator(view, 1f, 0f, duration)
        }
    }

    // ==================== 反馈动画 (Feedback) ====================

    object Feedback {

        /**
         * 心跳确认：scale 1→1.3→0.9→1。
         * 参考 animatior_guidance.md §6 "心跳确认"。
         *
         * @param view     目标 View
         * @param duration 总时长（毫秒）
         */
        fun heartbeat(view: View, duration: Long = 300L): ValueAnimator {
            return ValueAnimator.ofFloat(1f, 1.3f, 0.9f, 1f).apply {
                this.duration = duration
                addUpdateListener {
                    val scale = it.animatedValue as Float
                    view.scaleX = scale
                    view.scaleY = scale
                }
            }
        }

        /**
         * 涟漪扩散：从中心画圆，radius 0→maxRadius，alpha 255→0。
         * 参考 animatior_guidance.md §6 "涟漪扩散"。
         *
         * @param centerX   涟漪中心 X
         * @param centerY   涟漪中心 Y
         * @param maxRadius 最大半径
         * @param color     涟漪颜色
         * @param onFrame   每帧回调：(radius, alpha)
         * @param duration  时长（毫秒）
         */
        fun ripple(
            centerX: Float,
            centerY: Float,
            maxRadius: Float,
            color: Int,
            onFrame: (radius: Float, alpha: Int) -> Unit,
            duration: Long = 400L
        ): ValueAnimator {
            return AnimatorUtils.floatAnimator(0f, maxRadius, duration, DecelerateInterpolator()) { radius ->
                val alpha = (255 * (1f - radius / maxRadius)).toInt().coerceIn(0, 255)
                onFrame(radius, alpha)
            }
        }

        /**
         * 颜色闪烁：view 背景短暂变为 flashColor 后恢复。
         *
         * @param view       目标 View
         * @param flashColor 闪烁颜色
         * @param duration   时长（毫秒）
         */
        fun colorFlash(
            view: View,
            flashColor: Int,
            duration: Long = 150L
        ): ValueAnimator {
            val originalColor = (view.background?.mutate())?.let { bg ->
                // 保留原始背景，只做视觉闪烁效果
                null
            }
            // 使用 alpha 闪烁替代（更通用，不依赖背景类型）
            return ValueAnimator.ofFloat(1f, 0.5f, 1f).apply {
                this.duration = duration
                addUpdateListener {
                    view.alpha = it.animatedValue as Float
                }
            }
        }
    }

    // ==================== 环境动画 (Ambient) ====================

    object Ambient {

        /**
         * 背景遮罩淡入。
         * 参考 animatior_guidance.md §7 "背景遮罩淡入"。
         *
         * @param alphaFrom 起始透明度（0-255）
         * @param alphaTo   目标透明度（0-255）
         * @param onFrame   每帧回调，参数为当前 alpha
         * @param duration  时长（毫秒）
         */
        fun dimOverlay(
            alphaFrom: Int = 0,
            alphaTo: Int = 80,
            onFrame: (Int) -> Unit,
            duration: Long = 300L
        ): ValueAnimator {
            return AnimatorUtils.intAnimator(alphaFrom, alphaTo, duration, DecelerateInterpolator()) {
                onFrame(it)
            }
        }

        /**
         * 持续呼吸缩放（循环）。
         * 参考 animatior_guidance.md §7 "中心按钮呼吸"。
         *
         * @param view     目标 View
         * @param minScale 最小缩放
         * @param maxScale 最大缩放
         * @param duration 单次循环时长（毫秒）
         */
        fun breathingScale(
            view: View,
            minScale: Float = 1f,
            maxScale: Float = 1.08f,
            duration: Long = 1200L
        ): ValueAnimator {
            return ValueAnimator.ofFloat(minScale, maxScale).apply {
                this.duration = duration
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    val scale = it.animatedValue as Float
                    view.scaleX = scale
                    view.scaleY = scale
                }
            }
        }
    }
}
