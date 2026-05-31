package com.corsight.vision.tools

import android.content.Context
import com.corsight.inference.ModelRegistry
import com.corsight.inference.YoloV8OnnxEngine
import com.corsight.vision.Frame
import com.corsight.vision.ToolResult
import com.corsight.vision.VisionTool

class GenericDetectionTool : VisionTool {
    override val id: String = "generic_detection"
    override val displayName: String = "通用目标检测"

    companion object {
        const val MODEL_ID = "yolov8"
        const val MODEL_ASSET_PATH = "models/best.onnx"
        const val LABEL_ASSET_PATH = "models/coco80.txt"
        const val CONFIDENCE_THRESHOLD = 0.6f
        const val NMS_THRESHOLD = 0.45f
    }

    override fun onActivate(context: Context) {
        if (ModelRegistry.getDetector(MODEL_ID) == null) {
            val engine = YoloV8OnnxEngine(
                modelAssetPath = MODEL_ASSET_PATH,
                labelAssetPath = LABEL_ASSET_PATH,
                confidenceThreshold = CONFIDENCE_THRESHOLD,
                nmsThreshold = NMS_THRESHOLD
            )
            ModelRegistry.register(MODEL_ID, engine)
            engine.load(context)
        }
    }

    override fun onDeactivate() {
        ModelRegistry.release(MODEL_ID)
    }

    override fun process(frame: Frame): ToolResult {
        val detector = ModelRegistry.getDetector(MODEL_ID) ?: return ToolResult.Nothing
        val detections = detector.detect(frame.bitmap, frame.rotationDegrees)
        return if (detections.isNotEmpty()) {
            ToolResult.Detections(detections)
        } else {
            ToolResult.Nothing
        }
    }
}
