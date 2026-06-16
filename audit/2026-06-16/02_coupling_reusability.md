# Coupling & Reusability Audit

Date: 2026-06-16
Scope: All `.kt` files in `app/`, `inference/`, `vision/` modules (96 files total)

---

## Executive Summary

The codebase has **7 major coupling clusters** and **~15 extractable utility classes**.
The highest-impact issues are:

1. **TTS/STT layer** -- 12 duplicated patterns across 7 files, including entire copy-pasted classes
2. **Camera + permission handling** -- 7 duplicated patterns between BaseCaptureFragment and RetakeFragment
3. **SharedPreferences / config** -- 3 competing access patterns, 2 competing constant holders
4. **Vibration** -- 5 separate implementations with inconsistent API-level safety
5. **Voice button touch handling** -- near-identical long-press setup repeated 3 times across 2 Activities

Estimated total lines eliminable: **~1,800 lines** across **~30 files**.

---

## Finding 1: Duplicated STTCallback Interface (3 definitions)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduSpeechManager.kt` L43-49, `stt/SpeechRecognitionManager.kt` L29-34, `stt/SpeechRecognitionService.kt` L30-35 |
| **State** | Duplicated -- 3 independent definitions of the same interface |
| **Detail** | `BaiduSpeechManager` has `onPartialResult`; the other two are byte-for-byte identical |
| **Extraction** | `stt/STTCallback.kt` (single shared interface with `onPartialResult` as default method) |
| **Scope** | Cross-module |
| **Impact** | ~20 lines saved, eliminates interface divergence risk |

---

## Finding 2: Duplicated TtsCallback Interface (2 definitions)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L56-59, `stt/UnifiedTtsManager.kt` L45-48 |
| **State** | Duplicated -- completely identical definitions |
| **Extraction** | `stt/TtsCallback.kt` |
| **Scope** | Cross-module |
| **Impact** | ~5 lines, eliminates ambiguity |

---

## Finding 3: Duplicated SpeechRecognizer Initialization (2 copies)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/SpeechRecognitionManager.kt` L40-51, `stt/SpeechRecognitionService.kt` L43-54 |
| **State** | Duplicated -- only difference is `context` vs `this` |
| **Detail** | Intent configuration (language, model, max results, partial results) is byte-for-byte identical |
| **Extraction** | `stt/SpeechRecognizerFactory.kt` -- shared factory accepting `Context` parameter |
| **Scope** | Cross-module |
| **Impact** | ~25 lines saved per file, single source of truth for recognizer config |

---

## Finding 4: Duplicated RecognitionListener Implementation (entire block)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/SpeechRecognitionManager.kt` L100-154, `stt/SpeechRecognitionService.kt` L80-134 |
| **State** | Duplicated -- every callback method is identical |
| **Detail** | Includes a 15-line `onError` `when` block mapping 9 error codes to string resources |
| **Extraction** | `stt/BaseRecognitionListener.kt` -- abstract class with shared error mapping, or include in the factory from Finding 3 |
| **Scope** | Cross-module |
| **Impact** | ~70 lines saved, single error-mapping table |

---

## Finding 5: Duplicated stopListening / destroyRecognizer (2 copies)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/SpeechRecognitionManager.kt` L89-96, `stt/SpeechRecognitionService.kt` L69-76 |
| **State** | Duplicated |
| **Extraction** | Consolidate into the base class or factory from Finding 3/4 |
| **Scope** | Cross-module |
| **Impact** | ~10 lines |

---

## Finding 6: Duplicated Baidu TTS URL Constants (2 copies)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L35-36, `data/tts/TtsPreloader.kt` L31-32 |
| **State** | Duplicated -- `TOKEN_URL` and `TTS_URL` identical in both |
| **Extraction** | `stt/BaiduTtsConstants.kt` |
| **Scope** | Cross-module |
| **Impact** | ~5 lines, prevents URL drift |

---

## Finding 7: Duplicated Token Fetch Logic (2 copies)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L80-108, `data/tts/TtsPreloader.kt` L139-157 |
| **State** | Duplicated -- same HTTP POST, same timeouts (10000/10000), same JSON parsing |
| **Extraction** | `stt/BaiduTokenProvider.kt` -- singleton token manager with caching |
| **Scope** | Cross-module (library candidate) |
| **Impact** | ~40 lines saved, eliminates double-token-fetch on startup |

---

## Finding 8: Duplicated TTS HTTP Synthesis Logic (2 copies)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L155-206, `data/tts/TtsPreloader.kt` L159-182 |
| **State** | Duplicated -- same URL, same params, same timeouts (10000/30000), same Content-Type |
| **Detail** | Voice parameters `per=0&spd=5&pit=5&vol=15` are hardcoded magic values in both |
| **Extraction** | `stt/BaiduTtsApiClient.kt` -- shared HTTP client with configurable voice params |
| **Scope** | Cross-module (library candidate) |
| **Impact** | ~60 lines saved, single place to tune voice parameters |

---

## Finding 9: Duplicated TTS Audio Cache (BaiduTtsManager has its own, bypassing TtsAudioCache)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L316-332, `data/tts/TtsAudioCache.kt` L29-93 |
| **State** | Duplicated -- both use `"tts_cache"` directory, MD5 hashing, `"$hash.mp3"` naming |
| **Detail** | `BaiduTtsManager` implements its own inline cache instead of using the existing `TtsAudioCache` class. Two independent caches for the same data. |
| **Extraction** | Remove inline cache from `BaiduTtsManager`; inject `TtsAudioCache` instead |
| **Scope** | Cross-module |
| **Impact** | ~25 lines removed from BaiduTtsManager, eliminates dual-cache confusion |

---

## Finding 10: Duplicated Speech Queue Pattern (3 classes)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L44-49/114-136, `stt/UnifiedTtsManager.kt` L39-41/121-171, `data/tts/TtsPlayer.kt` L39-41/46-96 |
| **State** | Duplicated -- all three implement `LinkedList<String>` queue, `synchronized` access, `isSpeaking` flag, `@Volatile stopped` flag, identical `speak()` and `processQueue()` methods |
| **Extraction** | `stt/SpeechQueue.kt` -- reusable thread-safe FIFO queue with `speak()`/`stop()`/`flush()` |
| **Scope** | Cross-module (library candidate) |
| **Impact** | ~120 lines saved across 3 files |

---

## Finding 11: Duplicated stopPlayback / flushQueue (3 copies each)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L264-299, `stt/UnifiedTtsManager.kt` L132-145, `data/tts/TtsPlayer.kt` L60-80 |
| **State** | Duplicated -- all three: set `stopped=true`, clear queue in `synchronized`, stop/release MediaPlayer, reset state |
| **Extraction** | Consolidate into `SpeechQueue` from Finding 10 |
| **Scope** | Cross-module |
| **Impact** | ~60 lines saved |

---

## Finding 12: Duplicated MediaPlayer Setup (3 locations)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L222-243, `stt/BaiduTtsManager.kt` L340-351, `data/tts/TtsPlayer.kt` L119-139 |
| **State** | Duplicated -- all use `AudioManager.STREAM_MUSIC`, same release-on-error/completion pattern |
| **Extraction** | `util/MediaPlayerHelper.kt` -- reusable setup with callbacks |
| **Scope** | Cross-module |
| **Impact** | ~45 lines saved |

---

## Finding 13: No Shared STT Engine Interface

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduSpeechManager.kt` L170-173, `stt/SpeechRecognitionManager.kt` L53-64 |
| **State** | Both expose `isRecognitionAvailable(): Boolean` and `getRecognitionStatus(): String` but share no interface |
| **Extraction** | `stt/SpeechEngine.kt` -- interface with `isAvailable()`, `getStatus()`, `startListening()`, `stopListening()`, `destroy()` |
| **Scope** | Cross-module |
| **Impact** | Enables runtime engine switching, ~30 lines of adapter code |

---

## Finding 14: Duplicated SharedPreferences Key Constants (2 holders)

| Aspect | Detail |
|--------|--------|
| **Files** | `AppConfig.kt` L7-16, `config/AppConstants.kt` L15-22 |
| **State** | Duplicated -- 7 identical key strings with different constant names (`KEY_*` vs `SP_KEY_*`) |
| **Detail** | `SettingsViewModel` uses both classes in the same file. `VisionTestActivity` uses raw `"corsight_config"` string at L138 |
| **Extraction** | Delete `AppConfig` key constants; make `AppConstants` the single authority. Migrate all `AppConfig.KEY_*` references to `AppConstants.SP_KEY_*` |
| **Scope** | Cross-module |
| **Impact** | ~15 lines removed, eliminates divergence risk |

---

## Finding 15: Three Competing SharedPreferences Access Patterns

| Aspect | Detail |
|--------|--------|
| **Files** | `AppConfig.kt` L18-21, `config/AppConfigProvider.kt` L14-16, `VisionTestActivity.kt` L138 |
| **State** | Scattered -- (1) `AppConfig.prefs(context)` static factory, (2) `AppConfigProvider` Hilt-injected JSON-backed, (3) raw `getSharedPreferences("corsight_config", MODE_PRIVATE)` |
| **Detail** | `LlmFunctionCaller` uses `AppConfig` (pattern 1) with triple-redundant `?: ""` at L278/283/286. `VoiceInteractionManager` and `GestureVoiceLauncher` use neither -- they hardcode fallback values directly despite `AppConfigProvider` having exact config entries. |
| **Extraction** | Consolidate to single strategy: `AppConfigProvider` (Hilt singleton) for runtime-tunable values, `AppConstants` for compile-time values. Delete `AppConfig`. |
| **Scope** | Cross-module |
| **Impact** | ~40 lines of accessor code removed, single source of truth |

---

## Finding 16: Duplicated Vibration Handling (5 implementations)

| Aspect | Detail |
|--------|--------|
| **Files** | `MainActivity.kt` L421-432, `VisionTestActivity.kt` L159-168, `GridCaptureFragment.kt` L183-184, `ui/ringmenu/RingMenuCoordinator.kt` L319-329 + L565-577, `ui/voice/GestureVoiceLauncher.kt` L145 |
| **State** | Duplicated -- 5 separate implementations. Only `MainActivity` has proper `hasVibrator()` check and pre-Oreo compat. `VisionTestActivity` and `GridCaptureFragment` will crash on API < 26. `RingMenuCoordinator` has two copies in the same file (inline L319-329 and `vibrateShort()` L565). |
| **Extraction** | `util/VibrationHelper.kt` -- `vibrate(context, durationMs)` with proper API checks |
| **Scope** | Cross-module |
| **Impact** | ~50 lines saved, fixes 2 crash-prone implementations |

---

## Finding 17: Duplicated Long-Press-to-Voice Touch Handling (3 copies)

| Aspect | Detail |
|--------|--------|
| **Files** | `MainActivity.kt` L345-378 (`setupVoiceButton`), `MainActivity.kt` L385-418 (`setupVoiceCommandButton`), `VisionTestActivity.kt` L158-186 (`setupLongPressVoiceLauncher`) |
| **State** | Duplicated -- all three: ACTION_DOWN triggers permission check + vibrate + ripple animation + start listening; ACTION_UP cleans up animation + stops listening. The two in MainActivity are near-identical to each other (differ only in which View/Mode/hint text). |
| **Extraction** | `ui/voice/VoiceTouchHandler.kt` -- parameterized by `(container, ripple, hint, mode, onPermissionDenied)` |
| **Scope** | Cross-module |
| **Impact** | ~100 lines saved in MainActivity alone, eliminates 6 duplicated state variables (lines 122-129, 182-183) |

---

## Finding 18: Duplicated Permission Handling (4+ implementations)

| Aspect | Detail |
|--------|--------|
| **Files** | `MainActivity.kt` L655-708, `VisionTestActivity.kt` L743-756, `collection/ui/base/BaseCaptureFragment.kt` L107-115/L278-281, `collection/ui/dashboard/RetakeFragment.kt` L89-94/L142-146, `collection/ui/hub/CaptureHubActivity.kt` L45-70/L156-165 |
| **State** | Duplicated -- `hasCameraPermission()` is identical in BaseCaptureFragment and RetakeFragment. Permission launcher boilerplate is repeated in 3 files. MainActivity uses old-style `onRequestPermissionsResult`; CaptureHubActivity uses modern `registerForActivityResult`. |
| **Extraction** | `util/PermissionHelper.kt` -- `hasPermission(context, permission)`, `registerCameraLauncher(fragment, onGranted)`, `registerMultipleLauncher(activity, permissions, onResult)` |
| **Scope** | Cross-module |
| **Impact** | ~80 lines saved, consistent permission UX |

---

## Finding 19: Duplicated Camera Startup (BaseCaptureFragment vs RetakeFragment)

| Aspect | Detail |
|--------|--------|
| **Files** | `collection/ui/base/BaseCaptureFragment.kt` L284-304, `collection/ui/dashboard/RetakeFragment.kt` L148-167 |
| **State** | Duplicated -- `ProcessCameraProvider.getInstance` + `Preview.Builder` + `ImageCapture.Builder` + `bindToLifecycle` block is nearly identical. RetakeFragment does NOT extend BaseCaptureFragment. |
| **Detail** | Also duplicated: `setShutterEnabled()` (L355 vs L228), shutter touch setup (L131 vs L117), compass subscription (L158 vs L169), onResume/onPause camera lifecycle (L142 vs L129) |
| **Extraction** | Either make RetakeFragment extend BaseCaptureFragment, or extract `CameraHelper` class |
| **Scope** | Module-internal (collection) |
| **Impact** | ~100 lines saved in RetakeFragment, eliminates 5 near-identical methods |

---

## Finding 20: Inline IOU / Detection Algorithms in VisionTestActivity

| Aspect | Detail |
|--------|--------|
| **Files** | `VisionTestActivity.kt` L607-661, `ui/vision/VisionViewModel.kt` L290-328 |
| **State** | Inline -- IOU computation (L653-661), detection merging (L629-647), temporal stabilization (L607-627), box normalization (L710-721), box parsing (L688-708) all live inside an Activity |
| **Extraction** | `util/DetectionUtils.kt` -- `iou(a, b)`, `mergeDetections(list)`, `stabilizeDetections(history)`, `normalizeBox(box, w, h)`, `parseBox(coords)` |
| **Scope** | Cross-module (also used by VisionViewModel) |
| **Impact** | ~150 lines moved out of Activity, testable in isolation |

---

## Finding 21: Inline Haversine Distance Calculation

| Aspect | Detail |
|--------|--------|
| **Files** | `collection/ui/dashboard/RetakeFragment.kt` L263-271, `navigation/NavigationManager.kt` L348 |
| **State** | Inline -- full Haversine implementation in a Fragment, earth radius `6371000.0` as magic number |
| **Extraction** | `util/GeoUtils.kt` -- `haversineDistance(lat1, lon1, lat2, lon2): Double` |
| **Scope** | Cross-module |
| **Impact** | ~15 lines moved, reusable for any distance calculation |

---

## Finding 22: Inline Text Processing -- Regex Duplicated 6 Times

| Aspect | Detail |
|--------|--------|
| **Files** | `voice/VoiceCommandInterpreter.kt` L100-107/L118-150, `util/TextUtils.kt` L11-14 |
| **State** | Duplicated -- `cleanText()` strips Chinese punctuation; `TextUtils.cleanSpeechText()` does a subset. The regex `.replace(Regex("^[的吧吗呢啊]+"), "").replace(Regex("[的吧吗呢啊]+$"), "").trim()` is copy-pasted 4 times inside `extractDestination()` alone. |
| **Extraction** | Consolidate into `util/TextUtils.kt` -- add `stripChineseParticles(text): String` |
| **Scope** | Cross-module |
| **Impact** | ~30 lines saved, single regex definition |

---

## Finding 23: Inline OkHttp Client Construction (2+ copies)

| Aspect | Detail |
|--------|--------|
| **Files** | `network/TripPreviewService.kt` L30-33, `voice/LlmFunctionCaller.kt` L48-51 |
| **State** | Duplicated -- both build `OkHttpClient.Builder()` with different timeouts. Also duplicate `JSON_MEDIA = "application/json; charset=utf-8".toMediaType()` (TripPreviewService L27, LlmFunctionCaller L45). |
| **Extraction** | `network/HttpClientProvider.kt` -- Hilt singleton providing configured `OkHttpClient` instances, or inject via DI module |
| **Scope** | Cross-module (library candidate) |
| **Impact** | ~20 lines saved, single timeout configuration |

---

## Finding 24: Inline API Path Construction Bypassing AppConstants

| Aspect | Detail |
|--------|--------|
| **Files** | `network/TripPreviewService.kt` L41/L77, `voice/LlmFunctionCaller.kt` L117-119 |
| **State** | Inline -- `"$baseUrl/api/navigation/preview"` at L41 duplicates `AppConstants.PREVIEW_API_PATH` (L35). `"v1/chat/completions"` at L117 duplicates `AppConstants.LLM_API_PATH` (L30). |
| **Extraction** | Use existing `AppConstants` paths. Add `normalizeBaseUrl()` usage. |
| **Scope** | Cross-module |
| **Impact** | ~10 lines, eliminates path drift |

---

## Finding 25: Duplicated Urgency-to-Text Mapping (3 places)

| Aspect | Detail |
|--------|--------|
| **Files** | `ui/vision/VisionViewModel.kt` L283-288, `ObstacleAlertTracker.kt` L93-95, `DetectionOverlayView.kt` L223-227 |
| **State** | Duplicated -- 3 independent mappings from `ObstacleUrgency` enum to Chinese display strings |
| **Extraction** | Add `ObstacleUrgency.displayName()` or `ObstacleUrgency.toSpeechText()` to the enum, or a shared `ObstacleTextUtils.kt` |
| **Scope** | Cross-module |
| **Impact** | ~20 lines saved, single place to update urgency labels |

---

## Finding 26: Inline Hardcoded Chinese Strings (should be string resources)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduTtsManager.kt` L74/L197/L204/L246, `data/tts/TtsPreloader.kt` L71, `ObstacleAlertTracker.kt` L93-95, `collection/ui/base/BaseCaptureFragment.kt` L113, `collection/ui/dashboard/RetakeFragment.kt` L93, `network/TripPreviewService.kt` L41/L77 |
| **State** | Inline -- Chinese text hardcoded in source instead of `strings.xml` |
| **Extraction** | Move to `res/values/strings.xml`, reference via `R.string.*` |
| **Scope** | Cross-module |
| **Impact** | ~15 strings, enables future i18n |

---

## Finding 27: Scattered Magic Numbers -- Animation Durations

| Aspect | Detail |
|--------|--------|
| **Files** | `MainActivity.kt` (150ms x9, 200ms x4, 250ms x3, 50ms x2, 600ms x2), `ui/ringmenu/RingMenuCoordinator.kt` (350/200/800/1200/150/250/150 ms), `ui/ringmenu/RingMenuView.kt` (8 dimension ratios) |
| **State** | Scattered -- animation durations are raw numeric literals with no named constants |
| **Extraction** | `config/AnimationConstants.kt` -- `VOICE_RIPPLE_FADE_MS`, `RING_MENU_SHOW_MS`, etc. |
| **Scope** | Cross-module |
| **Impact** | ~30 magic numbers named, enables animation tuning without code changes |

---

## Finding 28: Scattered Magic Numbers -- Detection Algorithm Tuning

| Aspect | Detail |
|--------|--------|
| **Files** | `VisionTestActivity.kt` (0.35f, 1.5f, 5, 4, 5), `ui/vision/VisionViewModel.kt` (0.35f, 1.5f, 5, 4), `ObstacleRiskAnalyzer.kt` (0.70f, 0.50f, 0.30f) |
| **State** | Scattered -- IOU threshold `0.35f` appears in 2 files, normalization threshold `1.5f` in 2 files, history depth `5` in 2 files, urgency thresholds unnamed |
| **Extraction** | `config/DetectionConfig.kt` or add to `AppConfigProvider` |
| **Scope** | Cross-module |
| **Impact** | ~15 magic numbers named, enables runtime tuning |

---

## Finding 29: Duplicated Handler Creation (6 files)

| Aspect | Detail |
|--------|--------|
| **Files** | `stt/BaiduSpeechManager.kt` L21, `stt/BaiduTtsManager.kt` L39, `stt/SpeechRecognitionManager.kt` L24, `stt/SpeechRecognitionService.kt` L25+39, `stt/UnifiedTtsManager.kt` L43, `data/tts/TtsPlayer.kt` L37 |
| **State** | Duplicated -- all create `Handler(Looper.getMainLooper())` |
| **Extraction** | Inject via DI module or provide via `MainThreadDispatcher` abstraction |
| **Scope** | Cross-module |
| **Impact** | ~10 lines, consistent threading |

---

## Finding 30: Duplicated Toast Pattern (40+ call sites)

| Aspect | Detail |
|--------|--------|
| **Files** | `MainActivity.kt` (25+ calls), `VisionTestActivity.kt` (9 calls), `collection/ui/base/BaseCaptureFragment.kt` (8 calls), `collection/ui/dashboard/DashboardFragment.kt` (3 calls), `collection/ui/dashboard/RetakeFragment.kt` (3 calls), `ui/main/history/HistoryFragment.kt` L69, `ui/main/settings/SettingsFragment.kt` L78 |
| **State** | Scattered -- every file calls `Toast.makeText(context, ..., Toast.LENGTH_SHORT).show()` directly |
| **Detail** | Fragment/ViewModel pairs also duplicate the `_toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)` pattern (HistoryViewModel L35, SettingsViewModel L47) |
| **Extraction** | `util/ToastHelper.kt` -- extension function `Context.showToast(message)`, or ViewModel base class with shared `_toastMessage` flow |
| **Scope** | Cross-module |
| **Impact** | ~60 lines saved across call sites, single Toast style |

---

## Finding 31: Duplicated TaskStorage Read-Modify-Write Cycle (7 occurrences)

| Aspect | Detail |
|--------|--------|
| **Files** | `collection/data/TaskStorage.kt` L34-39, L61-69, L74-82, L86-88, L108-131, L138-148 |
| **State** | Duplicated -- every public method does `val tasks = getAllTasks().toMutableList(); /* modify */; prefs.edit().putString(KEY, tasksToJson(tasks)).apply()` |
| **Extraction** | `TaskStorage.kt` -- add private `updateTasks(transform: (MutableList<CaptureTask>) -> Unit)` helper |
| **Scope** | Module-internal (collection) |
| **Impact** | ~50 lines saved within the file, eliminates 7 identical save calls |

---

## Finding 32: Non-Lifecycle-Scoped Coroutines in Activities

| Aspect | Detail |
|--------|--------|
| **Files** | `MainActivity.kt` L505-507, L547-555, L944-956 (raw `Thread {}.start()`); `VisionTestActivity.kt` L66 (`CoroutineScope(Job() + Dispatchers.Main)` -- dead code), L277-341 (raw threads) |
| **State** | Scattered -- database and network operations use raw threads instead of `lifecycleScope.launch`. `runOnUiThread` callbacks can crash if Activity is destroyed mid-operation. |
| **Extraction** | Replace `Thread {}.start()` with `lifecycleScope.launch(Dispatchers.IO) { ... }`. Remove dead `scope` variable from VisionTestActivity. |
| **Scope** | Cross-module |
| **Impact** | Fixes 3+ potential crash sites, eliminates ~20 lines of manual thread management |

---

## Finding 33: Duplicated Lifecycle-Scope Collection Boilerplate in Fragments

| Aspect | Detail |
|--------|--------|
| **Files** | `ui/main/settings/SettingsFragment.kt` L55-80 (8 launches), `ui/main/history/HistoryFragment.kt` L45-70 (5 launches) |
| **State** | Duplicated -- each file launches a separate coroutine per StateFlow/SharedFlow |
| **Extraction** | Use `flow.collectLatestWithLifecycle(viewLifecycleOwner)` extension, or `repeatOnLifecycle(Lifecycle.State.STARTED)` block with multiple collectors |
| **Scope** | Cross-module |
| **Impact** | ~40 lines saved, less boilerplate |

---

## Finding 34: Inline Config Values Bypassing AppConfigProvider

| Aspect | Detail |
|--------|--------|
| **Files** | `voice/VoiceInteractionManager.kt` L37 (TOAST_DURATION_MS=1200), L114 (8000), L314 (2000), L384 (5000); `ui/voice/GestureVoiceLauncher.kt` L31 (LONG_PRESS_DURATION_MS=500), L86 (50*50), L145 (100); `voice/VoiceCommandInterpreter.kt` L91 (2); `voice/LlmFunctionCaller.kt` L97 (0.1), L98 (256) |
| **State** | Scattered -- all these values have corresponding entries in `AppConfigProvider` but the code hardcodes them instead of reading from config |
| **Extraction** | Inject `AppConfigProvider` into these classes and use its property accessors |
| **Scope** | Cross-module |
| **Impact** | ~10 hardcoded values replaced, enables runtime configuration |

---

## Finding 35: Inline Bitmap Utilities

| Aspect | Detail |
|--------|--------|
| **Files** | `collection/ui/dashboard/DashboardFragment.kt` L216-227 (decode-with-sampling), `VisionTestActivity.kt` L723-733 (toJpegBytes + rotateForDisplay) |
| **State** | Inline -- standard bitmap operations inlined in UI classes |
| **Extraction** | `util/BitmapUtils.kt` -- `decodeSampled(filePath, targetPx)`, `Bitmap.toJpegBytes(quality)`, `Bitmap.rotate(degrees)` |
| **Scope** | Cross-module |
| **Impact** | ~30 lines moved, reusable |

---

## Finding 36: Scattered Color Constants

| Aspect | Detail |
|--------|--------|
| **Files** | `DetectionOverlayView.kt` (7 `Color.parseColor` calls), `RingMenuView.kt` (implicit), `GridCaptureFragment.kt` L109/154/155, `DashboardFragment.kt` L165, `BaseCaptureFragment.kt` L179/187/373, `RetakeFragment.kt` L207 |
| **State** | Scattered -- same semantic colors (green=#4CAF50, orange=#FF9800, red=#F44336, gray=#888888) appear as different hex representations across files |
| **Extraction** | `res/values/colors.xml` entries or `config/AppColors.kt` constants |
| **Scope** | Cross-module |
| **Impact** | ~20 color literals centralized, consistent theming |

---

## Recommended Extraction Roadmap

### Phase 1 -- Quick Wins (1-2 days, ~400 lines saved)

| Utility | Target File | Files Simplified |
|---------|------------|-----------------|
| `util/VibrationHelper.kt` | New | 5 files |
| `util/GeoUtils.kt` | New | 2 files |
| `util/BitmapUtils.kt` | New | 2 files |
| `util/TextUtils.kt` enhancement | Existing | 2 files |
| `TaskStorage.updateTasks()` refactor | Existing | 1 file (7 occurrences) |
| Delete `AppConfig` key constants, migrate to `AppConstants` | 2 files | ~10 files affected |

### Phase 2 -- STT/TTS Consolidation (2-3 days, ~500 lines saved)

| Utility | Target File | Files Simplified |
|---------|------------|-----------------|
| `stt/STTCallback.kt` | New | 3 files |
| `stt/TtsCallback.kt` | New | 2 files |
| `stt/SpeechEngine.kt` interface | New | 2 files |
| `stt/BaiduTokenProvider.kt` | New | 2 files |
| `stt/BaiduTtsApiClient.kt` | New | 2 files |
| `stt/SpeechQueue.kt` | New | 3 files |
| Refactor `BaiduTtsManager` to use `TtsAudioCache` | Existing | 2 files |

### Phase 3 -- UI Layer Consolidation (2-3 days, ~500 lines saved)

| Utility | Target File | Files Simplified |
|---------|------------|-----------------|
| `ui/voice/VoiceTouchHandler.kt` | New | 2 files |
| `util/PermissionHelper.kt` | New | 5 files |
| `util/DetectionUtils.kt` | New | 2 files |
| `util/CameraHelper.kt` (or RetakeFragment extends Base) | New/existing | 2 files |
| `util/ToastHelper.kt` or ViewModel base class | New | 7+ files |
| `config/AnimationConstants.kt` | New | 3 files |

### Phase 4 -- Config Unification (1-2 days, ~200 lines saved)

| Utility | Target File | Files Simplified |
|---------|------------|-----------------|
| Delete `AppConfig.kt`, migrate all to `AppConfigProvider` + `AppConstants` | Delete + refactor | ~8 files |
| Inject `AppConfigProvider` into `VoiceInteractionManager`, `GestureVoiceLauncher`, `VoiceCommandInterpreter`, `LlmFunctionCaller` | Refactor | 4 files |
| `network/HttpClientProvider.kt` | New | 3+ files |
| Move hardcoded Chinese strings to `strings.xml` | Existing | 8 files |

---

## Appendix: Files with Highest Coupling Density

| File | Duplicate Count | Inline Logic Count | Magic Number Count |
|------|----------------|-------------------|-------------------|
| `MainActivity.kt` | 6 patterns | 4 items | 15+ literals |
| `VisionTestActivity.kt` | 4 patterns | 6 items | 15+ literals |
| `stt/BaiduTtsManager.kt` | 6 patterns | 2 items | 8 literals |
| `stt/SpeechRecognitionManager.kt` | 3 patterns | 0 | 0 |
| `stt/SpeechRecognitionService.kt` | 3 patterns | 0 | 0 |
| `stt/UnifiedTtsManager.kt` | 3 patterns | 0 | 3 literals |
| `data/tts/TtsPlayer.kt` | 2 patterns | 0 | 2 literals |
| `voice/VoiceInteractionManager.kt` | 0 | 1 item | 6 literals |
| `voice/LlmFunctionCaller.kt` | 1 pattern | 3 items | 4 literals |
| `voice/VoiceCommandInterpreter.kt` | 1 pattern | 1 item | 2 literals |
| `collection/ui/base/BaseCaptureFragment.kt` | 4 patterns | 3 items | 4 literals |
| `collection/ui/dashboard/RetakeFragment.kt` | 5 patterns | 2 items | 3 literals |
| `collection/data/TaskStorage.kt` | 2 patterns | 1 item | 0 |
| `ui/ringmenu/RingMenuCoordinator.kt` | 1 pattern | 0 | 15+ literals |
| `ui/vision/VisionViewModel.kt` | 0 | 3 items | 10+ literals |
| `ObstacleAlertTracker.kt` | 0 | 1 item | 1 literal |
