package com.example.voicenavigation.command

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.voicenavigation.config.AppConstants
import com.example.voicenavigation.navigation.NavigationManager
import com.example.voicenavigation.network.TripPreviewService
import com.example.voicenavigation.voice.VoiceInteractionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 命令执行器。所有功能的"做"都在这里。
 *
 * 从 MainActivity 抽出，解耦 UI 和业务逻辑。
 * 通过 [events] 输出 [CommandEvent]，由 ViewModel 订阅后更新 UI。
 */
@Singleton
class AppCommandHandler @Inject constructor(
    private val navigationManager: NavigationManager,
    private val voiceInteractionManager: VoiceInteractionManager,
    private val tripPreviewService: TripPreviewService
) {

    companion object {
        private const val TAG = "AppCommandHandler"
    }

    private val _events = MutableSharedFlow<CommandEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<CommandEvent> = _events.asSharedFlow()

    // ── 状态查询（供 VoiceInteractionManager.CommandExecutor 使用）──
    var lastSpokenText: String? = null
    var isObstacleRunning: Boolean = false

    fun isNavigating(): Boolean = navigationManager.isNavigating()

    fun handle(commandId: String, params: Map<String, String> = emptyMap()) {
        Log.d(TAG, "handle: $commandId $params")
        when (commandId) {
            // ── 导航 ──
            "navigate_to" -> {
                val dest = params["destination"] ?: return
                emit(CommandEvent.NavigateTo(dest))
            }
            "stop_navigation" -> {
                if (navigationManager.isNavigating()) {
                    navigationManager.stopNavigation()
                    emit(CommandEvent.StopNavigation)
                }
            }
            "preview_route" -> {
                emit(CommandEvent.PreviewRoute)
            }
            "where_am_i" -> {
                emit(CommandEvent.AnnounceLocation)
            }
            "repeat_last" -> {
                emit(CommandEvent.RepeatLast)
            }
            "query_status" -> {
                emit(CommandEvent.AnnounceStatus)
            }
            "text_search" -> {
                val keyword = params["keyword"] ?: params["destination"] ?: return
                emit(CommandEvent.SearchDestination(keyword))
            }

            // ── 避障 ──
            "start_obstacle_avoidance" -> {
                isObstacleRunning = true
                emit(CommandEvent.OpenObstacleAvoidance)
            }
            "stop_obstacle_avoidance" -> {
                isObstacleRunning = false
                emit(CommandEvent.StopObstacleAvoidance)
            }

            // ── 页面 ──
            "voice_assistant" -> {
                emit(CommandEvent.StartVoiceAssistant)
            }
            "show_history" -> {
                emit(CommandEvent.ShowHistory)
            }
            "show_settings" -> {
                emit(CommandEvent.ShowSettings)
            }
            "data_collection" -> {
                emit(CommandEvent.OpenDataCollection)
            }

            // ── 未知 ──
            else -> {
                Log.w(TAG, "Unknown command: $commandId")
                emit(CommandEvent.UnknownCommand(commandId))
            }
        }
    }

    private fun emit(event: CommandEvent) {
        _events.tryEmit(event)
    }
}
