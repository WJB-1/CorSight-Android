# Health Check: MainActivity and UI Wiring

**File:** `app/src/main/java/com/example/voicenavigation/MainActivity.kt`
**Date:** 2026-06-14

---

## 1. Hilt Injection

**Status: HEALTHY**

`MainActivity` IS annotated with `@AndroidEntryPoint` (line 86). The `@Inject lateinit var commandRouter: CommandRouter` field at line 172 is properly injected by Hilt at runtime.

However, `MainViewModel` is **not used** in MainActivity at all. MainActivity creates its own instances of `NavigationManager`, `VoiceInteractionManager`, `BaiduSpeechManager`, `BaiduTtsManager`, `TripPreviewService`, and `AppDatabase` manually in `initServices()` (lines 512-545). These duplicate the singletons that Hilt already provides via `VoiceModule`, `NavigationModule`, and `AppModule`.

**Risk:** Two separate `NavigationManager` instances and two separate `VoiceInteractionManager` instances exist at runtime -- one in Hilt's graph (used by `MainViewModel` and `CommandRouter`'s injected commands) and one created manually by MainActivity. For `NavigationManager` specifically, `StopNavigationCommand` (which is injected via Hilt) calls `navigationManager.stopNavigation()` on the Hilt singleton, while MainActivity's UI code calls `stopNavigation()` on its own manual instance. These are **two different objects**, so stopping navigation via the ring menu `StopNavigationCommand` will NOT update MainActivity's UI state (button text, route display clearing).

**Severity: HIGH** -- This is a silent state desync between ring-menu-initiated stop and UI-initiated stop.

---

## 2. Dead / Ghost UI Elements

**Status: LOW RISK (with caveats)**

The layout `activity_main.xml` preserves the IDs for old UI elements as zero-size/alpha-zero "ghost" stubs so that `findViewById()` does not crash:

| Element | Layout (XML) | Kotlin Reference |
|---|---|---|
| `btn_start_navigation` | 0dp x 0dp, alpha=0 (line 289) | `lateinit var btnStartNavigation` (line 117) |
| `btn_preview_route` | 0dp x 0dp, alpha=0 (line 290) | `lateinit var btnPreviewRoute` (line 118) |
| `btn_vision_test` | 0dp x 0dp, alpha=0 (line 291) | `lateinit var btnVisionTest` (line 119) |
| `et_destination` | 0dp x 0dp, alpha=0 (line 301) | `lateinit var etDestination` (line 122) |
| `btn_clear_search` | 0dp x 0dp, alpha=0 (line 302) | `lateinit var btnClearSearch` (line 123) |
| `bottom_nav` | 0dp x 0dp, alpha=0 (line 307) | `lateinit var bottomNav` (line 143) |
| `container_pages` | visibility=gone (line 256) | `lateinit var containerPages` (line 144) |
| `page_history` | included inside gone container (line 265) | `lateinit var pageHistoryView` (line 145) |
| `page_settings` | included inside gone container (line 272) | `lateinit var pageSettingsView` (line 146) |
| `bottom_controls` | 0dp x 0dp, alpha=0 (line 280) | `lateinit var bottomControls` (line 147) |
| `search_bar_container` | 0dp x 0dp, alpha=0 (line 295) | `lateinit var searchBarContainer` (line 148) |

None of these will cause null pointer crashes because `findViewById()` returns non-null for zero-size views. But the code **actively writes to** these invisible views:

- `btnStartNavigation.setText(R.string.stop_navigation)` at line 1155 -- sets text on a 0dp button. Harmless but wasteful.
- `etDestination.setText(cleaned)` at lines 951, 959, 1021, 1030 -- writes text to an invisible EditText. This works functionally because `searchDestination()` reads from `etDestination.text`, but the user never sees the input.
- `etDestination.setSelection(...)` at lines 952, 960 -- operates on invisible field.
- `tvVoiceHint.text = ...` at lines 336, 346 -- the hint label IS visible (inside the voice container), so this is fine.
- `btnClearSearch.visibility = ...` at line 422 -- toggles visibility of an invisible button. Harmless.
- `setupSearchBar()` attaches a `TextWatcher` to the invisible `etDestination` (line 416) and an `OnEditorActionListener` (line 436). The TextWatcher fires when voice commands set text on this EditText, which triggers `searchDestination()`. This is actually **critical plumbing** -- voice-driven search flows through this invisible EditText.

**Net assessment:** No crashes, but the code is confusing. The invisible `etDestination` acts as a hidden data bus for voice search. This should be refactored so voice commands call `searchDestination()` directly instead of writing to a hidden EditText and relying on a TextWatcher side-effect.

---

## 3. CommandRouter Wiring

**Status: HEALTHY**

`CommandRouter` is injected via `@Inject lateinit var commandRouter: CommandRouter` (line 172). Because `MainActivity` is `@AndroidEntryPoint`, Hilt constructs it from the singleton graph, which includes all 13 commands from `CommandModule`'s `@Binds @IntoMap @StringKey(...)` multibinding.

The ring menu uses it at line 1092:
```kotlin
onItemExecuted = { item ->
    hideRingMenu()
    commandRouter.execute(item.command)
}
```

This correctly routes through the full 13-command map. No manual construction or missing bindings.

**One concern:** `CommandRouter.execute()` emits `CommandEvent` on a `SharedFlow`, but **MainActivity never collects from `commandRouter.events`**. The events go nowhere. The ring menu commands are effectively fire-and-forget into a void. This means selecting a ring menu item triggers `command.execute(params)` (which may have side effects like `StopNavigationCommand` stopping navigation), but the `CommandEvent` payload is never consumed.

**Severity: MEDIUM** -- Ring menu items that rely on `CommandEvent` being consumed (e.g., `NavigateTo`, `ShowHistory`, `ShowSettings`, `OpenDataCollection`) will silently do nothing beyond the command object's internal side effects. `StopNavigationCommand` works only because it has its own side effect inside `execute()`, but even then it operates on the wrong `NavigationManager` instance (see Finding 1).

---

## 4. Voice Button Interactions

**Status: HEALTHY**

Both voice buttons work correctly with the current architecture:

**Blue button (TEXT_INPUT mode)** -- `setupVoiceButton()` at line 326:
- `ACTION_DOWN` -> `voiceInteractionManager.startListening(Mode.TEXT_INPUT)` (line 342)
- `ACTION_UP` -> `voiceInteractionManager.stopListening()` (line 355)
- Results arrive via `onTextResult()` (line 948) which writes to `etDestination` and calls `searchDestination()`.

**Orange button (COMMAND mode)** -- `setupVoiceCommandButton()` at line 366:
- `ACTION_DOWN` -> `voiceInteractionManager.startListening(Mode.COMMAND)` (line 383)
- `ACTION_UP` -> `voiceInteractionManager.stopListening()` (line 393)
- Results processed by `VoiceInteractionManager` internally, then dispatched to `CommandExecutor` methods (lines 966-1063).

Both buttons correctly reference `voiceInteractionManager` which is initialized in `initServices()`. Animations (ripple + breathing pulse) are properly started/stopped and cancelled on release. Pulse animators are cancelled in `onDestroy()`.

No issues found.

---

## 5. GestureVoiceLauncher.attach() Signature Match

**Status: HEALTHY**

`GestureVoiceLauncher.attach()` signature (GestureVoiceLauncher.kt line 53):
```kotlin
fun attach(activity: Activity, vim: VoiceInteractionManager, cb: GestureCallback)
```

MainActivity call (line 188):
```kotlin
GestureVoiceLauncher.attach(this, voiceInteractionManager, this)
```

- `this` (Activity) -> `activity: Activity` -- matches
- `voiceInteractionManager` -> `vim: VoiceInteractionManager` -- matches (initialized at line 534)
- `this` (implements `GestureVoiceLauncher.GestureCallback`) -> `cb: GestureCallback` -- matches

`GestureVoiceLauncher` is a Kotlin `object` (singleton), so `attach`/`detach` and `onDispatchTouchEvent` are static-like calls. `detach()` is called in `onDestroy()` (line 1226). The `dispatchTouchEvent` override at line 1220 properly forwards to `GestureVoiceLauncher.onDispatchTouchEvent(ev)`.

No issues found.

---

## 6. handleVoiceCommandIntent()

**Status: HEALTHY**

```kotlin
private fun handleVoiceCommandIntent(intent: Intent?) {
    if (intent?.getBooleanExtra("START_VOICE_COMMAND", false) == true) {
        intent.removeExtra("START_VOICE_COMMAND")
        voiceInteractionManager.startListening(VoiceInteractionManager.Mode.COMMAND)
        Toast.makeText(this, getString(R.string.msg_voice_assistant_ready), Toast.LENGTH_SHORT).show()
    }
}
```

- Called from `onCreate()` (line 196) -- handles initial intent.
- Called from `onNewIntent()` (line 200) -- handles when Activity receives a new intent while already alive (singleTop/launchMode).
- The extra is consumed (`removeExtra`) to prevent re-triggering on config change.
- Null-safe (`intent?.`).

One minor note: `onNewIntent()` does not call `setIntent(intent)` before calling `handleVoiceCommandIntent(intent)`. This is fine because `handleVoiceCommandIntent` receives the intent as a parameter, but if any other code later reads `getIntent()`, it would get the stale original intent. Not a practical problem here.

No issues found.

---

## 7. switchTab()

**Status: LOW RISK**

```kotlin
private fun switchTab(index: Int) {
    // TODO: History/Settings pages now managed by Fragments.
    if (index == 0) {
        containerPages?.let { ViewTransition.fadeOut(it, 200) }
        btnMyLocation?.let { ViewTransition.fadeIn(it, 200) }
    }
}
```

Called from `bottomNav.setOnItemSelectedListener` (lines 292-302). The `bottom_nav` is a 0dp/alpha=0 ghost, so in practice users never interact with it. The method only acts on `index == 0` (nav tab); indices 1 and 2 (history, settings) are no-ops -- the TODO comment confirms this is intentional transitional code.

`containerPages` is a `lateinit var` that IS initialized (line 266, the `container_pages` FrameLayout exists in XML as `visibility="gone"`). The null-safe `?.let` calls protect against the unlikely case where the view is absent.

`pageHistoryView` and `pageSettingsView` are also `lateinit var` initialized from the `<include>` stubs. They exist as zero-size children of the gone container. `layoutHistoryEmpty`, `tvHistoryCount`, `tvHistoryDestCount` are found via `pageHistoryView.findViewById()` at lines 273-275. These views exist in `page_history.xml`. No crash risk.

The history adapter listener at `setupHistoryAdapterListener()` (line 474) references `historyAdapter` which is never initialized (it is `var historyAdapter: VoiceRecordAdapter? = null` at line 153, never assigned). So `setupHistoryAdapterListener()` is dead code -- `historyAdapter?.setOnItemActionListener(...)` is a no-op.

No crash risk. `switchTab` is effectively dead code for indices 1 and 2, and a minor animation for index 0.

---

## 8. loadHistory() / loadSettings()

**Status: HEALTHY (stubs)**

Both are empty stubs with TODO comments:
```kotlin
private fun loadHistory() { /* TODO */ }
private fun loadSettings() { /* TODO */ }
```

`loadHistory()` is called from `setupHistoryAdapterListener()` at line 489 (inside the delete callback). But `setupHistoryAdapterListener()` is itself dead code because `historyAdapter` is null (see Finding 7). So `loadHistory()` is never reachable.

`loadSettings()` is never called from anywhere.

`HistoryFragment` and `SettingsFragment` both exist and use their own `HistoryViewModel` with its own `loadHistory()` method. These are independent of MainActivity's stubs.

No crash risk. Both are safely dead code.

---

## 9. onDestroy() Cleanup

**Status: MOSTLY HEALTHY (one ordering concern)**

```kotlin
override fun onDestroy() {
    GestureVoiceLauncher.detach()        // 1. Detach gesture singleton
    voicePulseAnim?.cancel()             // 2. Cancel animations
    voicePulseAnim = null
    voiceCommandPulseAnim?.cancel()
    voiceCommandPulseAnim = null
    super.onDestroy()                    // 3. Super (Activity lifecycle)
    baiduTts?.destroy()                  // 4. TTS cleanup
    baiduTts = null
    speechManager.destroyRecognizer()    // 5. STT cleanup
    navigationManager.stopNavigation()   // 6. Stop navigation
    navigationManager.destroyLocationClient()  // 7. Destroy location client
    tripPreviewService.cancelAll()       // 8. Cancel HTTP requests
    mapView.onDestroy()                  // 9. MapView lifecycle
}
```

**Issue: `super.onDestroy()` is called before resource cleanup.** Lines 4-9 run after `super.onDestroy()`. While this does not cause crashes in current Android, it is technically incorrect -- the Activity's view hierarchy is destroyed by `super.onDestroy()`, so any cleanup that touches views (e.g., TTS stopping, navigation UI teardown) runs after views are detached. In practice the code only nulls references and cancels network calls, so no visible harm.

**Issue: `mapView.onDestroy()` is last.** If any earlier cleanup throws an exception, the MapView will not be destroyed, leaking native resources. Moving it before `super.onDestroy()` would be safer.

**Missing:** The manually-created `appDatabase` (line 518) is never closed. Room databases created with `Room.databaseBuilder` do not strictly require `close()` for singleton usage, but since this is a manual instance separate from Hilt's singleton, it could theoretically leak on Activity recreation.

**Missing:** The `handler` (line 521) is never cleaned up (`handler.removeCallbacksAndMessages(null)`). Not a practical issue unless there are pending Runnables.

**Severity: LOW** -- No crashes, but cleanup ordering is suboptimal.

---

## 10. MapView Lifecycle Management

**Status: HEALTHY**

| Lifecycle Method | Called? | Location |
|---|---|---|
| `onCreate(savedInstanceState)` | Yes | Line 191 in `onCreate()` |
| `onResume()` | Yes | Line 1207 in `onResume()` |
| `onPause()` | Yes | Line 1211 in `onPause()` |
| `onSaveInstanceState(outState)` | Yes | Line 1217 in `onSaveInstanceState()` |
| `onDestroy()` | Yes | Line 1238 in `onDestroy()` |

All five required AMap lifecycle calls are present and properly forwarded. The MapView is initialized (`findViewById`) and `mapView.onCreate()` is called before `initMap()` which accesses `mapView.map`.

No issues found.

---

## Summary

| # | Check | Status | Severity |
|---|---|---|---|
| 1 | Hilt injection | Dual instance problem | **HIGH** |
| 2 | Dead code / ghost views | No crashes, hidden data bus | LOW |
| 3 | CommandRouter wiring | Injected correctly, but events not consumed | **MEDIUM** |
| 4 | Voice button interactions | Working correctly | OK |
| 5 | GestureVoiceLauncher.attach() | Signature matches | OK |
| 6 | handleVoiceCommandIntent() | Working correctly | OK |
| 7 | switchTab() | Effectively dead for tabs 1/2 | LOW |
| 8 | loadHistory()/loadSettings() | Safely empty stubs | OK |
| 9 | onDestroy() | Cleanup ordering suboptimal | LOW |
| 10 | MapView lifecycle | All 5 calls present | OK |

---

## Recommended Actions

### P0 -- Dual Instance Desync (Finding 1 + Finding 3)

MainActivity creates its own `NavigationManager`, `VoiceInteractionManager`, `BaiduSpeechManager`, `BaiduTtsManager`, `TripPreviewService`, and `AppDatabase` in `initServices()`, ignoring the Hilt singletons. Meanwhile, `CommandRouter` and `MainViewModel` use the Hilt instances. This causes:

- Ring menu `StopNavigationCommand` stops navigation on the Hilt `NavigationManager` but does not update MainActivity's UI (button text, route clearing).
- Two `VoiceInteractionManager` instances means voice callbacks set up in MainActivity (`setTextInputListener`, `setCommandExecutor`) are on the manual instance, while Hilt-injected code may use the singleton.

**Fix:** Inject `NavigationManager`, `VoiceInteractionManager`, `BaiduTtsManager`, `TripPreviewService`, and `AppDatabase` via `@Inject lateinit var` instead of creating them manually. Remove `initServices()` (or reduce it to listener setup only). This unifies the object graph.

### P1 -- CommandRouter Events Not Collected (Finding 3)

After `commandRouter.execute(item.command)` at line 1092, the emitted `CommandEvent` is never collected. Commands like `ShowHistory`, `ShowSettings`, `OpenDataCollection`, and `NavigateTo` will be silently ignored.

**Fix:** Collect `commandRouter.events` in `onCreate()` and dispatch to appropriate handlers, or migrate to `MainViewModel` which could collect the events and emit `UiEffect`s.

### P2 -- Hidden EditText Data Bus (Finding 2)

Voice search results flow through an invisible `etDestination` via `setText()`, triggering a `TextWatcher` that calls `searchDestination()`. This indirection is fragile and confusing.

**Fix:** Call `searchDestination()` directly from voice callbacks. Remove the TextWatcher dependency for voice-driven searches.
