package com.example.voicenavigation.collection.data

import java.util.UUID

/**
 * 单张照片记录，与后端 api_contract.md 中 images[] 结构对齐。
 *
 * @param bearing  拍摄方位角 0~360°，对应后端 images[].bearing
 * @param fov      水平视场角，对应后端 images[].fov（默认 90°）
 * @param description 该方位的文字描述，对应后端 images[].description
 * @param label    场景类型标注（自由模式用），如 "天桥"、"复杂路口"
 * @param direction 方向字符串（八方向模式用），如 "N"、"NE"；自由模式为 null
 */
data class PhotoRecord(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    val bearing: Float,
    val fov: Float = 90f,
    val description: String = "",
    val label: String = "",
    val direction: String? = null,
    var uploadStatus: UploadStatus = UploadStatus.PENDING,
    var remotePath: String? = null
)

enum class UploadStatus {
    PENDING,    // 等待上传
    UPLOADING,  // 上传中
    UPLOADED,   // 已上传
    FAILED      // 上传失败
}
