# Health Check: Command Routing & Ring Menu System

**Scope**: CommandRouter, MenuCommand, CommandEvent, CommandModule, 13 command classes, MenuConfig, RingMenuItem, RingMenuView, menu_config.json, MainActivity wiring.

**Date**: 2026-06-14

---

## Summary

The command routing system has a solid architecture -- Hilt multibinding maps string keys to `MenuCommand` implementations, `CommandRouter` dispatches by ID, and `MenuConfig` loads items from JSON. However, there is a **critical dead-end** in the event pipeline: `CommandRouter.events` is never observed by any consumer in the current codebase, meaning most commands execute silently with no UI effect. One command (`StopNavigationCommand`) partially compensates by performing side-effects directly, but the rest are effectively no-ops from the user's perspective.

**Findings: 3 CRITICAL, 2 HIGH, 3 MEDIUM, 2 LOW**

---

### [CRITICAL] CommandRouter.events is never collected -- commands execute but nothing listens

- **File**: `app/src/main/java/com/example/voicenavigation/command/CommandRouter.kt:27`
- **Issue**: `CommandRouter` emits `CommandEvent` objects via a `SharedFlow<CommandEvent>` (`_events`), but a codebase-wide search for `.events.collect`, `.events.observe`, or any consumer of `commandRouter.events` returns zero results. `MainActivity` injects `commandRouter` and calls `commandRouter.execute(item.command)` at line 1092, but never subscribes to `commandRouter.events`. `MainViewModel` holds a reference to `commandRouter` but also never collects the flow. This means every command that returns a `CommandEvent` (12 of 13 commands) fires into a void -- the event is emitted and immediately discarded.
- **Fix**: In `MainActivity.setupRingMenu()` (or in `MainViewModel`), add a coroutine collector:
  ```kotlin
  lifecycleScope.launch {
      commandRouter.events.collect { event ->
          when (event) {
              is CommandEvent.NavigateTo -> executeNavigateTo(event.destination)
              is CommandEvent.StopNavigation -> executeStopNavigation()
              is CommandEvent.OpenObstacleAvoidance -> executeStartObstacleAvoidance()
              is CommandEvent.StopObstacleAvoidance -> executeStopObstacleAvoidance()
              is CommandEvent.StartVoiceAssistant -> onVoiceAssistant()
              is CommandEvent.PreviewRoute -> executePreviewRoute()
              is CommandEvent.AnnounceLocation -> executeWhereAmI()
              is CommandEvent.RepeatLast -> executeRepeatLast()
              is CommandEvent.AnnounceStatus -> executeQueryStatus()
              is CommandEvent.SearchDestination -> executeTextSearch(event.keyword)
              is CommandEvent.ShowHistory -> { /* TODO: navigate to history tab */ }
              is CommandEvent.ShowSettings -> { /* TODO: navigate to settings tab */ }
              is CommandEvent.OpenDataCollection -> { /* TODO: open data collection */ }
              is CommandEvent.UnknownCommand -> executeUnknown(event.rawText)
              is CommandEvent.QueryResult -> { /* display query result */ }
          }
      }
  }
  ```

---

### [CRITICAL] Menu items "voice_assistant", "where_am_i", "repeat_last", "query_status", "text_search" are missing from menu_config.json -- 5 of 13 commands are unreachable via ring menu

- **File**: `app/src/main/assets/menu_config.json`
- **Issue**: `menu_config.json` defines 5 top-level items and 3 children. The `@StringKey` bindings in `CommandModule` register 13 commands, but only 8 have corresponding `command` values in the JSON (`voice_assistant`, `start_obstacle_avoidance`, `preview_route`, `stop_navigation`, `show_history`, `show_settings`, `data_collection`). The commands `where_am_i`, `repeat_last`, `query_status`, and `text_search` are registered in `CommandModule` and have `MenuCommand` implementations, but no menu item in `menu_config.json` references them. These 4 commands can only be reached via voice (`VoiceInteractionManager` / `LlmFunctionCaller`), not the ring menu. Note: `voice_assistant` IS in the JSON (item "voice"), so that one is fine.
- **Fix**: Add menu items for the missing commands to `menu_config.json`, either as top-level items or as children of the "more" submenu. For example:
  ```json
  {"id": "where", "label": "我在哪", "color": "#E91E63", "command": "where_am_i"},
  {"id": "repeat", "label": "重复播报", "color": "#673AB7", "command": "repeat_last"},
  {"id": "status", "label": "状态查询", "color": "#FF5722", "command": "query_status"}
  ```
  `text_search` is inherently parameterized (requires a `keyword` param) and may not be suitable for a static menu item without an input dialog.

---

### [CRITICAL] StopNavigationCommand depends on NavigationManager via Hilt, but MainActivity creates its own NavigationManager instance -- two separate instances exist

- **File**: `app/src/main/java/com/example/voicenavigation/command/commands/StopNavigationCommand.kt:8-9`
- **File**: `app/src/main/java/com/example/voicenavigation/di/NavigationModule.kt:18-19`
- **File**: `app/src/main/java/com/example/voicenavigation/MainActivity.kt:516`
- **Issue**: `StopNavigationCommand` receives `NavigationManager` via `@Inject constructor(private val navigationManager: NavigationManager)`. Hilt provides a **singleton** `NavigationManager` via `NavigationModule.provideNavigationManager()` (a new `NavigationManager(context)` instance). However, `MainActivity.initServices()` at line 516 creates a **second, independent** `NavigationManager(this)` instance and assigns it to the local `navigationManager` field. These are two different objects with independent state. When the ring menu's "stop_navigation" command calls `navigationManager.stopNavigation()` via the Hilt-provided instance, it is stopping navigation on an object that was never started, because `MainActivity` started navigation on its own private instance. The Hilt singleton's `isNavigating()` will always return `false`, so `StopNavigationCommand.execute()` will always return `null` (the command is a no-op).
- **Fix**: Remove the manual `NavigationManager` creation in `MainActivity.initServices()` and use the Hilt-injected singleton instead. Add `@Inject lateinit var navigationManager: NavigationManager` to `MainActivity` and remove line 516. Alternatively, if MainActivity's `NavigationManager` needs lifecycle callbacks (`setNavigationCallback(this)`), call `navigationManager.setNavigationCallback(this)` after injection. The same issue applies to `MainViewModel` which also receives `NavigationManager` via Hilt -- all three must share the same instance.

---

### [HIGH] "more" parent menu item has no command -- tapping it on main ring triggers onCenterClicked instead of expanding

- **File**: `app/src/main/assets/menu_config.json:27-36`
- **File**: `app/src/main/java/com/example/voicenavigation/ui/ringmenu/RingMenuView.kt:239-262`
- **Issue**: The "more" item in `menu_config.json` has no `"command"` field, which means `RingMenuItem.command` defaults to `""`. When the user taps (lifts finger on) the "more" item in the main ring, `handleUp()` at line 250-260 correctly detects `item.hasChildren` and sets `activeParentIndex = selectedIndex` without calling `onItemExecuted`. However, the user must **hold and drag** to the "more" sector first (which triggers `handleMove` to set `selectedIndex`), then **lift** to trigger `handleUp`. If the user taps and releases quickly within the "more" sector, `selectedIndex` is set in `handleMove` (ACTION_DOWN triggers `handleMove`), so `handleUp` will see it. This flow actually works for expanding children. **However**, there is a separate subtle issue: if `commandRouter.execute("")` were ever called for this item (it is not, since `onItemExecuted` is only called for non-parent items), it would log "Unknown command: " as a warning. The real issue is that the parent item's `command` field is empty string `""` which could cause confusion in code paths that check for `command` presence without also checking `hasChildren`.
- **Fix**: No immediate crash, but document that parent items with children should have `command` omitted or set to `null`. The current default of `""` is a code smell. Consider changing `RingMenuItem.command` default from `""` to `null` and checking `command.isNullOrEmpty()` at the call site.

---

### [HIGH] RingMenuView.brighten() glowAlpha calculation is inverted -- glowAlpha makes selection dimmer instead of brighter

- **File**: `app/src/main/java/com/example/voicenavigation/ui/ringmenu/RingMenuView.kt:392-398`
- **Issue**: The `brighten()` method computes `val a = (0xFF + glowAlpha).coerceAtMost(0xFF)`. Since `0xFF` is already 255 and adding any positive `glowAlpha` value will exceed 255, `coerceAtMost(0xFF)` will always clamp it back to 255. The `glowAlpha` parameter has zero visible effect on the alpha channel. The `drawSector` call at line 367 passes `glowAlpha` (the animated property) to `brighten()` when an item is selected, but the animation-driven breathing glow will never produce a visible result because the alpha is always 255.
- **Fix**: The intent appears to be a glowing halo effect on the selected sector. Replace with logic that applies glow as a separate overlay or modifies color components based on glowAlpha:
  ```kotlin
  private fun brighten(color: Int, glowAlpha: Int = 0): Int {
      val boost = 1.0f + (glowAlpha / 255f) * 0.3f  // glowAlpha=0..255 maps to 1.0..1.3
      val r = ((color shr 16 and 0xFF) * boost).toInt().coerceAtMost(255)
      val g = ((color shr 8 and 0xFF) * boost).toInt().coerceAtMost(255)
      val b = ((color and 0xFF) * boost).toInt().coerceAtMost(255)
      return Color.argb(0xFF, r, g, b)
  }
  ```

---

### [MEDIUM] MenuConfig bypasses Hilt injection -- manual construction with `MenuConfig(this)` in MainActivity

- **File**: `app/src/main/java/com/example/voicenavigation/MainActivity.kt:1070`
- **File**: `app/src/main/java/com/example/voicenavigation/menu/MenuConfig.kt:17-18`
- **Issue**: `MenuConfig` is annotated with `@Singleton` and `@Inject constructor(private val context: Context)`, meaning Hilt can provide it. However, `MainActivity.setupRingMenu()` at line 1070 manually creates `menuConfig = MenuConfig(this)` with a direct constructor call, bypassing Hilt entirely. This means:
  1. There are potentially two `MenuConfig` instances if anything else injects the Hilt singleton.
  2. The `@Singleton` annotation is misleading -- it suggests Hilt manages the lifecycle, but the actual usage creates a non-singleton.
  3. The `context` parameter receives `this` (Activity), not `@ApplicationContext`, which could lead to context leaks if `MenuConfig` outlives the Activity (though in this case it is stored as a local `lateinit var`).
- **Fix**: Either (a) inject `MenuConfig` via Hilt: `@Inject lateinit var menuConfig: MenuConfig` in `MainActivity`, or (b) remove the `@Singleton` and `@Inject` annotations if manual construction is intended.

---

### [MEDIUM] NavigateToCommand silently returns null when "destination" param is missing

- **File**: `app/src/main/java/com/example/voicenavigation/command/commands/NavigateToCommand.kt:10`
- **Issue**: When `NavigateToCommand.execute()` is called without a `"destination"` key in `params`, it returns `null`. In `CommandRouter.execute()` at line 37-39, a `null` return means no event is emitted and no error is logged. The ring menu item for "navigate_to" does not exist in `menu_config.json` (it is only reachable via voice), but if it were added, tapping it would silently do nothing. There is no user feedback (toast, TTS, etc.) that the command failed.
- **Fix**: Return a descriptive `CommandEvent` instead of `null`, or log a warning:
  ```kotlin
  override fun execute(params: Map<String, String>): CommandEvent? {
      val dest = params["destination"]
      if (dest == null) {
          Log.w("NavigateToCommand", "Missing 'destination' param")
          return null  // Consider returning CommandEvent.QueryResult("请提供目的地")
      }
      return CommandEvent.NavigateTo(dest)
  }
  ```

---

### [MEDIUM] TextSearchCommand silently returns null when params are missing

- **File**: `app/src/main/java/com/example/voicenavigation/command/commands/TextSearchCommand.kt:10`
- **Issue**: Same pattern as `NavigateToCommand` -- `TextSearchCommand` returns `null` when neither `"keyword"` nor `"destination"` is present in params. No feedback to the user. Since `text_search` is not in `menu_config.json`, this only affects the voice pipeline path, but the silent failure pattern is fragile.
- **Fix**: Same as above -- provide user feedback or log when required parameters are missing.

---

### [LOW] Duplicate NavigationManager instances: MainActivity creates one manually, Hilt provides another, MainViewModel receives a third

- **File**: `app/src/main/java/com/example/voicenavigation/MainActivity.kt:516`
- **File**: `app/src/main/java/com/example/voicenavigation/di/NavigationModule.kt:18`
- **File**: `app/src/main/java/com/example/voicenavigation/ui/main/MainViewModel.kt:47`
- **Issue**: Three references to `NavigationManager` exist:
  1. `MainActivity.navigationManager` -- manually created at line 516 with `NavigationManager(this)` (Activity context)
  2. Hilt singleton -- created by `NavigationModule` with `@ApplicationContext` (Application context)
  3. `MainViewModel.navigationManager` -- injected by Hilt (same singleton as #2)

  Instance #1 uses Activity context and has `setNavigationCallback(this)` called. Instances #2 and #3 use Application context and have no callback. These are functionally different objects. This is a design smell indicating an incomplete migration from manual dependency management to Hilt.
- **Fix**: Consolidate to a single Hilt-provided `NavigationManager`. Remove manual creation. Inject into both `MainActivity` and `MainViewModel`. Set the callback in `MainActivity.onCreate()`.

---

### [LOW] MainViewModel declares commandRouter as a public val but never uses it

- **File**: `app/src/main/java/com/example/voicenavigation/ui/main/MainViewModel.kt:50`
- **Issue**: `MainViewModel` exposes `val commandRouter: CommandRouter` as a public property injected by Hilt, but the ViewModel itself never calls `commandRouter.execute()` or collects `commandRouter.events`. Meanwhile, `MainActivity` injects its own `CommandRouter` instance at line 172 (`@Inject lateinit var commandRouter: CommandRouter`). Since `CommandRouter` is `@Singleton`, both are the same Hilt instance, so this is not a bug -- but the ViewModel's reference is unused dead code. It suggests an incomplete migration where the ViewModel was intended to be the command router consumer.
- **Fix**: Either move command routing logic into `MainViewModel` (subscribe to events there and expose UI state), or remove the unused `commandRouter` field from `MainViewModel`.

---

## Architecture Notes

### What works correctly

- **Hilt multibinding**: All 13 command classes have `@Inject` constructors, and `CommandModule` correctly uses `@Binds @IntoMap @StringKey` for each. The Hilt wiring is structurally sound.
- **Command ID matching**: Every `@StringKey("xxx")` in `CommandModule` matches the `id` property of its bound `MenuCommand` implementation. There are no mismatches.
- **MenuConfig JSON parsing**: `MenuConfig.parseItem()` correctly reads `id`, `label`, `color`, `command`, and recursively parses `children`. `RingMenuItem` data class fields align with the JSON structure.
- **RingMenuItem.command to CommandRouter mapping**: For menu items that have a `command` field in JSON, the value correctly corresponds to a `@StringKey` in `CommandModule`. The 8 commands present in the JSON all match.
- **RingMenuView touch handling**: The move/up selection logic, child menu expansion, and callback invocation (`onItemExecuted`, `onItemSelected`, `onCenterClicked`) are correctly implemented. Parent items with children expand without triggering `onItemExecuted`.
- **Command classes with no dependencies** (11 of 13): `@Inject constructor()` with no parameters works correctly with Hilt -- they are instantiated as new unscoped instances each time the multibinding map is built (which is once, since `CommandRouter` is `@Singleton`).

### Systemic gap

The entire command routing pipeline has a broken link at the end: commands execute, events emit, but nobody consumes the events to update the UI. The `VoiceInteractionManager.CommandExecutor` interface (implemented by `MainActivity`) is the existing working path for voice commands, but the ring menu path (`CommandRouter.execute()`) has no equivalent UI consumer. This means **all ring menu selections that don't have side-effects inside the command class itself (only `StopNavigationCommand` does) are effectively no-ops from the user's perspective**.
