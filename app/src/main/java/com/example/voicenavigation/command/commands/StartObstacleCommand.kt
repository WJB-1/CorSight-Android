package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class StartObstacleCommand @Inject constructor() : MenuCommand {
    override val id = "start_obstacle_avoidance"
    override fun execute(params: Map<String, String>): CommandEvent {
        return CommandEvent.OpenObstacleAvoidance
    }
}
