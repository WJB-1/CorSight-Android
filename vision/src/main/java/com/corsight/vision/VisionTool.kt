package com.corsight.vision

import android.content.Context

interface VisionTool {
    val id: String
    val displayName: String
    fun onActivate(context: Context)
    fun onDeactivate()
    fun process(frame: Frame): ToolResult
}

sealed class ToolResult {
    data class Detections(val items: List<com.corsight.inference.Detection>) : ToolResult()
    data class Announcement(val text: String, val urgent: Boolean = false) : ToolResult()
    object Nothing : ToolResult()
}
