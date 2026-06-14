package com.example.voicenavigation.core.camera

/**
 * 坐标系标识。
 *
 * - WGS84:  GPS 原始坐标，后端存储/计算标准
 * - GCJ02:  国测局坐标（高德/腾讯/百度地图 SDK 返回值）
 * - BD09:   百度坐标（仅百度地图 SDK）
 *
 * 上传时后端据此决定是否需要纠偏。
 */
enum class Crs {
    WGS84,
    GCJ02,
    BD09
}

/**
 * 拍照瞬间的传感器/相机元数据。
 *
 * 与后端 api_contract.md 中 images[] 结构对齐：
 * - bearing → images[].bearing
 * - fovX    → images[].fov
 * - crs     → images[].crs（坐标系标识，后端据此做纠偏）
 */
data class CaptureMetadata(
    val bearing: Float,          // 0~360°，拍摄方位角
    val latitude: Double,
    val longitude: Double,
    val crs: Crs = Crs.GCJ02,   // 坐标系，默认 GCJ02（高德 SDK）
    val fovX: Float,             // 水平视场角（对应后端 fov）
    val fovY: Float,             // 垂直视场角（后端未用，留作扩展）
    val focalLength: Float,      // 当前焦距 mm
    val zoomRatio: Float,        // 变焦倍率
    val timestamp: Long          // 拍摄时间戳
)
