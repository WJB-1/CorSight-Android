package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

class VoiceAssistantCommand @Inject constructor() : MenuCommand {
    override val id = "voice_assistant"
    override fun execute(params: Map<String, String>): CommandEvent {
        return CommandEvent.StartVoiceAssistant
    }
}
