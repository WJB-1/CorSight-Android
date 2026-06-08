package com.corsight.vision

import android.graphics.Bitmap

interface ImageSource {
    val displayName: String
    fun start(onFrame: (bitmap: Bitmap, rotationDegrees: Int) -> Unit): Boolean
    fun stop()
    val isRunning: Boolean
}
