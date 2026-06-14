package com.example.voicenavigation.command

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified command router backed by Hilt multibinding.
 *
 * All entry points (voice commands, ring menu, gesture detection) route through
 * [execute] by command ID. New commands only require a new [MenuCommand] class
 * and a single `@Binds @IntoMap` line in [com.example.voicenavigation.di.CommandModule].
 */
@Singleton
class CommandRouter @Inject constructor(
    private val commands: Map<String, @JvmSuppressWildcards MenuCommand>
) {

    companion object {
        private const val TAG = "CommandRouter"
    }

    private val _events = MutableSharedFlow<CommandEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<CommandEvent> = _events.asSharedFlow()

    fun execute(commandId: String, params: Map<String, String> = emptyMap()) {
        Log.d(TAG, "execute: commandId=$commandId, params=$params")
        val command = commands[commandId]
        if (command == null) {
            Log.w(TAG, "Unknown command: $commandId")
            _events.tryEmit(CommandEvent.UnknownCommand(commandId))
            return
        }
        val event = command.execute(params)
        if (event != null) {
            _events.tryEmit(event)
        }
    }
}
