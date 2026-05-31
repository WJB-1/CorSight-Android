package com.corsight.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class YoloV8OnnxEngine(
    private val modelAssetPath: String = "models/yolov8.onnx",
    private val labelAssetPath: String = "models/coco80.txt",
    private val confidenceThreshold: Float = 0.6f,
    private val nmsThreshold: Float = 0.45f
) : ObjectDetector {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var labels: List<String> = emptyList()
    private val inputSize = 640

    @Volatile
    private var released = false
    private val lock = Any()

    override val isReady: Boolean
        get() = ortSession != null

    override fun load(context: Context) {
        released = false
        ortEnv = OrtEnvironment.getEnvironment()
        labels = readLabels(context)
        val modelBytes = context.assets.open(modelAssetPath).readBytes()
        val sessionOptions = OrtSession.SessionOptions()
        ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)
    }

    override fun release() {
        released = true
        synchronized(lock) {
            ortSession?.close()
            ortSession = null
            ortEnv?.close()
            ortEnv = null
        }
    }

    override fun detect(bitmap: Bitmap, rotationDegrees: Int): List<Detection> {
        if (released) return emptyList()
        synchronized(lock) {
            val session = ortSession ?: return emptyList()
            val env = ortEnv ?: return emptyList()

            val source = bitmap.rotate(rotationDegrees.toFloat())
            val letterboxed = source.letterbox(inputSize)
            try {
                val imgData = preProcess(letterboxed.bitmap)
                val inputName = session.inputNames.iterator().next()
                val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
                val tensor = OnnxTensor.createTensor(env, imgData, shape)
                return tensor.use {
                    val output = session.run(Collections.singletonMap(inputName, tensor))
                    output.use {
                        @Suppress("UNCHECKED_CAST")
                        val rawOutput = output.get(0).value as Array<Array<FloatArray>>
                        parseYoloOutput(normalizeYoloOutput(rawOutput), source.width, source.height, letterboxed)
                    }
                }
            } finally {
                if (!source.isRecycled && source !== bitmap) source.recycle()
                if (!letterboxed.bitmap.isRecycled) letterboxed.bitmap.recycle()
            }
        }
    }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        if (degrees == 0f) return this
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.letterbox(targetSize: Int): Letterbox {
        val scale = min(targetSize.toFloat() / width, targetSize.toFloat() / height)
        val resizedW = (width * scale).toInt().coerceAtLeast(1)
        val resizedH = (height * scale).toInt().coerceAtLeast(1)
        val dx = (targetSize - resizedW) / 2f
        val dy = (targetSize - resizedH) / 2f
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val resized = Bitmap.createScaledBitmap(this, resizedW, resizedH, true)
        canvas.drawBitmap(resized, dx, dy, Paint(Paint.FILTER_BITMAP_FLAG))
        if (!resized.isRecycled) resized.recycle()
        return Letterbox(output, scale, dx, dy)
    }

    private fun preProcess(bitmap: Bitmap): FloatBuffer {
        val stride = inputSize * inputSize
        val imgData = FloatBuffer.allocate(1 * 3 * stride)
        imgData.rewind()
        val bmpData = IntArray(stride)
        bitmap.getPixels(bmpData, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val idx = inputSize * i + j
                val pixelValue = bmpData[idx]
                imgData.put(idx, (pixelValue shr 16 and 0xFF) / 255f)
                imgData.put(idx + stride, (pixelValue shr 8 and 0xFF) / 255f)
                imgData.put(idx + stride * 2, (pixelValue and 0xFF) / 255f)
            }
        }
        imgData.rewind()
        return imgData
    }

    private fun normalizeYoloOutput(output: Array<Array<FloatArray>>): Array<FloatArray> {
        require(output.isNotEmpty()) { "YOLO output is empty" }
        val predictions = output[0]
        require(predictions.isNotEmpty()) { "YOLO predictions are empty" }

        val rows = predictions.size
        val columns = predictions[0].size
        require(rows >= 5 && columns > 0) { "Unsupported YOLO output shape: [$rows,$columns]" }

        return if (rows <= columns) {
            predictions
        } else {
            Array(columns) { column ->
                FloatArray(rows) { row -> predictions[row][column] }
            }
        }
    }

    private fun parseYoloOutput(
        predictions: Array<FloatArray>,
        sourceWidth: Int,
        sourceHeight: Int,
        letterbox: Letterbox
    ): List<Detection> {
        val hasObjectness = labels.isNotEmpty() && labels.size == predictions.size - 5
        val classStart = if (hasObjectness) 5 else 4
        val availableClasses = predictions.size - classStart
        val numClasses = if (labels.isNotEmpty()) {
            min(labels.size, availableClasses)
        } else {
            availableClasses
        }
        if (numClasses <= 0) return emptyList()

        val numPredictions = predictions[0].size
        val validPredictions = mutableListOf<Prediction>()

        for (i in 0 until numPredictions) {
            val objectness = if (hasObjectness && i < predictions[4].size) {
                normalizeScore(predictions[4][i])
            } else {
                1f
            }
            var maxScore = 0f
            var maxClassId = 0
            for (c in 0 until numClasses) {
                val classRow = classStart + c
                if (classRow >= predictions.size || i >= predictions[classRow].size) continue
                val score = normalizeScore(predictions[classRow][i]) * objectness
                if (score > maxScore) {
                    maxScore = score
                    maxClassId = c
                }
            }

            if (maxScore >= confidenceThreshold) {
                val cx = predictions[0][i]
                val cy = predictions[1][i]
                val w = predictions[2][i]
                val h = predictions[3][i]
                val x1 = (cx - w / 2 - letterbox.padX) / letterbox.scale
                val y1 = (cy - h / 2 - letterbox.padY) / letterbox.scale
                val x2 = (cx + w / 2 - letterbox.padX) / letterbox.scale
                val y2 = (cy + h / 2 - letterbox.padY) / letterbox.scale
                val clipped = RectF(
                    x1.coerceIn(0f, sourceWidth.toFloat()),
                    y1.coerceIn(0f, sourceHeight.toFloat()),
                    x2.coerceIn(0f, sourceWidth.toFloat()),
                    y2.coerceIn(0f, sourceHeight.toFloat())
                )
                if (clipped.width() > 1f && clipped.height() > 1f) {
                    validPredictions.add(Prediction(clipped, maxScore, maxClassId))
                }
            }
        }

        return nms(validPredictions).map { idx ->
            val p = validPredictions[idx]
            Detection(
                box = p.box,
                score = p.confidence,
                classId = p.classId,
                label = labels.getOrNull(p.classId) ?: "unknown"
            )
        }
    }

    private fun normalizeScore(score: Float): Float {
        if (score.isNaN() || score.isInfinite()) return 0f
        return if (score in 0f..1f) {
            score
        } else {
            (1.0 / (1.0 + exp(-score.toDouble()))).toFloat()
        }
    }

    private fun nms(predictions: List<Prediction>): List<Int> {
        val indices = predictions.indices.sortedByDescending { predictions[it].confidence }
        val selected = mutableListOf<Int>()
        val suppressed = BooleanArray(predictions.size)

        for (i in indices) {
            if (suppressed[i]) continue
            selected.add(i)
            for (j in indices) {
                if (i == j || suppressed[j]) continue
                if (predictions[i].classId != predictions[j].classId) continue
                if (iou(predictions[i].box, predictions[j].box) > nmsThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return selected
    }

    private fun iou(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)
        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = box1.width() * box1.height() + box2.width() * box2.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun readLabels(context: Context): List<String> {
        val lines = context.assets.open(labelAssetPath).bufferedReader().readLines()
        return lines.mapNotNull { line ->
            val cleaned = line.trim().removePrefix("﻿")
            if (cleaned.isEmpty()) return@mapNotNull null
            val parts = cleaned.split(":", limit = 2)
            if (parts.size == 2) parts[1].trim() else cleaned
        }
    }

    private data class Letterbox(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private data class Prediction(
        val box: RectF,
        val confidence: Float,
        val classId: Int
    )
}
