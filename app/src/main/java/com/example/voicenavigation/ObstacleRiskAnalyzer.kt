package com.example.voicenavigation

import android.graphics.RectF
import com.corsight.inference.Detection
import kotlin.math.max
import kotlin.math.min

object ObstacleRiskAnalyzer {
    private const val RISK_AREA_RATIO = 0.30f
    private const val RISK_WIDTH_RATIO = 0.60f
    private val riskHeightRatio = RISK_AREA_RATIO / RISK_WIDTH_RATIO

    fun riskZone(imageWidth: Int, imageHeight: Int): RectF {
        val safeWidth = max(1, imageWidth)
        val safeHeight = max(1, imageHeight)
        val zoneWidth = safeWidth * RISK_WIDTH_RATIO
        val zoneHeight = safeHeight * riskHeightRatio
        val left = (safeWidth - zoneWidth) / 2f
        val top = (safeHeight - zoneHeight) / 2f
        return RectF(left, top, left + zoneWidth, top + zoneHeight)
    }

    fun analyze(detections: List<Detection>, imageWidth: Int, imageHeight: Int): List<ObstacleAlert> {
        val zone = riskZone(imageWidth, imageHeight)
        val zoneArea = max(1f, zone.width() * zone.height())
        return detections.mapNotNull { detection ->
            val overlap = intersectionArea(detection.box, zone) / zoneArea
            val urgency = when {
                overlap >= 0.70f -> ObstacleUrgency.HIGH
                overlap >= 0.50f -> ObstacleUrgency.MEDIUM
                overlap >= 0.30f -> ObstacleUrgency.LOW
                else -> return@mapNotNull null
            }
            ObstacleAlert(detection, urgency, overlap.coerceIn(0f, 1f), RectF(zone))
        }
    }

    private fun intersectionArea(first: RectF, second: RectF): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        return max(0f, right - left) * max(0f, bottom - top)
    }
}
