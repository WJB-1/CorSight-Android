package com.example.voicenavigation.ui.ringmenu

import androidx.annotation.DrawableRes

/**
 * 环形菜单项。支持无限级嵌套（children 即为子菜单）。
 *
 * [command] 字段对应 CommandRouter 中的 command_id。
 * 新增菜单项只需在 assets/menu_config.json 中添加，不改代码。
 */
data class RingMenuItem(
    val id: String,
    val label: String,
    @DrawableRes val iconResId: Int? = null,
    val color: Int = 0xFF6200EE.toInt(),
    val children: List<RingMenuItem>? = null,
    val command: String = ""
) {
    val hasChildren: Boolean get() = !children.isNullOrEmpty()
}
