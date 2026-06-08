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

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val detections = mutableListOf<Detection>()
    private val alerts = mutableListOf<ObstacleAlert>()
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

    fun updateDetections(newDetections: List<Detection>, newAlerts: List<ObstacleAlert> = emptyList()) {
        detections.clear()
        detections.addAll(newDetections)
        alerts.clear()
        alerts.addAll(newAlerts)
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
        for (detection in detections) {
            val alert = alerts.firstOrNull { it.detection == detection }
            mapRect(detection.box, mappedRect)
            paintBox.color = colorForAlert(alert)
            canvas.drawRect(mappedRect, paintBox)
            drawLabel(canvas, detection, alert, mappedRect)
        }
    }

    private fun drawRiskZone(canvas: Canvas) {
        val riskZone = ObstacleRiskAnalyzer.riskZone(sourceWidth, sourceHeight)
        mapRect(riskZone, mappedRiskZone)
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

    private fun drawLabel(canvas: Canvas, detection: Detection, alert: ObstacleAlert?, rect: RectF) {
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

        canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, paintTextBg)
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
