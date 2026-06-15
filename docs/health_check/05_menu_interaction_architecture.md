# 05 - Ring Menu Interaction Architecture Analysis

## Overview

The ring menu system is a gesture-driven radial UI for eyes-free/low-vision interaction.
The user long-presses anywhere on screen to open a circular menu, slides a finger to a
sector, and lifts to execute. The architecture follows a clean command pattern with
Hilt-injected multibinding.

---

## File-by-File Analysis

### 1. GestureVoiceLauncher.kt
**Path:** `app/src/main/java/com/example/voicenavigation/ui/voice/GestureVoiceLauncher.kt`

**What it does:** Global singleton `object` that detects a long-press (500 ms) on any
touch event forwarded from `Activity.dispatchTouchEvent()`. After the long-press fires,
it vibrates the device and notifies its callback. On `ACTION_UP` it signals confirm; on
`ACTION_CANCEL` it signals cancel.

**Depends on:**
- `android.os.Vibrator` (system service)
- `VoiceInteractionManager` (stored reference, unused in touch logic directly)
- `GestureCallback` interface (4 methods: `onVoiceAssistant`, `onRingMenuShow`,
  `onRingMenuConfirm`, `onCancel`)

**What calls it:**
- `MainActivity.dispatchTouchEvent()` forwards every touch.
- `MainActivity.onCreate()` calls `attach()`.
- `MainActivity.onDestroy()` calls `detach()`.

**Hardcoded values:**
| Value | Line | Meaning |
|-------|------|---------|
| `500L` | 30 | Long-press duration in ms |
| `50 * 50` | 86 | Move-cancel threshold: 50 px (squared) |
| `100` ms | 137 | Vibration duration |

**Bugs / Issues:**
1. **(BUG) No "tap-to-launch-voice" path.** The docstring says "long-press then lift
   without moving starts voice assistant." But `ACTION_UP` (line 94) fires
   `onRingMenuConfirm()` -- NOT `onVoiceAssistant()`. The `onVoiceAssistant()` method is
   never called from the gesture detector. The only way voice fires is if someone calls it
   externally. This is a **dead code path** for the documented interaction.
2. **Race with child view touches.** `dispatchTouchEvent` calls
   `GestureVoiceLauncher.onDispatchTouchEvent()` then returns `super.dispatchTouchEvent()`.
   The long-press timer fires even if the user is tapping a button, which means tapping
   any UI button for >= 500 ms will trigger the ring menu. This is by design (global
   gesture) but could be surprising.
3. **`isLongPressing` not cleared on ACTION_UP when long-press was NOT triggered.**
   Line 101 calls `cancelLongPress()` and returns false, but `isLongPressing` is already
   false so this is benign. However, if `isLongPressing` were somehow set between
   `ACTION_MOVE` and `ACTION_UP` (extremely unlikely on main thread), the flag would leak.

---

### 2. RingMenuView.kt
**Path:** `app/src/main/java/com/example/voicenavigation/ui/ringmenu/RingMenuView.kt`

**What it does:** Custom `View` that draws a radial (pie) menu with primary and secondary
rings. Handles `ACTION_DOWN`/`ACTION_MOVE` for hover selection and `ACTION_UP` for
execution. Exposes animatable properties (`menuScale`, `overlayAlpha`, `selectionExpansion`,
`subMenuScale`, `centerButtonScale`, `glowAlpha`) for external animation layers.

**Depends on:**
- `RingMenuItem` data model
- Android `Canvas` / `Paint` drawing primitives
- String resources `R.string.menu_back` and `R.string.menu_close`
- External animation layer drives animatable properties

**What calls it:**
- `MainActivity.setupRingMenu()` creates it, adds it to `ringMenuContainer`, sets items
  from `MenuConfig`.
- `MainActivity.showRingMenu()` / `hideRingMenu()` toggle visibility with animations.
- Touch events are delivered by the Android framework (view is a child of `ringMenuContainer`
  which is added to `android.R.id.content`).

**Hardcoded values:**
| Value | Line | Meaning |
|-------|------|---------|
| `80f` | 37 | Default inner radius (overridden in `onSizeChanged`) |
| `140f` | 38 | Default ring width (overridden) |
| `120f` | 39 | Default sub-ring width (overridden) |
| `4f` | 40 | Gap angle between sectors in degrees |
| `26f` | 41 | Default text size (overridden) |
| `22f` | 42 | Default center text size (overridden) |
| `0.08f` | 289 | Inner radius = 8% of min screen dimension |
| `0.16f` | 290 | Ring width = 16% of min screen dimension |
| `0.13f` | 291 | Sub-ring width = 13% of min screen dimension |
| `0.028f` | 292 | Text size = 2.8% of min screen dimension |
| `0.024f` | 293 | Center text size = 2.4% of min screen dimension |
| `10f` | 199, 220, 339, 319 | Gap between inner circle and ring (px, NOT scaled) |
| `1.3f` | 393-395 | Brighten multiplier for selected items |
| `3f` | 117 | Sector stroke width |

**Bugs / Issues:**
1. **(BUG) `brighten()` ignores `glowAlpha` for the color components.** Line 397 computes
   `a = (0xFF + glowAlpha).coerceAtMost(0xFF)`. Since `glowAlpha >= 0`, this always
   evaluates to `0xFF` (255). The `glowAlpha` parameter is effectively dead -- it can
   never make the selected item glow brighter because alpha is already maxed. The intended
   behavior was probably to blend or add glow only when `glowAlpha > 0`, but the current
   code makes every selected item fully opaque regardless.
2. **(BUG) Sub-menu sector angles are absolute, not offset to the parent sector.**
   Line 334: `startAngle = parentStartAngle + cIndex * childAnglePerItem + gapAngle / 2`.
   The child sectors spread across the FULL 360 degrees centered on the parent sector's
   midpoint. For a parent at 36 degrees with 3 children, each child spans 120 degrees
   starting from the parent's start angle. This means children will overlap other parent
   sectors unless there are exactly 1-2 children. With 3 children (as in "more" submenu:
   history, settings, data_collection), children will span 120 degrees each starting from
   the parent start angle, which is visually messy and may overlap sibling parent sectors.
3. **The `10f` gap between inner circle and main ring is in raw pixels**, not scaled with
   screen size. On high-DPI devices this will be a tiny gap; on low-DPI devices it will
   be relatively large. Should be proportional like the other dimensions.
4. **`centerX`/`centerY` are set to view center**, not to the touch point where the menu
   was invoked. The menu always renders from the center of the screen, but the animation
   pivot comes from the touch coordinates. This means the visual expansion and the drawn
   content are misaligned when the user long-presses off-center.

---

### 3. RingMenuItem.kt
**Path:** `app/src/main/java/com/example/voicenavigation/ui/ringmenu/RingMenuItem.kt`

**What it does:** Immutable data class representing one menu entry. Supports recursive
children for sub-menus. The `command` field maps to a key in `CommandRouter`.

**Depends on:** Nothing (pure data).

**What calls it:** `MenuConfig`, `RingMenuView`, `MainActivity`.

**Hardcoded values:**
| Value | Line | Meaning |
|-------|------|---------|
| `0xFF6200EE` | 16 | Default color (Material purple) -- only used if no color specified |

**Bugs / Issues:** None. Clean data class.

---

### 4. MenuConfig.kt
**Path:** `app/src/main/java/com/example/voicenavigation/menu/MenuConfig.kt`

**What it does:** Hilt `@Singleton` that reads `assets/menu_config.json` on first access,
parses it into `List<RingMenuItem>`, and caches the result.

**Depends on:**
- `Context` (for `assets.open()`)
- `RingMenuItem` data class

**What calls it:**
- `MainActivity.setupRingMenu()` calls `menuConfig.getItems()` to populate the view.
- Hilt injects it into `MainActivity`.

**Hardcoded values:**
| Value | Line | Meaning |
|-------|------|---------|
| `"menu_config.json"` | 25 | Config file name |
| `"#9E9E9E"` | 50 | Default color fallback |
| `"items"` | 42 | Root JSON key |

**Bugs / Issues:**
1. **No recursive depth limit.** JSON with circular `children` references or very deep
   nesting would cause a stack overflow. Low risk since JSON is hand-authored.
2. **`iconResId` is never populated from JSON.** The field exists on `RingMenuItem` but the
   parser never sets it. Icons are unused in rendering too, so this is dormant dead code.

---

### 5. CommandRouter.kt
**Path:** `app/src/main/java/com/example/voicenavigation/command/CommandRouter.kt`

**What it does:** Hilt `@Singleton` that holds a `Map<String, MenuCommand>` (populated by
Hilt multibinding in `CommandModule`). When `execute(commandId)` is called, it looks up the
command, runs it, and emits the resulting `CommandEvent` to a `SharedFlow`.

**Depends on:**
- `MenuCommand` interface
- `CommandEvent` sealed class
- Hilt multibinding map

**What calls it:**
- `MainActivity.setupRingMenu()` sets up `onItemExecuted` to call `commandRouter.execute(item.command)`.
- Could also be called from voice or gesture paths.

**Hardcoded values:** None significant.

**Bugs / Issues:**
1. **`extraBufferCapacity = 10` on `MutableSharedFlow`.** If events are emitted faster than
   collected (e.g., rapid menu taps), events will be dropped silently because there is no
   `BufferOverflow.DROP_OLDEST` specified -- the default is `DROP_OLDEST` which is fine,
   but the buffer size of 10 is arbitrary and undocumented.
2. **Null return from `command.execute()` is silently swallowed.** For example,
   `StopNavigationCommand` returns null when not navigating. This is intentional but the
   caller has no feedback that the command was a no-op.

---

### 6. MenuCommand.kt
**Path:** `app/src/main/java/com/example/voicenavigation/command/MenuCommand.kt`

**What it does:** Interface for the command pattern. Each implementation has an `id`
(matching the JSON `command` field) and an `execute()` method returning an optional
`CommandEvent`.

**Depends on:** `CommandEvent`.

**Bugs / Issues:** None.

---

### 7. CommandEvent.kt
**Path:** `app/src/main/java/com/example/voicenavigation/command/CommandEvent.kt`

**What it does:** Sealed class defining all possible events that can result from command
execution. Used as the event type for `CommandRouter.events` SharedFlow.

**Depends on:** `com.amap.api.maps.model.LatLng` (imported but unused in the sealed class
body -- dead import).

**Bugs / Issues:**
1. **Dead import:** `LatLng` is imported but never referenced in any event subclass.

---

### 8. CommandModule.kt
**Path:** `app/src/main/java/com/example/voicenavigation/di/CommandModule.kt`

**What it does:** Hilt `@Module` that uses `@Binds @IntoMap @StringKey` to register all
13 command implementations into the multibinding map consumed by `CommandRouter`.

**Depends on:** All 13 command classes in `command/commands/`.

**Bugs / Issues:**
1. **Not all commands in CommandModule are reachable from the ring menu.** The JSON config
   only references: `voice_assistant`, `start_obstacle_avoidance`, `preview_route`,
   `stop_navigation`, `show_history`, `show_settings`, `data_collection`. Commands like
   `navigate_to`, `stop_obstacle_avoidance`, `where_am_i`, `repeat_last`, `query_status`,
   `text_search` are registered but have no menu items. They are reachable only from voice
   commands.

---

### 9. MainActivity.kt (ring-menu relevant methods)

**Path:** `app/src/main/java/com/example/voicenavigation/MainActivity.kt`

**Relevant methods:**

| Method | Line | Purpose |
|--------|------|---------|
| `dispatchTouchEvent()` | 1297 | Forwards touch to `GestureVoiceLauncher`, then calls super |
| `setupRingMenu()` | 1058 | Creates `RingMenuView`, wires callbacks, starts event collector |
| `showRingMenu()` | 1097 | Animates ring menu container in from touch point |
| `hideRingMenu()` | 1102 | Animates ring menu container out |
| `handleCommandEvent()` | 1110 | Central dispatch: maps `CommandEvent` subtypes to UI actions |
| `onRingMenuShow()` | 1194 | Callback from `GestureVoiceLauncher` -> calls `showRingMenu()` |
| `onRingMenuConfirm()` | 1198 | Callback from `GestureVoiceLauncher` -> **does nothing** |
| `onVoiceAssistant()` | 1189 | Callback -> starts voice listening |
| `onCancel()` | 1203 | Callback -> hides ring menu |

**Bugs / Issues:**
1. **(BUG) `onRingMenuConfirm()` is empty (line 1198-1201).** The comment says "The view's
   `onItemExecuted` callback will fire" -- but the callback fires from `RingMenuView.handleUp()`
   which is triggered by `RingMenuView.onTouchEvent(ACTION_UP)`. The problem is that
   `GestureVoiceLauncher` consumes the `ACTION_UP` event in `dispatchTouchEvent` (returns
   true at line 98) BEFORE it reaches `RingMenuView.onTouchEvent`. Wait -- actually it
   returns `true` from `onDispatchTouchEvent` but `dispatchTouchEvent` ignores the return
   value and always calls `super.dispatchTouchEvent()`. So the event does reach the view.
   **However**, the `ringMenuContainer` starts as `View.GONE`. It only becomes `VISIBLE`
   after `showRingMenu()` animates it in. The animation takes 350ms. If the user lifts
   their finger before the container is visible, the touch event will not be dispatched to
   `RingMenuView` because the view is not yet visible. This creates a timing issue for
   fast lift-offs.
2. **(BUG) `ringMenuContainer` never resets to `GONE` after `scaleOut` animation completes.**
   Actually, looking at `ViewTransition.scaleOut()` line 221, it does set
   `view.visibility = View.GONE` in the `onEnd` callback. So this is handled correctly.
3. **(Design issue) The ring menu renders centered on screen** (`onSizeChanged` sets
   `centerX = w/2f`), but the animation pivot is the touch point. The menu will visually
   expand from where the user touched, but the actual sectors are drawn relative to screen
   center. This is a visual misalignment.

---

### 10. menu_config.json
**Path:** `app/src/main/assets/menu_config.json`

**Structure:**
```
5 top-level items:
  1. "voice"        -> voice_assistant
  2. "obstacle"     -> start_obstacle_avoidance
  3. "preview"      -> preview_route
  4. "stop_nav"     -> stop_navigation
  5. "more"         -> has 3 children:
       - "history"  -> show_history
       - "settings" -> show_settings
       - "collect"  -> data_collection
```

**Hardcoded values:** Colors are hex strings (#4CAF50, #F44336, #2196F3, #FF9800,
#9E9E9E, #795548, #607D8B, #009688). Labels are Chinese strings.

**Bugs / Issues:** None in the JSON itself.

---

### 11. Command Implementations (commands/ directory)

13 files, all following the same pattern: `@Inject constructor()`, implement `MenuCommand`,
return a `CommandEvent` subtype.

| Class | id | Event | Notes |
|-------|----|-------|-------|
| `NavigateToCommand` | `navigate_to` | `NavigateTo(dest)` | Returns null if no `destination` param |
| `StopNavigationCommand` | `stop_navigation` | `StopNavigation` | Injects `NavigationManager`; calls `stopNavigation()` AND emits event (double action) |
| `StartObstacleCommand` | `start_obstacle_avoidance` | `OpenObstacleAvoidance` | Always returns event |
| `StopObstacleCommand` | `stop_obstacle_avoidance` | `StopObstacleAvoidance` | Always returns event |
| `VoiceAssistantCommand` | `voice_assistant` | `StartVoiceAssistant` | Always returns event |
| `PreviewRouteCommand` | `preview_route` | `PreviewRoute` | Always returns event |
| `WhereAmICommand` | `where_am_i` | `AnnounceLocation` | Always returns event |
| `RepeatLastCommand` | `repeat_last` | `RepeatLast` | Always returns event |
| `QueryStatusCommand` | `query_status` | `AnnounceStatus` | Always returns event |
| `TextSearchCommand` | `text_search` | `SearchDestination` | Returns null if no keyword param |
| `ShowHistoryCommand` | `show_history` | `ShowHistory` | Always returns event |
| `ShowSettingsCommand` | `show_settings` | `ShowSettings` | Always returns event |
| `DataCollectionCommand` | `data_collection` | `OpenDataCollection` | Always returns event |

**Bugs / Issues:**
1. **(BUG) `StopNavigationCommand` performs double action.** It calls
   `navigationManager.stopNavigation()` (line 13) AND returns `CommandEvent.StopNavigation`.
   Then `handleCommandEvent()` in `MainActivity` calls `navigationManager.stopNavigation()`
   again. Navigation is stopped twice. The first stop triggers the `onNavigationStopped`
   callback which resets UI; the second call via `handleCommandEvent` does it again. This
   causes redundant `clearRouteDisplay()` and `btnStartNavigation.setText()` calls.
2. **`NavigateToCommand` and `TextSearchCommand` return null when parameters are missing.**
   When triggered from the ring menu (which passes no params), `navigate_to` returns null
   (no event emitted). The ring menu does not use `navigate_to` directly (it uses
   `voice_assistant`, `start_obstacle_avoidance`, etc.), so this is safe in practice.

---

## Event Flow Architecture (General)

```
Touch Event
  |
  v
Activity.dispatchTouchEvent()
  |
  +---> GestureVoiceLauncher.onDispatchTouchEvent()
  |       |
  |       +-- [500ms timer fires] --> callback.onRingMenuShow(x, y)
  |       |                              |
  |       |                              v
  |       |                          MainActivity.showRingMenu()
  |       |                              |
  |       |                              v
  |       |                          ViewTransition.scaleInFrom(ringMenuContainer)
  |       |                          ringMenuView.invalidate()
  |       |
  |       +-- [ACTION_UP after long-press] --> callback.onRingMenuConfirm()
  |                                               (empty -- no-op)
  |
  +---> super.dispatchTouchEvent() --> Android framework
          |
          v
      RingMenuView.onTouchEvent()
          |
          +-- [MOVE] --> handleMove() --> highlight sector
          |
          +-- [UP]   --> handleUp() --> onItemExecuted(item)
                                        |
                                        v
                                    hideRingMenu()
                                    commandRouter.execute(item.command)
                                        |
                                        v
                                    Command.execute(params) --> CommandEvent
                                        |
                                        v
                                    CommandRouter.events.tryEmit(event)
                                        |
                                        v
                                    MainActivity (lifecycleScope collector)
                                        |
                                        v
                                    handleCommandEvent(event)
                                        |
                                        v
                                    [UI action: start activity, TTS, etc.]
```

---

## End-to-End Scenario Traces

### Scenario A: Long-press -> slide to "避障" -> lift -> obstacle avoidance starts

| Step | File | Method/Line | What Happens |
|------|------|-------------|--------------|
| 1 | `MainActivity.kt` | `dispatchTouchEvent()` L1297 | `ACTION_DOWN` received |
| 2 | `GestureVoiceLauncher.kt` | `onDispatchTouchEvent()` L75-80 | Saves startX/Y, schedules long-press (500ms) |
| 3 | `GestureVoiceLauncher.kt` | `onLongPressTriggered()` L132 | Timer fires, vibrates 100ms |
| 4 | `GestureVoiceLauncher.kt` | callback L141 | Calls `onRingMenuShow(startX, startY)` |
| 5 | `MainActivity.kt` | `onRingMenuShow()` L1194 | Calls `showRingMenu(centerX, centerY)` |
| 6 | `MainActivity.kt` | `showRingMenu()` L1097 | `ViewTransition.scaleInFrom(ringMenuContainer, ...)` |
| 7 | `ViewTransition.kt` | `scaleInFrom()` L193 | Animates container scale 0->1 over 350ms |
| 8 | `RingMenuView.kt` | `onDraw()` L296 | Draws 5 sectors from `menu_config.json` data |
| 9 | (User slides finger) | | |
| 10 | `MainActivity.kt` | `dispatchTouchEvent()` L1297 | `ACTION_MOVE` forwarded |
| 11 | `GestureVoiceLauncher.kt` | `onDispatchTouchEvent()` L83-89 | `isLongPressing=true`, move > 50px check skipped |
| 12 | `RingMenuView.kt` | `onTouchEvent()` L166 | `ACTION_MOVE` -> `handleMove(x, y)` |
| 13 | `RingMenuView.kt` | `handleMove()` L182 | Calculates angle, finds "避障" sector (index 1) |
| 14 | `RingMenuView.kt` | `handleMove()` L224 | `selectedIndex = 1`, invokes `onItemSelected` |
| 15 | `RingMenuView.kt` | `onDraw()` L316-318 | Draws sector 1 with `selectionExpansion` highlight |
| 16 | (User lifts finger) | | |
| 17 | `MainActivity.kt` | `dispatchTouchEvent()` L1297 | `ACTION_UP` forwarded |
| 18 | `GestureVoiceLauncher.kt` | `onDispatchTouchEvent()` L94-98 | `isLongPressing=true`, calls `onRingMenuConfirm()`, returns true |
| 19 | `MainActivity.kt` | `onRingMenuConfirm()` L1198 | **Does nothing** (empty body) |
| 20 | `RingMenuView.kt` | `onTouchEvent()` L170 | `ACTION_UP` -> `handleUp()` |
| 21 | `RingMenuView.kt` | `handleUp()` L250 | `selectedIndex=1`, item has no children |
| 22 | `RingMenuView.kt` | `handleUp()` L257 | Calls `onItemExecuted(items[1])` |
| 23 | `MainActivity.kt` | `onItemExecuted` lambda L1086 | `hideRingMenu()` then `commandRouter.execute("start_obstacle_avoidance")` |
| 24 | `MainActivity.kt` | `hideRingMenu()` L1102 | `ViewTransition.scaleOut(ringMenuContainer, 200)` |
| 25 | `CommandRouter.kt` | `execute()` L29 | Looks up `StartObstacleCommand` |
| 26 | `StartObstacleCommand.kt` | `execute()` L9 | Returns `CommandEvent.OpenObstacleAvoidance` |
| 27 | `CommandRouter.kt` | `execute()` L39 | Emits event to `SharedFlow` |
| 28 | `MainActivity.kt` | `handleCommandEvent()` L1127 | `is CommandEvent.OpenObstacleAvoidance` |
| 29 | `MainActivity.kt` | `handleCommandEvent()` L1128 | `startActivity(Intent(this, VisionTestActivity::class.java))` |

---

### Scenario B: Long-press -> slide to "更多" -> lift -> submenu appears -> slide to "设置" -> lift -> settings opens

| Step | File | Method/Line | What Happens |
|------|------|-------------|--------------|
| 1-6 | (Same as Scenario A steps 1-6) | | Menu appears |
| 7 | `RingMenuView.kt` | `onDraw()` L296 | Draws 5 sectors |
| 8 | (User slides to "更多" sector, index 4) | | |
| 9 | `RingMenuView.kt` | `handleMove()` L224 | `selectedIndex = 4` |
| 10 | `RingMenuView.kt` | `handleMove()` L229 | `items[4].hasChildren == true` -> `activeParentIndex = 4` |
| 11 | `RingMenuView.kt` | `onDraw()` L326 | `activeParentIndex=4`, draws sub-menu ring with 3 children |
| 12 | (User lifts finger while on "更多") | | |
| 13 | `RingMenuView.kt` | `handleUp()` L250 | `selectedIndex=4`, `item.hasChildren=true` |
| 14 | `RingMenuView.kt` | `handleUp()` L253 | Sets `activeParentIndex = selectedIndex` (keeps submenu open) |
| 15 | `RingMenuView.kt` | `handleUp()` L255 | `invalidate()` -- submenu stays visible |
| 16 | **NOTE:** `handleUp()` returns here -- no `onItemExecuted` call, no command executed | | |
| 17 | (User slides to "设置" in sub-ring) | | |
| 18 | `RingMenuView.kt` | `handleMove()` L198 | `activeParentIndex >= 0` and has children |
| 19 | `RingMenuView.kt` | `handleMove()` L201 | Checks if distance is in sub-ring range |
| 20 | `RingMenuView.kt` | `handleMove()` L204-207 | Calculates child index, `selectedChildIndex` = 1 ("设置") |
| 21 | (User lifts finger) | | |
| 22 | `RingMenuView.kt` | `handleUp()` L241 | `activeParentIndex >= 0`, `selectedChildIndex >= 0` |
| 23 | `RingMenuView.kt` | `handleUp()` L243 | Calls `onItemExecuted(items[4].children[1])` ("设置") |
| 24 | `MainActivity.kt` | `onItemExecuted` lambda L1086 | `hideRingMenu()` then `commandRouter.execute("show_settings")` |
| 25 | `ShowSettingsCommand.kt` | `execute()` L9 | Returns `CommandEvent.ShowSettings` |
| 26 | `CommandRouter.kt` | `execute()` L39 | Emits event |
| 27 | `MainActivity.kt` | `handleCommandEvent()` L1169 | `is CommandEvent.ShowSettings` |
| 28 | `MainActivity.kt` | `handleCommandEvent()` L1170 | `switchTab(2)` |

**Important design note:** The sub-menu interaction requires TWO lifts. First lift on "更多"
expands the submenu (no command). Second lift on "设置" executes. The finger must remain
down between the two gestures -- there is no way to re-trigger the sub-menu after lifting.

---

### Scenario C: Long-press -> lift immediately (no slide) -> voice assistant starts

| Step | File | Method/Line | What Happens |
|------|------|-------------|--------------|
| 1 | `MainActivity.kt` | `dispatchTouchEvent()` L1297 | `ACTION_DOWN` received |
| 2 | `GestureVoiceLauncher.kt` | `onDispatchTouchEvent()` L75 | Schedules long-press |
| 3 | (500ms passes, finger still down) | | |
| 4 | `GestureVoiceLauncher.kt` | `onLongPressTriggered()` L132 | Vibrates, calls `onRingMenuShow()` |
| 5 | `MainActivity.kt` | `showRingMenu()` L1097 | Starts 350ms scale-in animation |
| 6 | (User lifts immediately, < 350ms after show started) | | |
| 7 | `MainActivity.kt` | `dispatchTouchEvent()` L1297 | `ACTION_UP` |
| 8 | `GestureVoiceLauncher.kt` | `onDispatchTouchEvent()` L94 | `isLongPressing=true` |
| 9 | `GestureVoiceLauncher.kt` | `onDispatchTouchEvent()` L96 | Calls `callback.onRingMenuConfirm()` |
| 10 | `MainActivity.kt` | `onRingMenuConfirm()` L1198 | **Empty body -- does nothing** |
| 11 | `RingMenuView.kt` | `onTouchEvent()` L170 | `ACTION_UP` -> `handleUp()` |
| 12 | `RingMenuView.kt` | `handleUp()` L240-265 | `activeParentIndex=-1`, `selectedChildIndex=-1`, `selectedIndex=-1` |
| 13 | `RingMenuView.kt` | `handleUp()` L265 | Calls `onCenterClicked()` (no item selected -> falls through to center click) |
| 14 | `MainActivity.kt` | `onCenterClicked` lambda L1090 | `hideRingMenu()` |

**RESULT: The menu appears and immediately closes. Voice assistant does NOT start.**

This is a **critical bug**. The documented behavior ("long-press then lift without moving
starts voice assistant") does not work because:

1. `GestureVoiceLauncher.onVoiceAssistant()` is never called by the gesture detector.
2. The `ACTION_UP` path calls `onRingMenuConfirm()` which is empty.
3. The `RingMenuView` receives the `ACTION_UP`, finds no selection, and fires
   `onCenterClicked()` which hides the menu.

For voice assistant to start via long-press, the user would need to:
- Long-press -> slide to "语音助手" sector -> lift.

There is no "quick lift" shortcut.

---

## Summary of All Bugs and Issues

### Critical

| # | Location | Description |
|---|----------|-------------|
| 1 | `GestureVoiceLauncher.kt` L94-98 | `onVoiceAssistant()` is dead code. Long-press-then-lift does NOT start voice assistant as documented. `ACTION_UP` calls `onRingMenuConfirm()` instead. |
| 2 | `StopNavigationCommand.kt` L12-14 | Double-stop: `navigationManager.stopNavigation()` called in command AND again in `handleCommandEvent()`. |

### Moderate

| # | Location | Description |
|---|----------|-------------|
| 3 | `RingMenuView.kt` L397 | `brighten()` glow alpha is always clamped to 0xFF. The `glowAlpha` parameter has no effect. |
| 4 | `RingMenuView.kt` L334 | Sub-menu sectors use absolute angles starting from parent start, not centered on parent sector. With 3 children spanning 120 degrees each, they overlap sibling parent sectors. |
| 5 | `MainActivity.kt` L1097 | Ring menu center is always screen center (`onSizeChanged`), but animation pivot is touch point. Visual misalignment when touching off-center. |
| 6 | `RingMenuView.kt` L199, 220, 319, 339 | The `10f` gap between inner circle and ring is in raw pixels, not proportional to screen size. |
| 7 | `MainActivity.kt` L1198-1201 | `onRingMenuConfirm()` is empty. It relies on `RingMenuView.handleUp()` being called, but if the ring container animation hasn't completed (350ms), the view won't receive the touch event. |

### Minor

| # | Location | Description |
|---|----------|-------------|
| 8 | `CommandEvent.kt` L4 | Dead import: `com.amap.api.maps.model.LatLng` unused. |
| 9 | `MenuConfig.kt` | `iconResId` field on `RingMenuItem` is never populated from JSON. |
| 10 | `RingMenuView.kt` | No recursive depth limit for sub-menus. |

---

## Hardcoded Values Reference (All Files)

| File | Value | Purpose |
|------|-------|---------|
| `GestureVoiceLauncher.kt` | `500L` ms | Long-press threshold |
| `GestureVoiceLauncher.kt` | `50` px | Move-cancel distance threshold |
| `GestureVoiceLauncher.kt` | `100` ms | Vibration duration |
| `RingMenuView.kt` | `4f` degrees | Gap between sectors |
| `RingMenuView.kt` | `10f` px | Inner circle-to-ring gap |
| `RingMenuView.kt` | `1.3f` | Brighten multiplier |
| `RingMenuView.kt` | `3f` px | Sector stroke width |
| `RingMenuView.kt` | `0.08f` | Inner radius ratio |
| `RingMenuView.kt` | `0.16f` | Ring width ratio |
| `RingMenuView.kt` | `0.13f` | Sub-ring width ratio |
| `RingMenuView.kt` | `0.028f` | Text size ratio |
| `RingMenuView.kt` | `0.024f` | Center text size ratio |
| `RingMenuItem.kt` | `0xFF6200EE` | Default purple color |
| `MenuConfig.kt` | `"menu_config.json"` | Config filename |
| `MenuConfig.kt` | `"#9E9E9E"` | Default color hex |
| `MainActivity.kt` | `350` ms | Ring menu show animation duration |
| `MainActivity.kt` | `200` ms | Ring menu hide animation duration |
