package com.example.voicenavigation.ui.ringmenu

import androidx.annotation.DrawableRes

/**
 * 环形菜单项。支持无限级嵌套（children 即为子菜单）。
 */
data class RingMenuItem(
    val id: String,
    val label: String,
    @DrawableRes val iconResId: Int? = null,
    val color: Int = 0xFF6200EE.toInt(),
    val children: List<RingMenuItem>? = null,
    val action: MenuAction? = null
) {
    val hasChildren: Boolean get() = !children.isNullOrEmpty()
}

sealed class MenuAction {
    object Navigate : MenuAction()
    object ObstacleAvoid : MenuAction()
    object History : MenuAction()
    object Settings : MenuAction()
    object DataCollection : MenuAction()
    object PreviewRoute : MenuAction()
    object StopNavigation : MenuAction()
    object CloseMenu : MenuAction()
    data class Custom(val tag: String) : MenuAction()
}
