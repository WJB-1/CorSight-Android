package com.example.voicenavigation

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

object ImageQualityAnalyzer {
    const val DEFAULT_MIN_SHARPNESS = 95.0

    data class Result(
        val sharpness: Double,
        val isClear: Boolean
    )

    fun assess(bitmap: Bitmap, minSharpness: Double = DEFAULT_MIN_SHARPNESS): Result {
        val sampleWidth = min(160, bitmap.width).coerceAtLeast(8)
        val sampleHeight = max(8, (bitmap.height * (sampleWidth.toFloat() / bitmap.width)).toInt())
        val sampled = if (bitmap.width == sampleWidth && bitmap.height == sampleHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, false)
        }

        val pixels = IntArray(sampleWidth * sampleHeight)
        sampled.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        if (sampled !== bitmap && !sampled.isRecycled) {
            sampled.recycle()
        }

        val gray = DoubleArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = color shr 16 and 0xFF
            val green = color shr 8 and 0xFF
            val blue = color and 0xFF
            gray[index] = red * 0.299 + green * 0.587 + blue * 0.114
        }

        var count = 0
        var sum = 0.0
        var sumSquares = 0.0
        for (y in 1 until sampleHeight - 1) {
            val row = y * sampleWidth
            for (x in 1 until sampleWidth - 1) {
                val center = gray[row + x]
                val laplacian = gray[row + x - 1] + gray[row + x + 1] +
                        gray[row - sampleWidth + x] + gray[row + sampleWidth + x] -
                        4.0 * center
                sum += laplacian
                sumSquares += laplacian * laplacian
                count++
            }
        }

        val mean = if (count > 0) sum / count else 0.0
        val variance = if (count > 0) sumSquares / count - mean * mean else 0.0
        val sharpness = max(0.0, variance)
        return Result(sharpness, sharpness >= minSharpness)
    }
}
