# 07 -- RingMenuCoordinator Integration Guide

## Overview

This guide details how to switch `MainActivity` from the old `GestureVoiceLauncher` + direct `RingMenuView` wiring to the new `RingMenuCoordinator` pattern. The coordinator owns touch handling, animation sequencing, and event emission, leaving the Activity as a pure event consumer.

---

## File Inventory

| New File | Purpose |
|---|---|
| `ui/ringmenu/RingMenuCoordinator.kt` | State machine + touch handler + animation driver |
| `ui/ringmenu/InteractionEvent.kt` | Sealed class of all coordinator-emitted events |

No existing files are modified by the coordinator itself. All changes below are to `MainActivity.kt` only.

---

## Step-by-Step Changes to `MainActivity.kt`

### 1. Update imports

**Remove:**
```kotlin
import com.example.voicenavigation.ui.voice.GestureVoiceLauncher
```

**Add:**
```kotlin
import com.example.voicenavigation.ui.ringmenu.RingMenuCoordinator
import com.example.voicenavigation.ui.ringmenu.InteractionEvent
```

### 2. Remove the `GestureVoiceLauncher.GestureCallback` interface from the class declaration

**Before:**
```kotlin
class MainActivity : AppCompatActivity(),
    NavigationManager.NavigationCallback,
    PoiSearch.OnPoiSearchListener,
    VoiceInteractionManager.CommandExecutor,
    VoiceInteractionManager.TextInputListener,
    GestureVoiceLauncher.GestureCallback {
```

**After:**
```kotlin
class MainActivity : AppCompatActivity(),
    NavigationManager.NavigationCallback,
    PoiSearch.OnPoiSearchListener,
    VoiceInteractionManager.CommandExecutor,
    VoiceInteractionManager.TextInputListener {
```

### 3. Add the coordinator field

**Remove:**
```kotlin
// (no direct replacement for GestureVoiceLauncher fields -- it was an object singleton)
```

**Add (near the other ring-menu fields):**
```kotlin
    // Ring menu coordinator (replaces GestureVoiceLauncher)
    private var ringMenuCoordinator: RingMenuCoordinator? = null
```

### 4. Update `onCreate`

**Remove these two lines:**
```kotlin
        setupRingMenu()
        // ...
        GestureVoiceLauncher.attach(this, voiceInteractionManager, this)
```

**Replace with:**
```kotlin
        setupRingMenu()   // keep this -- it still creates the view and container
        // Coordinator is initialized inside setupRingMenu() after the view is ready
```

### 5. Rewrite `setupRingMenu()`

Replace the entire method body. The new version:
- Still creates the `FrameLayout` container and `RingMenuView`
- Sets `selfTouchEnabled = false` on the view (coordinator drives it)
- Creates the `RingMenuCoordinator`
- Collects `InteractionEvent` via SharedFlow
- Still collects `CommandRouter.events` as before

```kotlin
    private fun setupRingMenu() {
        // CommandRouter event collection (unchanged)
        lifecycleScope.launch {
            commandRouter.events.collect { event ->
                handleCommandEvent(event)
            }
        }

        ringMenuContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        val rootLayout = findViewById<ViewGroup>(android.R.id.content)
        rootLayout.addView(ringMenuContainer)

        ringMenuView = RingMenuView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setMenuItems(menuConfig.getItems())
            selfTouchEnabled = false  // Coordinator drives touch

            onItemExecuted = { item ->
                // Fallback: if somehow the view fires this directly,
                // route through CommandRouter (same as before)
                hideRingMenu()
                commandRouter.execute(item.command)
            }
            onCenterClicked = {
                hideRingMenu()
            }
        }
        ringMenuContainer.addView(ringMenuView)

        // Create coordinator
        ringMenuCoordinator = RingMenuCoordinator(
            context = this,
            ringMenuView = ringMenuView!!,
            ringMenuContainer = ringMenuContainer
        ).apply {
            // Optional: use touch-point positioning instead of center
            // menuPositionMode = RingMenuCoordinator.MenuPositionMode.TOUCH_POINT
        }

        // Collect coordinator events
        lifecycleScope.launch {
            ringMenuCoordinator!!.events.collect { event ->
                handleInteractionEvent(event)
            }
        }
    }
```

### 6. Add `handleInteractionEvent()`

This new method replaces the old `GestureCallback` implementations:

```kotlin
    /**
     * Handle events from RingMenuCoordinator.
     * Replaces GestureVoiceLauncher.GestureCallback implementations.
     */
    private fun handleInteractionEvent(event: InteractionEvent) {
        when (event) {
            is InteractionEvent.ShowMenu -> {
                // Coordinator already made the container visible and started animation.
                // We just need to invalidate to ensure drawing.
                ringMenuView?.invalidate()
            }

            is InteractionEvent.DismissMenu -> {
                hideRingMenu()
            }

            is InteractionEvent.ItemExecuted -> {
                // Coordinator confirmed a leaf item. Route the command.
                // Note: hideRingMenu() is already done by the coordinator's dismiss animation.
                commandRouter.execute(event.commandId)
            }

            is InteractionEvent.LaunchVoiceAssistant -> {
                // Long-press + no movement = voice assistant
                voiceInteractionManager.startListening(VoiceInteractionManager.Mode.COMMAND)
                Toast.makeText(this, getString(R.string.msg_voice_assistant_ready), Toast.LENGTH_SHORT).show()
            }

            is InteractionEvent.CenterTapped -> {
                // Center button tapped with no sub-menu active -- close
                // DismissMenu will follow from the coordinator
            }

            is InteractionEvent.SubMenuBack -> {
                // Center button tapped while sub-menu was active -- back
                // The RingMenuView handles sub-menu collapse internally via confirmSelection
            }

            is InteractionEvent.Cancelled -> {
                hideRingMenu()
            }

            is InteractionEvent.ItemHighlighted -> {
                // Optional: haptic feedback on sector change
                // vibrate(10)
            }

            is InteractionEvent.HighlightCleared -> {
                // Finger moved back to center -- no action needed
            }
        }
    }
```

### 7. Update `dispatchTouchEvent()`

**Before:**
```kotlin
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        GestureVoiceLauncher.onDispatchTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
```

**After:**
```kotlin
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        ringMenuCoordinator?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
```

### 8. Update `onDestroy()`

**Remove:**
```kotlin
        GestureVoiceLauncher.detach()
```

**Add (in the same location):**
```kotlin
        ringMenuCoordinator?.destroy()
        ringMenuCoordinator = null
```

### 9. Remove old `GestureCallback` overrides

**Delete these four methods entirely:**
```kotlin
    override fun onVoiceAssistant() { ... }
    override fun onRingMenuShow(centerX: Float, centerY: Float) { ... }
    override fun onRingMenuConfirm() { ... }
    override fun onCancel() { ... }
```

All four are replaced by `handleInteractionEvent()`.

### 10. `showRingMenu()` and `hideRingMenu()` changes

`showRingMenu()` is no longer called directly (the coordinator makes the container visible and starts the animation). However, keep `hideRingMenu()` since it is also used by `onItemExecuted` and other places.

**`showRingMenu()` can be removed or made private** since the coordinator handles it. If kept, simplify:

```kotlin
    private fun showRingMenu(centerX: Float, centerY: Float) {
        // Now handled by RingMenuCoordinator.
        // Retained only for backward compatibility if called from elsewhere.
        ringMenuContainer.visibility = View.VISIBLE
        ringMenuView?.invalidate()
    }
```

`hideRingMenu()` stays as-is:
```kotlin
    private fun hideRingMenu() {
        ViewTransition.scaleOut(ringMenuContainer, 200)
    }
```

---

## Summary of Removed Code

| Removed | Replacement |
|---|---|
| `GestureVoiceLauncher.attach(...)` | `RingMenuCoordinator(...)` constructor |
| `GestureVoiceLauncher.onDispatchTouchEvent(ev)` | `ringMenuCoordinator.onTouchEvent(ev)` |
| `GestureVoiceLauncher.detach()` | `ringMenuCoordinator.destroy()` |
| `GestureVoiceLauncher.GestureCallback` interface + 4 overrides | `handleInteractionEvent()` |
| `showRingMenu(centerX, centerY)` call | Coordinator emits `ShowMenu` event |

## Summary of New Code

| New | Purpose |
|---|---|
| `ringMenuCoordinator` field | Holds the coordinator instance |
| `handleInteractionEvent(event)` | Central event dispatcher for all coordinator events |
| `RingMenuCoordinator` | Replaces GestureVoiceLauncher singleton with instance-based state machine |
| `InteractionEvent` | Type-safe sealed class for all interaction events |

---

## State Machine Reference

```
IDLE ──(finger down)──> PRESSING ──(500ms)──> RING_MENU
                                  │                    │
                          (moved > 50px)       (finger moves over sector)
                                  │                    │
                                  v                    v
                               IDLE              SELECTING
                                                    │
                                              (finger up on leaf item)
                                                    │
                                                    v
                                                EXECUTING
                                                    │
                                              (dismiss animation done)
                                                    │
                                                    v
                                                   IDLE
```

**Transitions from RING_MENU (finger has not moved):**
- Finger up with no movement -> `LaunchVoiceAssistant` event -> IDLE
- Finger up with movement but no sector selected -> `Cancelled` event -> IDLE

**Transitions from SELECTING:**
- Finger up on leaf item -> `ItemExecuted` event -> EXECUTING -> IDLE
- Finger up on parent item -> sub-menu expands (stays SELECTING)
- Finger up on center -> `CenterTapped` or `SubMenuBack` event -> IDLE (or stays SELECTING)
- ACTION_CANCEL -> `Cancelled` event -> IDLE

---

## Animation Properties Driven

The coordinator drives these `RingMenuView` properties through the existing animation system:

| Property | Animation | When |
|---|---|---|
| `menuScale` | 0 -> 1 (OvershootInterpolator) | Menu appears |
| `menuScale` | 1 -> 0 (AccelerateInterpolator) | Menu dismisses |
| `overlayAlpha` | 0 -> 0x80 (DecelerateInterpolator) | Menu appears |
| `overlayAlpha` | 0x80 -> 0 | Menu dismisses |
| `selectionExpansion` | 0 -> 12 (OvershootInterpolator) | Finger enters new sector |
| `subMenuScale` | 0 -> 1 (OvershootInterpolator) | Sub-menu opens |
| `subMenuScale` | current -> 0 (AccelerateInterpolator) | Sub-menu closes |
| `glowAlpha` | 0x00 <-> 0x44 (breathing loop) | While menu is active |
| `centerButtonScale` | 1 <-> 1.08 (breathing loop) | While menu is active |

---

## Testing Checklist

1. **Long press + no move + release** -> Voice assistant starts
2. **Long press + slide to sector + release** -> Command executes, menu dismisses
3. **Long press + slide to parent item + release** -> Sub-menu opens
4. **Sub-menu open + slide to child + release** -> Child command executes
5. **Sub-menu open + tap center** -> Sub-menu collapses (SubMenuBack)
6. **Menu visible + tap center** -> Menu dismisses
7. **Long press + move finger off screen** -> Menu dismisses (Cancel)
8. **Rapid double long press** -> No crash, second press ignored during EXECUTING
9. **Screen rotation during menu open** -> No crash, menu dismisses gracefully
10. **Menu center vs touch-point positioning** -> Menu appears at correct location
