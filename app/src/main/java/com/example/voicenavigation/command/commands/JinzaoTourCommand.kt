package com.example.voicenavigation.command.commands

import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.MenuCommand
import javax.inject.Inject

/**
 * 金造村游览命令 —— 一键规划路线 + 行前预览。
 *
 * 用户说"逛金造村"时触发：跳过 POI 搜索，直接定位金造村坐标，
 * 规划步行路线并自动发送预览请求。
 */
class JinzaoTourCommand @Inject constructor() : MenuCommand {
    override val id = "jinzao_tour"
    override fun execute(params: Map<String, String>): CommandEvent {
        return CommandEvent.JinzaoTour(params["destination"] ?: "金造村")
    }
}
