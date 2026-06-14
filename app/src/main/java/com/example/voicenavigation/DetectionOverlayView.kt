package com.example.voicenavigation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.corsight.inference.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * 检测结果覆盖层 View。
 *
 * 绘制检测边界框和障碍物风险区域。
 *
 * 动画策略：View 本身不包含任何动画逻辑，仅暴露可被动画层驱动的属性。
 * 动画由 animation 包中的 DetectionOverlayAnimations 统一管理。
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val detections = mutableListOf<Detection>()
    private val alerts = mutableListOf<ObstacleAlert>()

    // 旧检测数据（用于动画过渡时的插值起点）
    private val prevDetections = mutableListOf<Detection>()
    private val prevAlerts = mutableListOf<ObstacleAlert>()

    private val mappedRect = RectF()
    private val mappedRiskZone = RectF()

    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.GREEN
    }
    private val paintRiskZone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#CCFFC107")
    }
    private val paintRiskFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#22FFC107")
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintTextBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000")
        style = Paint.Style.FILL
    }

    private var sourceWidth = 1
    private var sourceHeight = 1
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // ==================== 可动画化属性（供动画层读写） ====================

    /**
     * 检测框过渡进度（0f ~ 1f）。
     * 动画层驱动此属性，在新旧检测结果之间插值：
     * - 0f = 完全显示旧位置
     * - 1f = 完全显示新位置
     */
    var transitionProgress: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 检测框描边 alpha（0 ~ 255）。
     * 动画层驱动此属性实现检测框的淡入效果。
     */
    var boxAlpha: Int = 255
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 风险区域闪烁 alpha 偏移（0 ~ 0x44 左右）。
     * 动画层通过呼吸动画驱动，叠加在基础 alpha 上。
     */
    var riskZoneGlowAlpha: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    // ==================== 查询接口（供动画层读取） ====================

    fun getDetections(): List<Detection> = detections
    fun getAlerts(): List<ObstacleAlert> = alerts
    fun getPrevDetections(): List<Detection> = prevDetections
    fun getPrevAlerts(): List<ObstacleAlert> = prevAlerts

    // ==================== 数据更新 API ====================

    fun setSourceImageSize(imageWidth: Int, imageHeight: Int) {
        sourceWidth = max(1, imageWidth)
        sourceHeight = max(1, imageHeight)
        recomputeTransform()
    }

    @Deprecated("Use setSourceImageSize(imageWidth, imageHeight).")
    fun setTransformations(
        modelInputSize: Int,
        previewWidth: Int,
        previewHeight: Int,
        rotationDegrees: Int
    ) {
        setSourceImageSize(modelInputSize, modelInputSize)
    }

    /**
     * 更新检测结果。
     *
     * 保存旧数据用于动画过渡插值，然后替换为新数据。
     * 动画层应在此调用后启动 transitionProgress 动画（0→1）。
     */
    fun updateDetections(newDetections: List<Detection>, newAlerts: List<ObstacleAlert> = emptyList()) {
        // 保存旧数据
        prevDetections.clear()
        prevDetections.addAll(detections)
        prevAlerts.clear()
        prevAlerts.addAll(alerts)

        // 替换新数据
        detections.clear()
        detections.addAll(newDetections)
        alerts.clear()
        alerts.addAll(newAlerts)

        // 重置过渡进度（动画层将驱动它从 0 到 1）
        transitionProgress = 1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeTransform()
    }

    private fun recomputeTransform() {
        if (width <= 0 || height <= 0) return
        val scaleW = width.toFloat() / sourceWidth
        val scaleH = height.toFloat() / sourceHeight
        scale = min(scaleW, scaleH)
        offsetX = (width - sourceWidth * scale) / 2f
        offsetY = (height - sourceHeight * scale) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawRiskZone(canvas)

        val t = transitionProgress
        val alpha = boxAlpha

        for ((i, detection) in detections.withIndex()) {
            val alert = alerts.firstOrNull { it.detection == detection }

            // 插值：如果过渡中且有旧数据，lerp 旧位置→新位置
            if (t < 1f && i < prevDetections.size) {
                val prevBox = prevDetections[i].box
                val newBox = detection.box
                val lerped = RectF(
                    prevBox.left + (newBox.left - prevBox.left) * t,
                    prevBox.top + (newBox.top - prevBox.top) * t,
                    prevBox.right + (newBox.right - prevBox.right) * t,
                    prevBox.bottom + (newBox.bottom - prevBox.bottom) * t
                )
                mapRect(lerped, mappedRect)
            } else {
                mapRect(detection.box, mappedRect)
            }

            paintBox.color = colorForAlert(alert)
            paintBox.alpha = alpha
            canvas.drawRect(mappedRect, paintBox)
            drawLabel(canvas, detection, alert, mappedRect, alpha)
        }
    }

    private fun drawRiskZone(canvas: Canvas) {
        val riskZone = ObstacleRiskAnalyzer.riskZone(sourceWidth, sourceHeight)
        mapRect(riskZone, mappedRiskZone)
        // 风险区域填充（叠加呼吸闪烁）
        val baseFillAlpha = 0x22
        paintRiskFill.alpha = (baseFillAlpha + riskZoneGlowAlpha).coerceIn(0, 0xFF)
        canvas.drawRect(mappedRiskZone, paintRiskFill)
        canvas.drawRect(mappedRiskZone, paintRiskZone)
    }

    private fun mapRect(source: RectF, out: RectF) {
        out.set(
            source.left * scale + offsetX,
            source.top * scale + offsetY,
            source.right * scale + offsetX,
            source.bottom * scale + offsetY
        )
        out.left = out.left.coerceIn(0f, width.toFloat())
        out.top = out.top.coerceIn(0f, height.toFloat())
        out.right = out.right.coerceIn(0f, width.toFloat())
        out.bottom = out.bottom.coerceIn(0f, height.toFloat())
    }

    private fun drawLabel(canvas: Canvas, detection: Detection, alert: ObstacleAlert?, rect: RectF, alpha: Int = 255) {
        val urgencyText = when (alert?.urgency) {
            ObstacleUrgency.LOW -> "低"
            ObstacleUrgency.MEDIUM -> "中"
            ObstacleUrgency.HIGH -> "高"
            null -> ""
        }
        val label = if (urgencyText.isEmpty()) {
            "${detection.label} ${(detection.score * 100).toInt()}%"
        } else {
            "${detection.label} $urgencyText ${(alert!!.overlapRatio * 100).toInt()}%"
        }
        val textWidth = paintText.measureText(label)
        val textHeight = paintText.fontMetrics.run { descent - ascent }
        val bgLeft = rect.left
        val bgTop = max(0f, rect.top - textHeight - 8f)
        val bgRight = min(width.toFloat(), bgLeft + textWidth + 12f)
        val bgBottom = bgTop + textHeight + 8f

        paintTextBg.alpha = (0xAA * alpha / 255).toInt()
        canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, paintTextBg)
        paintText.alpha = alpha
        canvas.drawText(label, bgLeft + 6f, bgBottom - 7f, paintText)
    }

    private fun colorForAlert(alert: ObstacleAlert?): Int {
        return when (alert?.urgency) {
            ObstacleUrgency.LOW -> Color.parseColor("#FFFFC107")
            ObstacleUrgency.MEDIUM -> Color.parseColor("#FFFF9800")
            ObstacleUrgency.HIGH -> Color.parseColor("#FFF44336")
            null -> Color.GREEN
        }
    }
}
