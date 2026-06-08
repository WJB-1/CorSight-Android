package com.example.voicenavigation.collection

data class CaptureTask(
    val pointId: String,
    val chunkId: String,
    val latitude: Double,
    val longitude: Double,
    val sceneDescription: String,
    val images: MutableMap<String, String> = mutableMapOf(),
    var status: String = "pending",
    val createdAt: String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()),
    var updatedAt: String = createdAt,
    var uploadedAt: String? = null
)
