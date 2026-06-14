package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class WhereAmICommand @Inject constructor() : MenuCommand {
    override val id = "where_am_i"
    override fun execute(params: Map<String, String>): CommandEvent {
        return CommandEvent.AnnounceLocation
    }
}
