package com.example.voicenavigation.animation

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.example.voicenavigation.animation.AnimatorUtils.onEnd

/**
 * 底部语音交互区域动画编排器。
 *
 * 管理语音区域的所有动画：
 * - 按下反馈：卡片微缩 + 毛玻璃增强
 * - 上滑跟随：手指拖拽时卡片上移 + 缩小 + 颜色渐变
 * - 超阈值震动：进入取消区的触觉反馈
 * - 松手回弹：弹性弹回原位
 * - 取消淡出：取消后快速恢复
 *
 * 使用方式（在 MainActivity.setupVoiceZone 中）：
 * ```
 *   ACTION_DOWN → VoiceZoneAnimations.onPress(voiceZone)
 *   ACTION_MOVE → VoiceZoneAnimations.onDrag(voiceZone, deltaY, threshold)
 *   ACTION_UP   → VoiceZoneAnimations.onRelease(voiceZone, isCancelled)
 * ```
 */
object VoiceZoneAnimations {

    // ==================== 按下反馈 ====================

    /**
     * 按下瞬间：卡片微缩到 0.97（100ms）。
     *
     * @param zone 语音区域 View
     */
    fun onPress(zone: View) {
        zone.animate().cancel()
        zone.animate()
            .scaleX(0.97f).scaleY(0.97f)
            .setDuration(100)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ==================== 上滑跟随 ====================

    /**
     * 手指拖拽时实时更新卡片位置和缩放。
     *
     * 直接设置属性（不启动动画），制造即时跟随的阻尼感：
     * - 上移距离 = deltaY * 0.5（阻尼系数，手指滑 100px 卡片只移 50px）
     * - 缩放 = 1 - (deltaY/threshold) * 0.12，最大缩到 0.88
     * - deltaY 负值（向下滑）时忽略
     *
     * @param zone      语音区域 View
     * @param deltaY    手指上滑距离（正数 = 上滑）
     * @param threshold 取消阈值（像素）
     */
    fun onDrag(zone: View, deltaY: Float, threshold: Float) {
        if (deltaY <= 0f) {
            // 回到原位
            zone.translationY = 0f
            zone.scaleX = 1f
            zone.scaleY = 1f
            return
        }
        // 阻尼跟随
        zone.translationY = -(deltaY * 0.5f)
        // 跟随缩放
        val progress = (deltaY / threshold).coerceIn(0f, 1f)
        val scale = 1f - progress * 0.12f  // 1.0 → 0.88
        zone.scaleX = scale
        zone.scaleY = scale
    }

    // ==================== 进入/离开取消区 ====================

    /**
     * 获取当前上滑进度（0~1），用于 UI 层判断是否进入取消区。
     *
     * @param deltaY    手指上滑距离
     * @param threshold 取消阈值
     * @return 0 = 未滑动，1 = 已到阈值
     */
    fun getDragProgress(deltaY: Float, threshold: Float): Float {
        return (deltaY / threshold).coerceIn(0f, 1f)
    }

    // ==================== 松手回弹 ====================

    /**
     * 松手时弹性弹回原位（300ms，带 OvershootInterpolator）。
     *
     * @param zone 语音区域 View
     * @param onEnd 回弹结束回调（可选）
     */
    fun onRelease(zone: View, onEnd: (() -> Unit)? = null) {
        zone.animate().cancel()
        zone.animate()
            .translationY(0f)
            .scaleX(1f).scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.5f))
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    // ==================== 取消后恢复 ====================

    /**
     * 取消操作后的快速恢复动画（200ms）。
     *
     * @param zone 语音区域 View
     */
    fun onCancelled(zone: View) {
        zone.animate().cancel()
        zone.animate()
            .translationY(0f)
            .scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
