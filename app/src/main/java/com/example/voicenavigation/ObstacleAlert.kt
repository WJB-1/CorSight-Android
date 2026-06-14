package com.example.voicenavigation

import android.graphics.RectF
import com.corsight.inference.Detection

enum class ObstacleUrgency {
    LOW,
    MEDIUM,
    HIGH
}

data class ObstacleAlert(
    val detection: Detection,
    val urgency: ObstacleUrgency,
    val overlapRatio: Float,
    val riskZone: RectF
)
