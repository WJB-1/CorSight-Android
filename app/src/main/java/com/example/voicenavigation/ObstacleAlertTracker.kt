package com.example.voicenavigation

import com.corsight.inference.Detection

data class ObstacleSpeechEvent(
    val label: String,
    val urgency: ObstacleUrgency,
    val message: String,
    val count: Int
)

class ObstacleAlertTracker {

    companion object {
        private const val MISSING_FRAME_RESET_THRESHOLD = 5
    }

    private data class TargetState(
        var count: Int,
        var highestAnnouncedUrgency: ObstacleUrgency,
        var missingFrames: Int = 0
    )

    private val targetStates = mutableMapOf<String, TargetState>()
    private val activeLabels = mutableListOf<String>()

    fun update(detections: List<Detection>, alerts: List<ObstacleAlert>): List<ObstacleSpeechEvent> {
        val detectedLabels = detections.map { normalizeLabel(it.label) }
            .filter { it.isNotEmpty() }
            .toSet()

        updateMissingTargets(detectedLabels)

        val strongestAlerts = alerts
            .filter { normalizeLabel(it.detection.label).isNotEmpty() }
            .groupBy { normalizeLabel(it.detection.label) }
            .mapValues { (_, labelAlerts) -> labelAlerts.maxBy { it.urgency.ordinal } }

        val events = mutableListOf<ObstacleSpeechEvent>()
        for ((label, alert) in strongestAlerts) {
            val state = targetStates[label]
            if (state == null) {
                val newState = TargetState(count = 1, highestAnnouncedUrgency = alert.urgency)
                targetStates[label] = newState
                if (!activeLabels.contains(label)) activeLabels.add(label)
                events.add(alert.toSpeechEvent(label, newState.count))
            } else {
                state.count += 1
                state.missingFrames = 0
                if (alert.urgency.ordinal > state.highestAnnouncedUrgency.ordinal) {
                    state.highestAnnouncedUrgency = alert.urgency
                    events.add(alert.toSpeechEvent(label, state.count))
                }
            }
        }

        for (label in detectedLabels) {
            if (label !in strongestAlerts) {
                targetStates[label]?.let {
                    it.count += 1
                    it.missingFrames = 0
                }
            }
        }

        return events
    }

    fun reset() {
        targetStates.clear()
        activeLabels.clear()
    }

    fun trackedLabels(): List<String> = activeLabels.toList()

    private fun updateMissingTargets(detectedLabels: Set<String>) {
        val iterator = targetStates.keys.iterator()
        while (iterator.hasNext()) {
            val label = iterator.next()
            if (label !in detectedLabels) {
                val state = targetStates[label] ?: continue
                state.missingFrames += 1
                if (state.missingFrames >= MISSING_FRAME_RESET_THRESHOLD) {
                    iterator.remove()
                    activeLabels.remove(label)
                }
            }
        }
    }

    private fun ObstacleAlert.toSpeechEvent(label: String, count: Int): ObstacleSpeechEvent {
        val message = when (urgency) {
            ObstacleUrgency.LOW -> "请注意，不远处有$label"
            ObstacleUrgency.MEDIUM -> "请注意，正在接近$label"
            ObstacleUrgency.HIGH -> "请注意，已靠近$label"
        }
        return ObstacleSpeechEvent(label, urgency, message, count)
    }

    private fun normalizeLabel(label: String): String {
        return label.trim()
    }
}
