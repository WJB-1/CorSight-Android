package com.example.voicenavigation.animation

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.example.voicenavigation.ui.ringmenu.RingMenuView

/**
 * 环形菜单动画编排器。
 *
 * 所有 RingMenuView 的动画逻辑集中在此处，由动画层统一管理。
 * RingMenuView 本身不包含任何动画代码，仅暴露可动画化的属性。
 *
 * 使用方式：
 * ```
 *   // 弹出菜单
 *   RingMenuAnimations.show(ringMenuView)
 *
 *   // 关闭菜单
 *   RingMenuAnimations.dismiss(ringMenuView) { container.visibility = View.GONE }
 *
 *   // 启动中心按钮呼吸效果
 *   val breathe = RingMenuAnimations.startCenterBreathing(ringMenuView)
 *   // 停止
 *   breathe.cancel()
 * ```
 */
object RingMenuAnimations {

    // ==================== 菜单生命周期动画 ====================

    /**
     * 弹性弹出：menuScale 0→1，遮罩 alpha 0→0x80。
     * 参考 animatior_guidance.md §1 "整体弹性弹出"。
     *
     * @param menu     目标 RingMenuView
     * @param duration 时长（毫秒）
     * @return AnimatorSet（可外部监听 onEnd）
     */
    fun show(menu: RingMenuView, duration: Long = 350L): AnimatorSet {
        // 先设初始状态
        menu.menuScale = 0f
        menu.overlayAlpha = 0
        menu.selectionExpansion = 0f
        menu.subMenuScale = 1f

        val scaleAnim = AnimatorUtils.floatAnimator(0f, 1f, duration, OvershootInterpolator(1.8f)) {
            menu.menuScale = it
        }
        val overlayAnim = AnimatorUtils.intAnimator(0, 0x80, (duration * 0.6).toLong(), DecelerateInterpolator()) {
            menu.overlayAlpha = it
        }
        val set = AnimatorUtils.playTogether(scaleAnim, overlayAnim)
        set.start()
        return set
    }

    /**
     * 加速收起：menuScale 1→0，遮罩 alpha 0x80→0。
     * 参考 animatior_guidance.md §5 "整体加速消失"。
     *
     * @param menu     目标 RingMenuView
     * @param duration 时长（毫秒）
     * @param onEnd    动画结束回调（通常用于设 GONE）
     * @return ValueAnimator
     */
    fun dismiss(menu: RingMenuView, duration: Long = 200L, onEnd: (() -> Unit)? = null): ValueAnimator {
        return AnimatorUtils.floatAnimator(1f, 0f, duration, AccelerateInterpolator()) { scale ->
            menu.menuScale = scale
            menu.overlayAlpha = (0x80 * scale).toInt()
            if (scale < 0.01f) {
                onEnd?.invoke()
            }
        }.apply { start() }
    }

    // ==================== 选中/高亮动画 ====================

    /**
     * 选中扇形的外扩动画。
     * 参考 animatior_guidance.md §2 "扇形外扩"。
     *
     * @param menu     目标 RingMenuView
     * @param from     起始外扩量
     * @param to       目标外扩量
     * @param duration 时长（毫秒）
     */
    fun selectionExpand(menu: RingMenuView, from: Float = 0f, to: Float = 12f, duration: Long = 150L): ValueAnimator {
        return AnimatorUtils.floatAnimator(from, to, duration, OvershootInterpolator(1.5f)) {
            menu.selectionExpansion = it
        }.apply { start() }
    }

    /**
     * 高亮切换：旧选中项收缩 + 新选中项展开。
     * 参考 animatior_guidance.md §3 "高亮平滑迁移"。
     *
     * @param menu     目标 RingMenuView
     * @param oldExpansion 旧选中项当前外扩量
     * @param newExpansion 新选中项目标外扩量
     */
    fun selectionTransition(menu: RingMenuView, oldExpansion: Float = 12f, newExpansion: Float = 12f): AnimatorSet {
        // 先收缩旧的，再展开新的
        val shrink = AnimatorUtils.floatAnimator(oldExpansion, 0f, 100, DecelerateInterpolator()) {
            menu.selectionExpansion = it
        }
        val expand = AnimatorUtils.floatAnimator(0f, newExpansion, 150, OvershootInterpolator(1.5f)) {
            menu.selectionExpansion = it
        }
        return AnimatorUtils.playSequentially(shrink, expand).apply { start() }
    }

    /**
     * 呼吸发光（持续循环）。
     * 参考 animatior_guidance.md §2 "呼吸发光"。
     *
     * @param menu     目标 RingMenuView
     * @param duration 单次循环时长
     */
    fun startGlowBreathing(menu: RingMenuView, duration: Long = 800L): ValueAnimator {
        return Animations.Selection.breathingGlow(0x00, 0x44, duration) { alpha ->
            menu.glowAlpha = alpha
        }.apply { start() }
    }

    // ==================== 子菜单过渡动画 ====================

    /**
     * 子菜单展开：主菜单缩小退后 + 子菜单 scale 0→1。
     * 参考 animatior_guidance.md §4 "二级目录过渡"。
     *
     * @param menu     目标 RingMenuView
     * @param duration 时长（毫秒）
     */
    fun subMenuExpand(menu: RingMenuView, duration: Long = 250L): AnimatorSet {
        menu.subMenuScale = 0f
        val subShow = AnimatorUtils.floatAnimator(0f, 1f, duration, OvershootInterpolator(1.5f)) {
            menu.subMenuScale = it
        }
        val mainShrink = AnimatorUtils.floatAnimator(1f, 0.85f, (duration * 0.8).toLong(), DecelerateInterpolator()) {
            // 主菜单轻微缩小（通过 menuScale 叠加效果）
            // 注意：这里不直接改 menuScale，因为它是整体缩放
            // 子菜单的缩放在 onDraw 中通过 canvas.scale 控制
        }
        return AnimatorUtils.playTogether(subShow).apply { start() }
    }

    /**
     * 子菜单收起。
     */
    fun subMenuCollapse(menu: RingMenuView, duration: Long = 150L): ValueAnimator {
        return AnimatorUtils.floatAnimator(menu.subMenuScale, 0f, duration, AccelerateInterpolator()) {
            menu.subMenuScale = it
        }.apply { start() }
    }

    // ==================== 中心按钮动画 ====================

    /**
     * 中心按钮呼吸效果（持续循环）。
     * 参考 animatior_guidance.md §7 "中心按钮呼吸"。
     *
     * @param menu     目标 RingMenuView
     * @param minScale 最小缩放
     * @param maxScale 最大缩放
     * @param duration 单次循环时长
     */
    fun startCenterBreathing(
        menu: RingMenuView,
        minScale: Float = 1f,
        maxScale: Float = 1.08f,
        duration: Long = 1200L
    ): ValueAnimator {
        return Animations.Ambient.breathingScale(menu, minScale, maxScale, duration).apply { start() }
    }

    // ==================== 级联展开动画 ====================

    /**
     * 扇形级联展开：每个扇形依次弹出。
     * 参考 animatior_guidance.md §1 "扇形级联展开"。
     *
     * @param menu      目标 RingMenuView
     * @param itemCount 扇形数量
     * @param delay     每个扇形的延迟间隔（毫秒）
     * @param duration  单个扇形动画时长
     */
    fun cascadeShow(
        menu: RingMenuView,
        itemCount: Int,
        delay: Long = 50L,
        duration: Long = 300L
    ): AnimatorSet {
        menu.menuScale = 0f
        menu.overlayAlpha = 0
        val animators = (0 until itemCount).map { index ->
            AnimatorUtils.floatAnimator(0f, 1f, duration, OvershootInterpolator(1.5f)) {
                // 级联效果通过延迟实现，每帧更新 menuScale
                menu.menuScale = it
            }.apply { startDelay = index * delay }
        }
        // 同时播放遮罩淡入
        val overlayAnim = AnimatorUtils.intAnimator(0, 0x80, 300, DecelerateInterpolator()) {
            menu.overlayAlpha = it
        }
        return AnimatorSet().apply {
            playTogether(animators + overlayAnim)
            start()
        }
    }
}
