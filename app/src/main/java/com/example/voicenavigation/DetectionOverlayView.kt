package com.example.voicenavigation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.corsight.inference.Detection

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val detections = mutableListOf<Detection>()
    private val paintBox = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.GREEN
    }
    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintTextBg = Paint().apply {
        color = Color.parseColor("#AA000000")
        style = Paint.Style.FILL
    }

    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    fun setTransformations(
        modelInputSize: Int,
        previewWidth: Int,
        previewHeight: Int,
        rotationDegrees: Int
    ) {
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val modelW = if (isRotated) modelInputSize else modelInputSize
        val modelH = if (isRotated) modelInputSize else modelInputSize

        val scaleW = previewWidth.toFloat() / modelW
        val scaleH = previewHeight.toFloat() / modelH
        val scale = minOf(scaleW, scaleH)

        scaleX = scale
        scaleY = scale
        offsetX = (previewWidth - modelW * scale) / 2f
        offsetY = (previewHeight - modelH * scale) / 2f
    }

    fun updateDetections(newDetections: List<Detection>) {
        detections.clear()
        detections.addAll(newDetections)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (detection in detections) {
            val left = detection.box.left * scaleX + offsetX
            val top = detection.box.top * scaleY + offsetY
            val right = detection.box.right * scaleX + offsetX
            val bottom = detection.box.bottom * scaleY + offsetY

            canvas.drawRect(left, top, right, bottom, paintBox)

            val label = "${detection.label} ${(detection.score * 100).toInt()}%"
            val textWidth = paintText.measureText(label)
            val textHeight = paintText.fontMetrics.run { descent - ascent }

            val bgLeft = left
            val bgTop = top - textHeight
            val bgRight = left + textWidth + 8
            val bgBottom = top

            if (bgTop > 0) {
                canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, paintTextBg)
                canvas.drawText(label, bgLeft + 4, top - 4, paintText)
            } else {
                canvas.drawRect(bgLeft, top, bgLeft + textWidth + 8, top + textHeight, paintTextBg)
                canvas.drawText(label, bgLeft + 4, top + textHeight - 4, paintText)
            }
        }
    }
}
