package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class TextSearchCommand @Inject constructor() : MenuCommand {
    override val id = "text_search"
    override fun execute(params: Map<String, String>): CommandEvent? {
        val keyword = params["keyword"] ?: params["destination"] ?: return null
        return CommandEvent.SearchDestination(keyword)
    }
}
