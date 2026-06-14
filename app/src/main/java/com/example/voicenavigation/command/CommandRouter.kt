package com.example.voicenavigation.command

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一命令路由器。
 *
 * 所有功能入口（语音命令、环形菜单、手势检测）统一通过 command_id 路由到 [AppCommandHandler]。
 * 新增功能只需：
 * 1. 在 [AppCommandHandler.handle] 加一个 when 分支
 * 2. 在 assets/menu_config.json 加菜单项（如需菜单入口）
 * 3. 在 assets/voice_keywords.json 加关键词（如需语音入口）
 */
@Singleton
class CommandRouter @Inject constructor(
    private val handler: AppCommandHandler
) {

    companion object {
        private const val TAG = "CommandRouter"
    }

    /**
     * 执行命令。
     *
     * @param commandId 命令标识（如 "navigate_to", "stop_navigation"）
     * @param params 附带参数（如 destination="天安门"）
     */
    fun execute(commandId: String, params: Map<String, String> = emptyMap()) {
        Log.d(TAG, "execute: commandId=$commandId, params=$params")
        handler.handle(commandId, params)
    }
}
