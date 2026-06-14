package com.example.voicenavigation.command

import com.amap.api.maps.model.LatLng

/**
 * 命令执行后产生的 UI 事件。ViewModel 订阅后更新界面。
 *
 * 与 [UiEffect] 的区别：CommandEvent 是命令执行的副作用（需要 UI 响应），
 * UiEffect 是 ViewModel 层的通用 UI 效果。
 */
sealed class CommandEvent {
    // ── 导航 ──
    data class SearchDestination(val keyword: String) : CommandEvent()
    data class NavigateTo(val destination: String) : CommandEvent()
    object StopNavigation : CommandEvent()
    object AnnounceLocation : CommandEvent()
    object RepeatLast : CommandEvent()
    object PreviewRoute : CommandEvent()
    object AnnounceStatus : CommandEvent()

    // ── 避障 ──
    object OpenObstacleAvoidance : CommandEvent()
    object StopObstacleAvoidance : CommandEvent()

    // ── 页面切换 ──
    object ShowHistory : CommandEvent()
    object ShowSettings : CommandEvent()
    object OpenDataCollection : CommandEvent()

    // ── 语音 ──
    object StartVoiceAssistant : CommandEvent()

    // ── 未知 ──
    data class UnknownCommand(val rawText: String) : CommandEvent()

    // ── 查询 ──
    data class QueryResult(val value: String) : CommandEvent()
}
