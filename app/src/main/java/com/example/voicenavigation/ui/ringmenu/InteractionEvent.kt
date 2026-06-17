package com.example.voicenavigation.ui.ringmenu

import com.example.voicenavigation.menu.RingMenuItem

/**
 * Events emitted by [RingMenuCoordinator] to notify the host (Activity/Fragment)
 * of user interactions with the ring menu.
 *
 * The host collects these via [RingMenuCoordinator.events] SharedFlow and
 * performs the corresponding action (show/hide menu, route command, start voice, etc.).
 */
sealed class InteractionEvent {

    /** Long press detected -- host should make the ring menu container visible. */
    data class ShowMenu(val centerX: Float, val centerY: Float) : InteractionEvent()

    /** Menu should be hidden (dismiss animation requested). */
    data object DismissMenu : InteractionEvent()

    /**
     * User confirmed a menu item selection.
     * [commandId] is the [RingMenuItem.command] value of the selected item.
     * For items with children this event is NOT emitted on first tap
     * (first tap opens sub-menu); it IS emitted when a leaf item is confirmed.
     */
    data class ItemExecuted(val commandId: String, val item: RingMenuItem) : InteractionEvent()

    /**
     * Long press was released without the finger moving (no menu interaction).
     * Host should start the voice assistant.
     */
    data object LaunchVoiceAssistant : InteractionEvent()

    /**
     * User tapped the center button while no sub-menu was active.
     * Equivalent to cancel / close the menu.
     */
    data object CenterTapped : InteractionEvent()

    /**
     * User tapped the center button while a sub-menu was active.
     * Host may handle "go back" if needed, but the coordinator handles
     * the sub-menu collapse internally.
     */
    data object SubMenuBack : InteractionEvent()

    /**
     * Menu was cancelled (finger left the menu area, or ACTION_CANCEL).
     * Host should hide the menu.
     */
    data object Cancelled : InteractionEvent()

    /**
     * A menu item is being hovered (finger moved over a sector).
     * Useful for haptic feedback or accessibility announcements.
     */
    data class ItemHighlighted(val item: RingMenuItem) : InteractionEvent()

    /**
     * The finger moved back into the center area, clearing the highlight.
     */
    data object HighlightCleared : InteractionEvent()
}
