package com.corsight.inference

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class YoloV8OnnxEngine(
    private val modelAssetPath: String = "models/yolov8.onnx",
    private val labelAssetPath: String = "models/coco80.txt",
    private val confidenceThreshold: Float = 0.5f,
    private val nmsThreshold: Float = 0.45f
) : ObjectDetector {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var labels: List<String> = emptyList()
    private val inputSize = 640
    private val numClasses = 80

    override val isReady: Boolean
        get() = ortSession != null

    override fun load(context: Context) {
        ortEnv = OrtEnvironment.getEnvironment()
        labels = readLabels(context)
        val modelBytes = context.assets.open(modelAssetPath).readBytes()
        val sessionOptions = OrtSession.SessionOptions()
        ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)
    }

    override fun release() {
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
    }

    override fun detect(bitmap: Bitmap, rotationDegrees: Int): List<Detection> {
        val session = ortSession ?: return emptyList()
        val env = ortEnv ?: return emptyList()

        val startTime = SystemClock.uptimeMillis()

        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        val rotated = scaled.rotate(rotationDegrees.toFloat())
        val imgData = preProcess(rotated)

        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val tensor = OnnxTensor.createTensor(env, imgData, shape)

        val result = tensor.use {
            val output = session.run(Collections.singletonMap(inputName, tensor))
            output.use {
                @Suppress("UNCHECKED_CAST")
                val rawOutput = output.get(0).value as Array<Array<FloatArray>>
                parseYoloOutput(rawOutput)
            }
        }

        val elapsed = SystemClock.uptimeMillis() - startTime
        return result.map { it.copy(score = it.score) }
    }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
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

    private fun parseYoloOutput(output: Array<Array<FloatArray>>): List<Detection> {
        val predictions = output[0]
        val numPredictions = predictions[0].size

        val validPredictions = mutableListOf<Prediction>()

        for (i in 0 until numPredictions) {
            var maxScore = 0f
            var maxClassId = 0
            for (c in 0 until numClasses) {
                val score = predictions[c + 4][i]
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
                val x1 = cx - w / 2
                val y1 = cy - h / 2
                val x2 = cx + w / 2
                val y2 = cy + h / 2
                validPredictions.add(Prediction(x1, y1, x2, y2, maxScore, maxClassId))
            }
        }

        return nms(validPredictions).map { idx ->
            val p = validPredictions[idx]
            Detection(
                box = RectF(p.x1, p.y1, p.x2, p.y2),
                score = p.confidence,
                classId = p.classId,
                label = labels.getOrNull(p.classId) ?: "unknown"
            )
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
                val box1 = predictions[i]
                val box2 = predictions[j]
                if (box1.classId != box2.classId) continue

                val x1 = maxOf(box1.x1, box2.x1)
                val y1 = maxOf(box1.y1, box2.y1)
                val x2 = minOf(box1.x2, box2.x2)
                val y2 = minOf(box1.y2, box2.y2)

                val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
                val area1 = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
                val area2 = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
                val union = area1 + area2 - intersection
                val iou = if (union > 0) intersection / union else 0f

                if (iou > nmsThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return selected
    }

    private fun readLabels(context: Context): List<String> {
        val lines = context.assets.open(labelAssetPath).bufferedReader().readLines()
        return lines.map { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) parts[1].trim() else line.trim()
        }
    }

    private data class Prediction(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val confidence: Float, val classId: Int
    )
}
