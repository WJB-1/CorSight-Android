package com.example.voicenavigation.core.camera

/**
 * 拍照瞬间的传感器/相机元数据。
 *
 * 与后端 api_contract.md 中 images[] 结构对齐：
 * - bearing → images[].bearing
 * - fovX    → images[].fov
 */
data class CaptureMetadata(
    val bearing: Float,          // 0~360°，拍摄方位角
    val latitude: Double,        // WGS-84
    val longitude: Double,       // WGS-84
    val fovX: Float,             // 水平视场角（对应后端 fov）
    val fovY: Float,             // 垂直视场角（后端未用，留作扩展）
    val focalLength: Float,      // 当前焦距 mm
    val zoomRatio: Float,        // 变焦倍率
    val timestamp: Long          // 拍摄时间戳
)
