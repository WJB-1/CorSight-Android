package com.corsight.vision

import android.graphics.Bitmap

data class Frame(
    val bitmap: Bitmap,
    val rotationDegrees: Int,
    val timestamp: Long = System.currentTimeMillis()
)
