package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import com.example.voicenavigation.navigation.NavigationManager
import javax.inject.Inject

class StopNavigationCommand @Inject constructor(
    private val navigationManager: NavigationManager
) : MenuCommand {
    override val id = "stop_navigation"
    override fun execute(params: Map<String, String>): CommandEvent? {
        // B4 修复：只返回事件，不直接执行副作用。
        // 副作用由 handleCommandEvent 统一处理，避免双重执行。
        return CommandEvent.StopNavigation
    }
}
