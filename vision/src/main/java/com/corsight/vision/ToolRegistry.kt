package com.corsight.vision

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ToolRegistry {
    private val tools = mutableMapOf<String, VisionTool>()
    private val _activeTool = MutableStateFlow<VisionTool?>(null)
    val activeTool: StateFlow<VisionTool?> = _activeTool.asStateFlow()

    fun register(tool: VisionTool) {
        tools[tool.id] = tool
    }

    fun activate(context: Context, id: String) {
        val current = _activeTool.value
        if (current?.id == id) return
        current?.onDeactivate()
        val next = tools[id]
        next?.onActivate(context)
        _activeTool.value = next
    }

    fun deactivate() {
        _activeTool.value?.onDeactivate()
        _activeTool.value = null
    }

    fun get(id: String): VisionTool? = tools[id]

    fun allTools(): List<VisionTool> = tools.values.toList()

    fun releaseAll() {
        deactivate()
        tools.clear()
    }

    /** 从外部传入 Bitmap 进行检测，不依赖 CameraX */
    fun processBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        source: String = "external"
    ): ToolResult {
        return activeTool.value?.process(
            Frame(bitmap, rotationDegrees, source = source)
        ) ?: ToolResult.Nothing
    }
}
