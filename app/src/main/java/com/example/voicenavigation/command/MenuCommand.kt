package com.example.voicenavigation.command

/**
 * Single command interface. Each command has a unique ID and can execute.
 */
interface MenuCommand {
    /** Command identifier matching menu_config.json "command" field */
    val id: String

    /**
     * Execute the command.
     * @param params Optional parameters (e.g., destination for navigate_to)
     * @return CommandEvent to emit, or null if no UI event needed
     */
    fun execute(params: Map<String, String> = emptyMap()): CommandEvent?
}
