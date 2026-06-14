package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class ShowHistoryCommand @Inject constructor() : MenuCommand {
    override val id = "show_history"
    override fun execute(params: Map<String, String>): CommandEvent {
        return CommandEvent.ShowHistory
    }
}
