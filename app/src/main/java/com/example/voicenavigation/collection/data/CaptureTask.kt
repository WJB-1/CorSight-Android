package com.example.voicenavigation.collection.data

/**
 * 一个采样点的完整数据。
 *
 * @param pointId    全局唯一 ID，格式 P_{timestamp}_{random5}，对应后端 point_id
 * @param latitude   纬度 WGS-84
 * @param longitude  经度 WGS-84
 * @param sceneDescription 场景描述，对应后端 scene_description
 * @param mode       采集模式：FREE（自由拍照）或 GRID（八方向）
 * @param photos     该点位的所有照片
 * @param uploadSessionId 后端返回的 session_id，上传元数据后获得
 * @param status     任务整体状态
 */
data class CaptureTask(
    val pointId: String,
    val latitude: Double,
    val longitude: Double,
    val sceneDescription: String,
    val mode: CaptureMode,
    val photos: MutableList<PhotoRecord> = mutableListOf(),
    var uploadSessionId: String? = null,
    var status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt
)

enum class CaptureMode {
    FREE,   // 自由拍照
    GRID    // 八方向
}

enum class TaskStatus {
    PENDING,    // 待上传
    UPLOADING,  // 上传中
    SUCCESS,    // 全部上传成功
    FAILED      // 部分/全部上传失败
}
