package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class PreviewRouteCommand @Inject constructor() : MenuCommand {
    override val id = "preview_route"
    override fun execute(params: Map<String, String>): CommandEvent {
        return CommandEvent.PreviewRoute
    }
}
