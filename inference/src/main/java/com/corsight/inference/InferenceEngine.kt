package com.corsight.inference

import android.content.Context

interface InferenceEngine {
    fun load(context: Context)
    fun release()
    val isReady: Boolean
}

interface ObjectDetector : InferenceEngine {
    fun detect(bitmap: android.graphics.Bitmap, rotationDegrees: Int): List<Detection>
}

data class Detection(
    val box: android.graphics.RectF,
    val score: Float,
    val classId: Int,
    val label: String
)
