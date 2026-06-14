package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class DataCollectionCommand @Inject constructor() : MenuCommand {
    override val id = "data_collection"
    override fun execute(params: Map<String, String>): CommandEvent {
        return CommandEvent.OpenDataCollection
    }
}
