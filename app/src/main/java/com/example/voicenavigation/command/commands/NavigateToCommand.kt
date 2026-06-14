package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class NavigateToCommand @Inject constructor() : MenuCommand {
    override val id = "navigate_to"
    override fun execute(params: Map<String, String>): CommandEvent? {
        val dest = params["destination"] ?: return null
        return CommandEvent.NavigateTo(dest)
    }
}
