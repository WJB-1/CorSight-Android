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
        if (navigationManager.isNavigating()) {
            navigationManager.stopNavigation()
            return CommandEvent.StopNavigation
        }
        return null
    }
}
