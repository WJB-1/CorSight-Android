package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class ShowSettingsCommand @Inject constructor() : MenuCommand {
    override val id = "show_settings"
    override fun execute(params: Map<String, String>): CommandEvent {
        return CommandEvent.ShowSettings
    }
}
