# CorSight Android v2.0 -- Crash Bug Audit

**Date:** 2026-06-16
**Scope:** All 116 Kotlin source files across 3 modules (app, inference, vision)
**Method:** Manual code review of every source file, checking for NPE, lifecycle, thread safety, resource leaks, Hilt injection, and Android-specific crash patterns.

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 5     |
| HIGH     | 9     |
| MEDIUM   | 8     |
| LOW      | 4     |
| **Total** | **26** |

---

## CRITICAL -- Will crash under normal usage paths

### C1. VisionTestActivity: UI binding access after onDestroy on background threads

**File:** `app/src/main/java/com/example/voicenavigation/VisionTestActivity.kt`
**Lines:** 425-427, 445-446, 452-455, 459-462, 475-476, 480-483, 518-521, 528-539
**Bug type:** Lifecycle / Thread safety

The `destroyed` flag is checked at the top of `processFrame()` (line 421), but the background threads (`inferenceExecutor`, OkHttp callbacks) still access `binding.*` views via `runOnUiThread` even after `destroyed = true`. The race window: `processFrame` passes the `destroyed` check, then `onDestroy` sets `destroyed = true` and calls `binding.root` is still valid, but after `super.onDestroy()` completes, the Activity window is detached. The `runOnUiThread` callbacks (lines 445, 452, 459, 475, 518, 528) will execute on a destroyed Activity.

**Code:**
```kotlin
// Line 421: guard exists but race window remains
if (destroyed) return
// ...
runOnUiThread {
    binding.overlayView.setSourceImageSize(lastFrameWidth, lastFrameHeight)  // line 426
}
```

**Reproduce:** Start obstacle detection -> rapidly press back button -> crash on `binding.overlayView` access.
**Severity:** CRITICAL -- crash in normal back-button flow.
**Fix:** In every `runOnUiThread` block inside `processLocalFrame`, `processCloudFrame`, and OkHttp callbacks, add `if (isDestroyed || isFinishing) return@runOnUiThread` guard.

---

### C2. MainActivity: `selectedDestLatLng!!` force unwrap after race with `onArrived()`

**File:** `app/src/main/java/com/example/voicenavigation/MainActivity.kt`
**Lines:** 774, 838
**Bug type:** NPE (force unwrap)

In `onPoiSearched()` (line 774), `selectedDestLatLng!!` is force-unwrapped after setting the destination. However, `onArrived()` (line 1299) sets `selectedDestLatLng = null`. If the location callback fires `onArrived()` between `setDestination()` and the `planRoute()` call (possible in a rapid arrival scenario), this crashes.

**Code:**
```kotlin
// Line 774
navigationManager.planRoute(loc, selectedDestLatLng!!, selectedDestName)
// Line 838
navigationManager.planRoute(currentLocation!!, selectedDestLatLng!!, selectedDestName)
```

**Reproduce:** Navigate to a destination very close to current location. The `onArrived` callback fires and nullifies `selectedDestLatLng` before `planRoute` executes.
**Severity:** CRITICAL -- NPE crash.
**Fix:** Replace `!!` with `?.let { }` or local variable capture: `val dest = selectedDestLatLng ?: return`.

---

### C3. NavigationManager: `destination!!` force unwrap in triggerReroute

**File:** `app/src/main/java/com/example/voicenavigation/navigation/NavigationManager.kt`
**Line:** 209
**Bug type:** NPE (force unwrap)

`triggerReroute()` calls `planRoute(... destination!! ...)`. If `stopNavigation()` is called (which sets `destination` to null via the reset logic) concurrently with a location update that triggers reroute, `destination!!` crashes.

**Code:**
```kotlin
private fun triggerReroute(currentLocation: Location) {
    isRerouting = true
    navigationCallback?.onReRouting()
    planRoute(
        LatLng(currentLocation.latitude, currentLocation.longitude),
        destination!!,  // line 209 -- CRASH if destination is null
        destinationName
    )
}
```

**Reproduce:** Start navigation -> walk off route -> quickly tap stop navigation -> location update triggers reroute simultaneously.
**Severity:** CRITICAL -- NPE crash.
**Fix:** Guard with `val dest = destination ?: return` at top of `triggerReroute`.

---

### C4. NavigationManager: `routePoints!!` and `stepInstructions!!` force unwraps in onWalkRouteSearched

**File:** `app/src/main/java/com/example/voicenavigation/navigation/NavigationManager.kt`
**Lines:** 115, 149, 151, 167, 284
**Bug type:** NPE (force unwrap)

Multiple `!!` force unwraps on `routePoints!!` (lines 115, 149, 151, 167) inside `updateNavigationProgress()` and `onWalkRouteSearched()` (line 284). While `routePoints` is checked for null at line 115, the `stopNavigation()` method (line 325) sets `routePoints = null` without synchronization. A location callback on a background thread could see `routePoints` as non-null, then it becomes null before the `!!` dereference.

**Code:**
```kotlin
// Line 115 in location listener callback (background thread)
if (isNavigating && routePoints != null && routePoints!!.isNotEmpty()) {
    updateNavigationProgress(location)
}
// Inside updateNavigationProgress, line 149:
val endSearch = Math.min(routePoints!!.size, currentPolylineIndex + 50)
```

**Reproduce:** Navigate -> arrive at destination (or press stop) -> rapid location updates still in pipeline -> crash.
**Severity:** CRITICAL -- NPE crash.
**Fix:** Capture `routePoints` in a local `val` at the start of `updateNavigationProgress` and use that throughout.

---

### C5. VisionTestActivity: `ObstacleAlertTracker()` created as non-singleton alongside Hilt singleton

**File:** `app/src/main/java/com/example/voicenavigation/VisionTestActivity.kt` line 87
**File:** `app/src/main/java/com/example/voicenavigation/di/InferenceModule.kt` line 21
**Bug type:** Thread safety / Architecture mismatch

`ObstacleAlertTracker` is created as a Hilt `@Singleton` in `InferenceModule` but is never injected into `VisionTestActivity`. Instead, `VisionTestActivity` creates its own instance on line 87: `private val obstacleAlertTracker = ObstacleAlertTracker()`. This means the Hilt singleton is wasted and any future code that injects the singleton will get a different instance with different state. This is not a crash itself, but the `ObstacleAlertTracker` instance is accessed from `inferenceExecutor` thread (via `renderDetections` -> `obstacleAlertTracker.update`) and the main thread (via `reset()` in `switchToSource`). The `targetStates` and `activeLabels` maps have no synchronization.

**Reproduce:** Rapidly switch between camera and network sources while detection is running.
**Severity:** CRITICAL -- ConcurrentModificationException on `targetStates` map.
**Fix:** Either inject the singleton via `@Inject lateinit var obstacleAlertTracker: ObstacleAlertTracker` and add `@Synchronized` to `update()` and `reset()`, or make the internal maps `ConcurrentHashMap`.

---

## HIGH -- Likely crash under specific but realistic conditions

### H1. MainActivity: `mapView` used before `onCreate` completes initialization

**File:** `app/src/main/java/com/example/voicenavigation/MainActivity.kt`
**Lines:** 208-211
**Bug type:** Lifecycle

`mapView` is declared as `lateinit var` on line 119 and assigned on line 208 inside `onCreate`. However, `onResume()` (line 1329) calls `mapView.onResume()`. If the Activity is recreated (config change) and `onResume` fires before the specific line in `onCreate` that assigns `mapView` (between `super.onCreate` and line 208), this crashes with `UninitializedPropertyAccessException`.

**Reproduce:** Rotate the device during the brief window in `onCreate` between `setContentView` and `mapView = findViewById(R.id.map)`.
**Severity:** HIGH -- extremely tight race window but theoretically possible.
**Fix:** Change to `private var mapView: MapView? = null` and use `mapView?.onResume()`.

---

### H2. BaiduTtsManager: MediaPlayer accessed from multiple threads without synchronization

**File:** `app/src/main/java/com/example/voicenavigation/stt/BaiduTtsManager.kt`
**Lines:** 209-248, 264-278, 284-298
**Bug type:** Thread safety

`mediaPlayer` is read/written from multiple threads: `synthesizeAndPlay` runs on a spawned `Thread` (line 135), `stopPlayback` and `flushQueue` can be called from the main thread. The `mediaPlayer` field has no synchronization. If `stopPlayback()` calls `mediaPlayer?.release()` on the main thread while `setOnCompletionListener` fires on the MediaPlayer's internal thread, double-release can occur.

**Reproduce:** Start TTS playback -> immediately call `stopPlayback()` from UI -> MediaPlayer completion callback fires -> `mp.release()` called twice -> crash.
**Severity:** HIGH -- realistic in fast user interactions.
**Fix:** Add `@Synchronized` or a lock around all `mediaPlayer` access, or use a `Handler` to serialize all MediaPlayer operations on the main thread.

---

### H3. TtsPlayer: MediaPlayer accessed from multiple threads without synchronization

**File:** `app/src/main/java/com/example/voicenavigation/data/tts/TtsPlayer.kt`
**Lines:** 60-66, 72-79, 90-113, 116-146
**Bug type:** Thread safety

Same pattern as H2. `mediaPlayer` is accessed from `processQueue()` (which can be called from any thread via `speak()`), `flushQueue()`, `stopPlayback()`, and the MediaPlayer's completion listener thread. No synchronization protects the `mediaPlayer` field.

**Reproduce:** Call `speak()` rapidly from main thread while MediaPlayer completion fires -> double release.
**Severity:** HIGH.
**Fix:** Same as H2 -- serialize all MediaPlayer operations.

---

### H4. VoiceInteractionManager: singleton holds Activity-context callbacks that leak after Activity destroy

**File:** `app/src/main/java/com/example/voicenavigation/voice/VoiceInteractionManager.kt`
**Lines:** 79-80, 92-94
**Bug type:** Lifecycle / Memory leak

`VoiceInteractionManager` is a Hilt `@Singleton`. It holds `textInputListener` and `commandExecutor` references set to `this` (the Activity) on lines 565-566 of `MainActivity.kt`. When `MainActivity` is destroyed and recreated (config change), the old Activity instance remains referenced by the singleton. The old Activity's `runOnUiThread`, `Toast.makeText`, and view access will crash or leak.

**Code in MainActivity.kt:**
```kotlin
// Line 565-566
voiceInteractionManager.setTextInputListener(this)
voiceInteractionManager.setCommandExecutor(this)
```
**Code in VoiceInteractionManager.kt:**
```kotlin
// Line 160-163: showToast uses context (Application context, OK)
// But onResult calls textInputListener?.onTextResult(trimmed) which accesses views
```

**Reproduce:** While LLM is processing a voice command -> rotate device -> old Activity destroyed -> LLM callback fires -> `onTextResult` called on destroyed Activity -> crash when accessing `etDestination`.
**Severity:** HIGH.
**Fix:** Set listeners to null in `MainActivity.onDestroy()`, or use `WeakReference`.

---

### H5. VisionTestActivity: BroadcastReceiver registered without exported flag (API 33+)

**File:** `app/src/main/java/com/example/voicenavigation/VisionTestActivity.kt`
**Line:** 252
**Bug type:** Android-specific

On Android 13+ (API 33), `registerReceiver()` without the `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED` flag throws a `SecurityException`. The code registers a receiver for a custom action on line 252 without specifying the flag.

**Code:**
```kotlin
registerReceiver(stopObstacleReceiver, filter)  // Missing flag for API 33+
```

**Reproduce:** Run on Android 13+ device -> open VisionTestActivity -> crash on `registerReceiver`.
**Severity:** HIGH -- crash on all Android 13+ devices.
**Fix:** Add `Context.RECEIVER_NOT_EXPORTED` flag: `registerReceiver(stopObstacleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)`.

---

### H6. MainActivity: `currentFocus!!` force unwrap in hideKeyboard

**File:** `app/src/main/java/com/example/voicenavigation/MainActivity.kt`
**Line:** 528
**Bug type:** NPE

`hideKeyboard()` checks `currentFocus != null` but then uses `currentFocus!!.windowToken`. Between the null check and the `!!`, `currentFocus` could become null (Android framework can clear focus asynchronously).

**Code:**
```kotlin
private fun hideKeyboard() {
    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
    if (imm != null && currentFocus != null) {
        imm.hideSoftInputFromWindow(currentFocus!!.windowToken, 0)  // Race condition
    }
}
```

**Reproduce:** Tap search field -> type -> press IME search -> focus cleared by framework -> `currentFocus` becomes null -> crash.
**Severity:** HIGH -- common in search bar usage.
**Fix:** Use `currentFocus?.windowToken ?: return`.

---

### H7. GestureVoiceLauncher: `vibrator?.vibrate()` without `hasVibrator()` check

**File:** `app/src/main/java/com/example/voicenavigation/ui/voice/GestureVoiceLauncher.kt`
**Line:** 144
**Bug type:** Android-specific

`vibrator?.vibrate(VibrationEffect.createOneShot(...))` is called without checking `v.hasVibrator()`. On devices without a vibrator, this throws `Exception` or does nothing depending on the vendor implementation.

**Code:**
```kotlin
vibrator?.vibrate(
    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
)
```

**Reproduce:** Run on a device without vibrator hardware -> long press -> crash.
**Severity:** HIGH -- crash on specific device types.
**Fix:** Add `vibrator?.let { if (it.hasVibrator()) it.vibrate(...) }`.

---

### H8. NavigationManager: `navigationCallback!!` force unwrap

**File:** `app/src/main/java/com/example/voicenavigation/navigation/NavigationManager.kt`
**Line:** 284
**Bug type:** NPE

In `onWalkRouteSearched`, `navigationCallback!!` is force-unwrapped. If `setNavigationCallback(null)` is called (e.g., during Activity destroy), this crashes.

**Code:**
```kotlin
if (navigationCallback != null) {
    navigationCallback!!.onRouteReady(routePoints!!, totalDistance, totalDuration, stepInstructions!!)
    if (!isRerouting) {
        navigationCallback!!.onNavigationStarted()
    }
}
```

**Reproduce:** Start navigation -> immediately press back -> AMap callback fires -> `navigationCallback` already null -> crash.
**Severity:** HIGH.
**Fix:** Use `navigationCallback?.let { callback -> ... }` pattern.

---

### H9. UnifiedTtsManager: `destroy()` destroys shared BaiduTtsManager singleton

**File:** `app/src/main/java/com/example/voicenavigation/stt/UnifiedTtsManager.kt`
**Lines:** 156-157
**File:** `app/src/main/java/com/example/voicenavigation/MainActivity.kt` line 1357-1358
**Bug type:** Lifecycle

`UnifiedTtsManager.destroy()` calls `baiduTts?.destroy()` which nulls out the `accessToken`. But `BaiduTtsManager` is also a Hilt `@Singleton` and is injected directly into `TtsPlayer` (via `AppModule.provideTtsPlayer`). After `MainActivity.onDestroy()` calls `unifiedTts.destroy()`, the `BaiduTtsManager` singleton's token is nulled. If `TtsPlayer.speak()` is called after this (e.g., from a queued callback), it silently fails or produces errors.

**Reproduce:** Open app -> trigger TTS -> press back to destroy Activity -> re-open app -> TTS token is gone -> no speech output until restart.
**Severity:** HIGH -- silent failure, not a crash, but breaks core functionality.
**Fix:** `UnifiedTtsManager.destroy()` should NOT destroy the injected `baiduTts` singleton. Only destroy the system TTS. Or make `BaiduTtsManager.destroy()` reset-safe.

---

## MEDIUM -- Edge case crashes

### M1. VisionTestActivity: `scope = CoroutineScope(Job() + Dispatchers.Main)` never tied to lifecycle

**File:** `app/src/main/java/com/example/voicenavigation/VisionTestActivity.kt`
**Line:** 66
**Bug type:** Lifecycle

The coroutine scope is manually created with `CoroutineScope(Job() + Dispatchers.Main)` instead of using `lifecycleScope`. It is cancelled in `onDestroy` (line 765), but if `onDestroy` is not called (e.g., process death), coroutines leak. More importantly, if any coroutine started in this scope tries to access `binding` after the Activity is destroyed but before `scope.cancel()`, it will crash.

**Reproduce:** Process death while coroutines are running.
**Severity:** MEDIUM.
**Fix:** Use `lifecycleScope` instead of manual `CoroutineScope`.

---

### M2. CameraSource: `onFrame` callback invokes bitmap from CameraX background thread

**File:** `app/src/main/java/com/example/voicenavigation/CameraSource.kt`
**Lines:** 78-84
**Bug type:** Thread safety

The `imageAnalysis.setAnalyzer` callback runs on the provided `executor` (background thread). It creates a bitmap via `imageProxy.toBitmap()` and passes it to `onFrame`. The bitmap is used in `VisionTestActivity.processFrame()` which accesses `binding.overlayView.setSourceImageSize()` via `runOnUiThread`. However, the bitmap itself is passed across threads. After `imageProxy.close()` on line 84, the underlying buffer may be invalidated. `imageProxy.toBitmap()` creates a copy, so this is likely safe, but if the CameraX implementation returns a recycled bitmap, it could cause a crash.

**Reproduce:** Under heavy load, CameraX may recycle the image buffer.
**Severity:** MEDIUM -- depends on CameraX internals.
**Fix:** Ensure bitmap is copied before `imageProxy.close()` (already done by `toBitmap()`, but add defensive copy).

---

### M3. BaiduSpeechManager: `asr!!` force unwrap after null check

**File:** `app/src/main/java/com/example/voicenavigation/stt/BaiduSpeechManager.kt`
**Lines:** 70, 199, 212, 225, 238
**Bug type:** NPE

`asr` is checked for null at line 176, but `asr!!` is used at lines 199, 212, 225, 238. Between the null check and the `!!` usage, `destroyRecognizer()` (called from another thread) could set `asr = null`.

**Reproduce:** Call `startListening()` and `destroyRecognizer()` concurrently from different threads.
**Severity:** MEDIUM.
**Fix:** Capture `val localAsr = asr ?: return` at the start of each method.

---

### M4. SpeechRecognitionManager: `speechRecognizer!!` force unwrap

**File:** `app/src/main/java/com/example/voicenavigation/stt/SpeechRecognitionManager.kt`
**Line:** 76
**Bug type:** NPE

Same pattern: `speechRecognizer` checked for null on line 74, then `speechRecognizer!!` on line 76.

**Reproduce:** Call `startListening()` then `destroyRecognizer()` from another thread.
**Severity:** MEDIUM.
**Fix:** Use local variable capture.

---

### M5. SpeechRecognitionService: `speechRecognizer!!` force unwrap

**File:** `app/src/main/java/com/example/voicenavigation/stt/SpeechRecognitionService.kt`
**Line:** 59
**Bug type:** NPE

Same `!!` pattern after null check.

**Severity:** MEDIUM.
**Fix:** Use local variable capture.

---

### M6. NetworkSource: Socket not closed on all error paths

**File:** `app/src/main/java/com/example/voicenavigation/NetworkSource.kt`
**Lines:** 31-43
**Bug type:** Resource leak

In `start()`, if `Socket` constructor succeeds but `PrintWriter` or `receiveLoop` throws, the socket is caught by the generic exception handler but `socket.close()` is only called in `receiveLoop`'s finally block (line 91). If `receiveLoop` is never entered (exception before line 38), the socket leaks.

**Reproduce:** Connect to a server that accepts connection but immediately closes it.
**Severity:** MEDIUM -- resource leak, eventual `Too many open files`.
**Fix:** Add `socket?.close()` in the catch block of `start()`.

---

### M7. BaseCaptureFragment: `tvDebugOverlay` accessed before `onViewCreated`

**File:** `app/src/main/java/com/example/voicenavigation/collection/ui/base/BaseCaptureFragment.kt`
**Lines:** 177, 186
**Bug type:** NPE

`startSensors()` is called in `onResume()` (line 143). If `onResume` fires before `onViewCreated` (edge case with Fragment transactions), `tvDebugOverlay` is still uninitialized `lateinit var`. The `collectLatest` block at line 177 accesses `tvDebugOverlay.visibility`.

**Reproduce:** Add Fragment, immediately call `onResume` before view creation (programmatic Fragment management edge case).
**Severity:** MEDIUM.
**Fix:** Guard `startSensors()` with `if (::tvDebugOverlay.isInitialized)` or move to `onViewCreated`.

---

### M8. MapFragment: `onCreateView` returns null

**File:** `app/src/main/java/com/example/voicenavigation/ui/main/map/MapFragment.kt`
**Line:** 22
**Bug type:** Android-specific

`onCreateView` returns `null`, which means `onViewCreated` receives a null view. If the Fragment is ever actually added to a container, this will crash when the framework tries to add the null view.

**Code:**
```kotlin
override fun onCreateView(...): View? {
    return null  // TODO: inflate a dedicated fragment layout
}
```

**Reproduce:** If MapFragment is ever added to a FragmentContainerView, it crashes immediately.
**Severity:** MEDIUM -- currently MapFragment appears unused, but it exists and has `@AndroidEntryPoint`.
**Fix:** Inflate a placeholder layout or throw `UnsupportedOperationException` with a clear message.

---

## LOW -- Code smells unlikely to crash but should be fixed

### L1. GestureVoiceLauncher: singleton holds Activity reference (memory leak)

**File:** `app/src/main/java/com/example/voicenavigation/ui/voice/GestureVoiceLauncher.kt`
**Lines:** 53-58
**Bug type:** Memory leak

`GestureVoiceLauncher` is an `object` (singleton) that holds `callback` (which references the Activity) and `vibrator` (from Activity context). If `detach()` is not called on Activity destroy, the Activity leaks.

**Severity:** LOW -- appears to be replaced by `RingMenuCoordinator` but still exists in codebase.

---

### L2. TtsPlayer: `baiduTts.speak()` called without callback for cache-miss flow

**File:** `app/src/main/java/com/example/voicenavigation/data/tts/TtsPlayer.kt`
**Lines:** 106-113
**Bug type:** Logic error

When the cache misses, `baiduTts.speak(next)` is called, and then a 100ms delayed runnable sets `isPlaying = false`. If `baiduTts` takes longer than 100ms to start playback (which is likely on first call needing token fetch), the queue processes the next item while the previous one is still being synthesized, causing audio overlap.

**Reproduce:** Clear TTS cache -> trigger multiple TTS calls rapidly -> audio overlaps.
**Severity:** LOW -- audible glitch, no crash.

---

### L3. VisionTestActivity: `ObstacleAlertTracker` not injected, Hilt singleton wasted

**File:** `app/src/main/java/com/example/voicenavigation/di/InferenceModule.kt` line 21
**Bug type:** Code smell (Hilt)

`InferenceModule` provides `ObstacleAlertTracker` as a `@Singleton`, but `VisionTestActivity` creates its own instance on line 87. The Hilt-provided singleton is never used anywhere.

**Severity:** LOW -- wasted DI binding.

---

### L4. MainActivity: redundant `::navigationManager.isInitialized` check

**File:** `app/src/main/java/com/example/voicenavigation/MainActivity.kt`
**Line:** 817
**Bug type:** Code smell

`navigationManager` is `@Inject lateinit var`. With `@AndroidEntryPoint` and Hilt, injection happens before `onCreate`. The `isInitialized` check is always true and suggests distrust in the DI framework.

**Severity:** LOW -- dead code.

---

## Recommendations

1. **Immediate (before next release):** Fix C1-C5 and H1-H9. These are real crash paths that users will encounter.

2. **Thread safety audit:** Add `@Synchronized` or use a single `Handler` for all MediaPlayer operations in `BaiduTtsManager` and `TtsPlayer`. This fixes H2, H3.

3. **Lifecycle cleanup:** In `MainActivity.onDestroy()`, null out all callbacks on singletons:
   ```kotlin
   voiceInteractionManager.setTextInputListener(null)
   voiceInteractionManager.setCommandExecutor(null)
   voiceInteractionManager.setVoiceEventListener(null)
   navigationManager.setNavigationCallback(null)
   ```

4. **Replace all `!!` with safe calls:** The codebase has 20+ force unwrap sites. Each is a potential crash. Systematically replace with `?.let {}` or local variable capture.

5. **API 33+ compliance:** Fix BroadcastReceiver registration (H5) immediately -- this is a guaranteed crash on modern devices.

6. **Replace raw `Thread {}` with coroutines:** Lines like `Thread { appDatabase.voiceRecordDao().deleteById(...) }.start()` (MainActivity:505) should use `lifecycleScope.launch(Dispatchers.IO)` for proper lifecycle management.
