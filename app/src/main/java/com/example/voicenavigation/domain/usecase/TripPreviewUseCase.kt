package com.example.voicenavigation.domain.usecase

import com.example.voicenavigation.network.TripPreviewService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 行前预览用例。封装预览网络请求。
 */
@Singleton
class TripPreviewUseCase @Inject constructor(
    private val tripPreviewService: TripPreviewService
) {

    var baseUrl: String
        get() = tripPreviewService.baseUrl
        set(value) { tripPreviewService.baseUrl = value }

    fun sendPreview(
        originLat: Double, originLng: Double,
        destLat: Double, destLng: Double,
        callback: TripPreviewService.PreviewCallback
    ) {
        tripPreviewService.sendPreviewRequest(originLat, originLng, destLat, destLng, callback)
    }

    fun sendFixedPreview(routeId: String, callback: TripPreviewService.PreviewCallback) {
        tripPreviewService.sendFixedPreviewRequest(routeId, callback)
    }

    fun cancelAll() {
        tripPreviewService.cancelAll()
    }
}
