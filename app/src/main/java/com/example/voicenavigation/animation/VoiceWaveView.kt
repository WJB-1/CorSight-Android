package com.example.voicenavigation.animation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * 声波可视化 View（伪振幅驱动）。
 *
 * 5 根圆角竖条，高度由循环正弦波 + 随机抖动驱动，
 * 经一阶低通滤波平滑，产生类似 Siri 的"呼吸跳动"效果。
 *
 * 不接入真实音量 — 视障用户看不到，纯视觉装饰。
 *
 * 使用方式：
 * ```
 *   val waveView = findViewById<VoiceWaveView>(R.id.voiceWaveView)
 *   // 录音开始
 *   waveView.startWave()
 *   // 录音结束
 *   waveView.stopWave()
 * ```
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 5
        private const val UPDATE_INTERVAL_MS = 50L  // 20fps，足够流畅且省电
        private const val LOW_PASS_ALPHA = 0.3f     // 低通滤波系数
        private const val MIN_AMPLITUDE = 0.15f     // 最小振幅（避免完全静止）
        private const val MAX_AMPLITUDE = 1.0f
    }

    // 每根竖条的当前平滑振幅（归一化 0~1）
    private val amplitudes = FloatArray(BAR_COUNT) { MIN_AMPLITUDE }

    // 每根竖条的相位偏移（制造流动感）
    private val phases = floatArrayOf(0f, 0.8f, 1.6f, 2.4f, 3.2f)

    // 频率系数（每根竖条略有不同）
    private val frequencies = floatArrayOf(3.0f, 3.5f, 4.0f, 3.2f, 3.8f)

    private var isRunning = false
    private var tickCount = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val paintBar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val barRect = RectF()

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            tickCount++
            updateAmplitudes()
            invalidate()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    /**
     * 开始声波动画。
     */
    fun startWave() {
        if (isRunning) return
        isRunning = true
        tickCount = 0
        handler.post(updateRunnable)
    }

    /**
     * 停止声波动画，所有竖条平滑回到最小振幅。
     */
    fun stopWave() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        // 不立即停止 — 让低通滤波自然回落到最小值
        post { animateFadeOut() }
    }

    /**
     * 设置竖条颜色。
     */
    fun setBarColor(color: Int) {
        paintBar.color = color
        invalidate()
    }

    private fun animateFadeOut() {
        // 用一个短定时器驱动回落动画
        val fadeHandler = Handler(Looper.getMainLooper())
        val fadeRunnable = object : Runnable {
            override fun run() {
                var anyAboveMin = false
                for (i in amplitudes.indices) {
                    amplitudes[i] += LOW_PASS_ALPHA * (MIN_AMPLITUDE * 0.3f - amplitudes[i])
                    if (amplitudes[i] > MIN_AMPLITUDE * 0.35f) anyAboveMin = true
                }
                invalidate()
                if (anyAboveMin) fadeHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        fadeHandler.post(fadeRunnable)
    }

    private fun updateAmplitudes() {
        val time = tickCount * UPDATE_INTERVAL_MS / 1000f  // 秒
        for (i in amplitudes.indices) {
            // 目标振幅 = 正弦波基座 + 随机抖动
            val sinValue = sin((time * frequencies[i] + phases[i]).toDouble()).toFloat()
            val base = 0.5f + 0.5f * sinValue  // 0~1
            val jitter = (Math.random().toFloat() - 0.5f) * 0.3f  // -0.15~+0.15
            val target = (base + jitter).coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)

            // 一阶低通滤波平滑
            amplitudes[i] += LOW_PASS_ALPHA * (target - amplitudes[i])
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val totalBarWidth = width * 0.7f  // 竖条总宽度占 View 的 70%
        val barWidth = totalBarWidth / (BAR_COUNT * 1.5f)  // 竖条宽度，1.5x 间距比
        val gap = barWidth * 0.5f
        val startX = (width - totalBarWidth) / 2f
        val centerY = height / 2f
        val maxBarHeight = height * 0.85f
        val cornerRadius = barWidth / 2f

        for (i in 0 until BAR_COUNT) {
            val amp = amplitudes[i]
            val barHeight = (amp * maxBarHeight).coerceAtLeast(barWidth)  // 最小高度 = 宽度（圆形）
            val x = startX + i * (barWidth + gap)

            barRect.set(
                x,
                centerY - barHeight / 2f,
                x + barWidth,
                centerY + barHeight / 2f
            )
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paintBar)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }
}
