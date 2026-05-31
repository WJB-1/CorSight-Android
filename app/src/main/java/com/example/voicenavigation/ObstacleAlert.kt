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

interface ObstacleWarningListener {
    fun onObstacleWarnings(alerts: List<ObstacleAlert>)
}

interface ObstacleSpeechListener {
    fun onObstacleSpeech(event: ObstacleSpeechEvent)
}

object ObstacleWarningNotifier {
    @Volatile
    var listener: ObstacleWarningListener? = null

    @Volatile
    var speechListener: ObstacleSpeechListener? = null

    fun dispatch(alerts: List<ObstacleAlert>) {
        listener?.onObstacleWarnings(alerts)
    }

    fun dispatchSpeech(event: ObstacleSpeechEvent) {
        speechListener?.onObstacleSpeech(event)
    }
}
