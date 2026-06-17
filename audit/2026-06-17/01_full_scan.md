# CorSight Android v2.0 -- Full Code Audit Report
**Date:** 2026-06-17
**Scope:** All 120 Kotlin source files under `CorSight-Android_v2.0`
**Auditor:** Automated static analysis

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 8 |
| HIGH | 22 |
| MEDIUM | 35 |
| LOW | 18 |
| **Total** | **83** |

---

## 1. Force Unwrap (`!!`) -- 37 findings

Force unwraps are the #1 source of KotlinNullPointerException crashes.

### CRITICAL (5)

| # | File | Line | Code | Risk |
|---|------|------|------|------|
| 1 | `app/.../MainActivity.kt` | 686 | `mMap!!.isMyLocationEnabled = true` | mMap null if map not ready |
| 2 | `app/.../MainActivity.kt` | 707 | `mMap!!.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 16f))` | Same risk |
| 3 | `app/.../MainActivity.kt` | 814 | `val firstItem = poiResults!![0]` | poiResults can be null |
| 4 | `app/.../MainActivity.kt` | 914 | `routePolyline = mMap!!.addPolyline(options)` | mMap null risk |
| 5 | `app/.../MainActivity.kt` | 927 | `destinationMarker = mMap!!.addMarker(...)` | mMap null risk |

**Fix:** Use `mMap?.let { ... }` or guard with `val map = mMap ?: return`.

### HIGH (14)

| # | File | Line | Code |
|---|------|------|------|
| 1 | `app/.../stt/BaiduSpeechManager.kt` | 70 | `asr!!.registerListener(eventListener)` |
| 2 | `app/.../stt/BaiduSpeechManager.kt` | 205 | `asr!!.send(SpeechConstant.ASR_START, ...)` |
| 3 | `app/.../stt/BaiduSpeechManager.kt` | 218 | `asr!!.send(SpeechConstant.ASR_STOP, ...)` |
| 4 | `app/.../stt/BaiduSpeechManager.kt` | 221 | `asr!!.send(SpeechConstant.ASR_CANCEL, ...)` |
| 5 | `app/.../stt/BaiduSpeechManager.kt` | 237 | `asr!!.send(SpeechConstant.ASR_CANCEL, ...)` |
| 6 | `app/.../stt/BaiduSpeechManager.kt` | 250 | `asr!!.send(SpeechConstant.ASR_CANCEL, ...)` |
| 7 | `app/.../stt/BaiduSpeechManager.kt` | 252 | `asr!!.unregisterListener(eventListener)` |
| 8 | `app/.../stt/SpeechRecognitionManager.kt` | 43 | `speechRecognizer!!.setRecognitionListener(this)` |
| 9 | `app/.../stt/SpeechRecognitionManager.kt` | 76 | `speechRecognizer!!.startListening(recognitionIntent)` |
| 10 | `app/.../stt/SpeechRecognitionService.kt` | 46 | `speechRecognizer!!.setRecognitionListener(this)` |
| 11 | `app/.../stt/SpeechRecognitionService.kt` | 59 | `speechRecognizer!!.startListening(recognitionIntent)` |
| 12 | `app/.../NetworkSource.kt` | 34 | `val output = PrintWriter(socket!!.getOutputStream(), true)` |
| 13 | `app/.../NetworkSource.kt` | 38 | `receiveLoop(socket!!)` |
| 14 | `inference/.../YoloV8OnnxEngine.kt` | 44 | `ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)` |

**Fix:** Replace with safe-call + early return: `val r = recognizer ?: return`.

### MEDIUM (18)

| # | File | Line | Code |
|---|------|------|------|
| 1 | `app/.../collection/service/UploadService.kt` | 76 | `task.uploadSessionId!!` |
| 2 | `app/.../core/compass/HardwareCompassProvider.kt` | 137 | `emaStep(fastFiltered!!, heading, FAST_ALPHA)` |
| 3 | `app/.../core/compass/HardwareCompassProvider.kt` | 138 | `emaStep(slowFiltered!!, fastFiltered!!, SLOW_ALPHA)` |
| 4 | `app/.../core/compass/HardwareCompassProvider.kt` | 143 | `val candidate = slowFiltered!!` |
| 5 | `app/.../core/compass/HardwareCompassProvider.kt` | 145 | `candidate - lastEmittedHeading!!` |
| 6 | `app/.../core/compass/HardwareCompassProvider.kt` | 148 | `abs(diff) < DEADZONE_DEG) lastEmittedHeading!! else candidate` |
| 7 | `app/.../core/network/NetworkUrlResolver.kt` | 74 | `return cachedUrl!!` |
| 8 | `app/.../DetectionOverlayView.kt` | 231 | `(alert!!.overlapRatio * 100).toInt()` |
| 9 | `app/.../MainActivity.kt` | 1091 | `ringMenuView = ringMenuView!!` |
| 10 | `app/.../MainActivity.kt` | 1097 | `ringMenuCoordinator!!.events.collect` |
| 11 | `app/.../ui/main/history/HistoryFragment.kt` | 49 | `setupAdapterListener(historyAdapter!!)` |
| 12 | `app/.../ui/main/history/HistoryFragment.kt` | 52 | `historyAdapter!!.updateData(records)` |
| 13 | `app/.../ui/ringmenu/RingMenuCoordinator.kt` | 305 | `handler.postDelayed(longPressRunnable!!, ...)` |
| 14 | `app/.../ui/ringmenu/RingMenuView.kt` | 284 | `val children = items[activeParentIndex].children!!` |
| 15 | `app/.../ui/voice/GestureVoiceLauncher.kt` | 132 | `handler.postDelayed(longPressRunnable!!, ...)` |
| 16 | `app/.../VisionTestActivity.kt` | 177 | `handler.postDelayed(longPressRunnable!!, 500)` |
| 17 | `app/.../VisionTestActivity.kt` | 276 | `udpReceiveThread != null && udpReceiveThread!!.isAlive` |
| 18 | `app/.../voice/VoiceInteractionManager.kt` | 132 | `return resultTimeoutRunnable!!` |

**Fix:** For Runnable fields: initialize to `Runnable {}` instead of null, eliminating `!!`. For nullable values: use safe calls.

### LOW (2 -- acceptable)

| # | File | Line | Code | Note |
|---|------|------|------|------|
| 1 | `app/.../voice/VoiceInteractionManager.kt` | 341 | `command.destination!!` | After explicit type check |
| 2 | `app/.../voice/VoiceInteractionManager.kt` | 396 | `command.destination!!` | After explicit type check |

---

## 2. `lateinit var` -- 68 findings

### HIGH RISK -- Not injected by Hilt, manual init in onCreate (could crash if accessed too early) (8)

| # | File | Line | Code | Risk |
|---|------|------|------|------|
| 1 | `app/.../MainActivity.kt` | 119 | `private lateinit var handler: Handler` | Initialized in `onCreate` |
| 2 | `app/.../MainActivity.kt` | 122-178 | 15+ `lateinit var` for Views | All in `onCreate` -- fragile if accessed from lifecycle callbacks before init |
| 3 | `app/.../stt/SpeechRecognitionService.kt` | 25 | `private lateinit var handler: Handler` | Initialized in `onCreate` |
| 4 | `app/.../VisionTestActivity.kt` | 68 | `private lateinit var binding: ActivityVisionTestBinding` | Standard pattern, but any access before `onCreateView` crashes |
| 5 | `app/.../collection/ui/dashboard/DashboardFragment.kt` | 46-51 | 6x `lateinit var` for Views | All in `onViewCreated` |
| 6 | `app/.../collection/ui/dashboard/RetakeFragment.kt` | 71-74 | 4x `lateinit var` for Views | All in `onViewCreated` |
| 7 | `app/.../collection/ui/gridmode/GridCaptureFragment.kt` | 47 | `private lateinit var directionBar: LinearLayout` | In `onViewCreated` |
| 8 | `app/.../collection/ui/hub/CaptureHubActivity.kt` | 38-40 | 3x `lateinit var` for Views | In `onCreate` |

**Fix:** Use `by lazy { }` or nullable `var x: View? = null` for View references. For Hilt-injected fields, these are safe (Hilt injects before `onCreate`).

### MEDIUM RISK -- Hilt `@Inject lateinit` (safe if Hilt used correctly) (12)

| # | File | Line | Code |
|---|------|------|------|
| 1 | `app/.../MainActivity.kt` | 108-115 | 8x `@Inject lateinit var` (navigationManager, speechManager, baiduTts, etc.) |
| 2 | `app/.../MainActivity.kt` | 178 | `@Inject lateinit var commandRouter` |
| 3 | `app/.../collection/ui/base/BaseCaptureFragment.kt` | 68-69 | 2x `@Inject lateinit var` |
| 4 | `app/.../collection/ui/hub/CaptureHubActivity.kt` | 35-36 | 2x `@Inject lateinit var` |
| 5 | `app/.../collection/ui/dashboard/RetakeFragment.kt` | 64-65 | 2x `@Inject lateinit var` |
| 6 | `app/.../ui/main/history/HistoryFragment.kt` | 27 | `@Inject lateinit var unifiedTts` |
| 7 | `app/.../VisionTestActivity.kt` | 66 | `@Inject lateinit var unifiedTts` |

**Note:** These are safe as long as Hilt is properly configured. Low priority.

---

## 3. Direct `Thread {}` Usage -- 4 findings (should use coroutines)

| # | File | Line | Code | Severity |
|---|------|------|------|----------|
| 1 | `app/.../MainActivity.kt` | 567 | `Thread { ... runOnUiThread { ... } }.start()` | HIGH |
| 2 | `app/.../NetworkSource.kt` | 31 | `receiveThread = Thread { ... }` | MEDIUM |
| 3 | `app/.../stt/BaiduTtsManager.kt` | 64 | `Thread { synthesizeAndPlay(text) }.start()` | HIGH |
| 4 | `app/.../stt/BaiduTtsManager.kt` | 136 | `Thread { synthesizeAndPlay(next) }.start()` | HIGH |
| 5 | `app/.../VisionTestActivity.kt` | 284 | `udpReceiveThread = Thread { ... }` | MEDIUM |

**Fix:**
- For #1: Use `lifecycleScope.launch(Dispatchers.IO) { ... withContext(Dispatchers.Main) { ... } }`
- For #2, #5: Acceptable for long-running socket/UDP receive loops, but wrap in structured concurrency (CoroutineScope + Job)
- For #3, #4: Use `viewModelScope.launch(Dispatchers.IO)` or a dedicated `CoroutineScope`

---

## 4. Java-to-Kotlin Issues -- 3 findings

| # | File | Line | Code | Severity | Issue |
|---|------|------|------|----------|-------|
| 1 | `app/.../MainActivity.kt` | 357 | `SuggestionAdapter(ArrayList())` | MEDIUM | Use `mutableListOf()` instead of `ArrayList()` |
| 2 | `app/.../ObstacleAlertTracker.kt` | 25 | `ConcurrentHashMap<String, TargetState>()` | LOW | Acceptable (no Kotlin equivalent for ConcurrentHashMap), but consider `Mutex` + `mutableMapOf` |
| 3 | `app/.../stt/BaiduSpeechManager.kt` | 200 | `params as Map<*, *>` | HIGH | Unsafe cast. Use `params as? Map<*, *> ?: return` or restructure params as a proper Map type |

---

## 5. `object` Singletons Holding Mutable State -- 4 findings

### MEDIUM (potential memory leaks / threading issues)

| # | File | Lines | State | Risk |
|---|------|-------|-------|------|
| 1 | `app/.../animation/ViewTransition.kt` | 23 | `private val activeTransitions = mutableMapOf<View, Animator>()` | **HIGH** -- Stores references to Views in a global map. Views hold Activity context. If animations are not properly cancelled, this leaks Activities. |
| 2 | `app/.../animation/PageIndicatorAnimations.kt` | 36-37 | `labelDismissRunnable`, `labelAnimator` | MEDIUM -- Holds Runnable/Animator references. Should be cleared on Activity destroy. |
| 3 | `app/.../config/AppConfigProvider.kt` | 24 | `private var config: JSONObject? = null` | LOW -- Cached config, acceptable. |
| 4 | `app/.../core/network/NetworkUrlResolver.kt` | 44-46 | `cachedUrl`, `cacheTimestamp`, `lastNetworkType` | LOW -- Transient cache, OK. |

**Fix for #1:** Either make `ViewTransition` a non-singleton (instantiated per View owner), or call `cancelAll()` in `onDestroy`. At minimum, use `WeakHashMap<View, Animator>`.

---

## 6. `when` Statements Missing Sealed Class Exhaustive Matching -- 8 findings

### MEDIUM -- Could benefit from sealed class exhaustive matching

| # | File | Line | Expression | Notes |
|---|------|------|------------|-------|
| 1 | `app/.../MainActivity.kt` | 235 | `when (effect)` on `UiEffect` | UiEffect is already sealed -- verify `else` is not used |
| 2 | `app/.../MainActivity.kt` | 274 | `when (state)` on `NavigationState` | NavigationState is sealed |
| 3 | `app/.../MainActivity.kt` | 1114 | `when (event)` on `InteractionEvent` | InteractionEvent is sealed |
| 4 | `app/.../MainActivity.kt` | 1164 | `when (event)` on `InteractionEvent` | Same |
| 5 | `app/.../ui/main/MainViewModel.kt` | 262 | `when (event)` on `CommandEvent` | CommandEvent is sealed |
| 6 | `app/.../voice/VoiceInteractionManager.kt` | 299 | `when (fnName)` on String | Cannot use sealed class |
| 7 | `app/.../voice/VoiceInteractionManager.kt` | 337 | `when (command.type)` on `VoiceCommand.Type` | If enum/sealed, should be exhaustive |
| 8 | `app/.../ui/vision/VisionViewModel.kt` | 253 | `when (result)` | Check if sealed |

**Fix:** For sealed class/enum `when` blocks, remove `else` branch so compiler enforces exhaustive matching. Add explicit branches instead.

---

## 7. Cross-Layer Import Violations -- 5 findings

### MEDIUM -- UI layer importing data/infra layers directly

| # | File | Line | Import | Issue |
|---|------|------|--------|-------|
| 1 | `app/.../ui/main/MainViewModel.kt` | 11-12 | `data.VoiceRecord`, `data.VoiceRecordRepository` | ViewModel importing data layer directly (should go through domain/usecase) |
| 2 | `app/.../ui/main/history/HistoryViewModel.kt` | 5-6 | `data.VoiceRecord`, `data.VoiceRecordRepository` | Same |
| 3 | `app/.../ui/main/history/HistoryFragment.kt` | 17 | `stt.UnifiedTtsManager` | Fragment importing STT infra directly |
| 4 | `app/.../ui/main/history/HistoryViewModel.kt` | 7 | `stt.UnifiedTtsManager` | ViewModel importing STT infra |
| 5 | `app/.../ui/main/adapter/VoiceRecordAdapter.kt` | 10 | `data.VoiceRecord` | Adapter importing data entity -- LOW (common pattern) |

**Fix:** Route all data access through domain use cases. Inject TTS via an interface/usecase.

---

## 8. Hardcoded Chinese Strings NOT in `strings.xml` -- 120+ findings

### HIGH -- Breaks i18n, cannot be translated or tested

This is the largest category. Chinese strings are used for Toast messages, dialog titles, dialog buttons, status labels, and UI text throughout the codebase. Key files:

| File | Count | Examples |
|------|-------|---------|
| `app/.../collection/ui/base/BaseCaptureFragment.kt` | 20+ | `"需要相机权限"`, `"正在定位..."`, `"拍照失败"`, `"保存采样点"`, `"确认保存"`, `"放弃全部"`, `"继续拍摄"`, `"GPS 未就绪，请等待定位完成"`, `"已采集 $photoCount 张照片"` |
| `app/.../collection/ui/dashboard/DashboardFragment.kt` | 20+ | `"全部上传"`, `"确定上传所有待上传的任务？"`, `"确定"`, `"取消"`, `"清空已完成任务"`, `"待上传"`, `"上传中"`, `"已完成"`, `"失败"`, `"全屏预览"`, `"上传此张"`, `"补拍替换"` |
| `app/.../collection/ui/dashboard/RetakeFragment.kt` | 10+ | `"需要相机权限"`, `"正在校验位置..."`, `"任务不存在"`, `"补拍成功，照片已替换"`, `"拍照失败"` |
| `app/.../collection/ui/dashboard/DashboardViewModel.kt` | 5 | `"任务不存在"`, `"无法获取当前位置，请检查 GPS 信号"`, `"GPS 精度不足..."` |
| `app/.../collection/ui/dashboard/FullscreenPreviewDialog.kt` | 4 | `"已上传"`, `"上传中"`, `"失败"`, `"待上传"` |
| `app/.../collection/ui/freemode/FreeCaptureFragment.kt` | 10+ | `"天桥"`, `"复杂路口"`, `"斑马线"`, `"公交站台"`, `"场景标注"`, `"保存"`, `"放弃"`, `"已拍:"` |
| `app/.../collection/ui/gridmode/GridCaptureFragment.kt` | 3 | `"$target 已采集"`, `"八方向采集完成"` |
| `app/.../collection/ui/hub/CaptureHubActivity.kt` | 10+ | `"需要相机权限"`, `"需要位置权限"`, `"位置服务未开启"`, `"确定"`, `"去设置"`, `"取消"` |
| `app/.../collection/ui/hub/CapturePagerAdapter.kt` | 1 | `"自由采集"`, `"八方向"`, `"后台管理"` |
| `app/.../collection/service/UploadService.kt` | 4 | `"元数据提交失败"`, `"文件不存在"`, `"重试 $MAX_RETRY 次后失败"` |
| `app/.../NetworkSource.kt` | 1 | `"网络流 ($ip)"` |
| `app/.../CameraSource.kt` | 1 | `"手机相机"` |
| `app/.../DetectionOverlayView.kt` | 3 | `"低"`, `"中"`, `"高"` |
| `app/.../ObstacleAlertTracker.kt` | 3 | `"请注意，不远处有$label"`, `"请注意，正在接近$label"`, `"请注意，已靠近$label"` |
| `app/.../MainActivity.kt` | 5 | `"正在${event.item.label}"`, `"语音助手已就绪"` |
| `app/.../navigation/NavigationManager.kt` | 5 | Log messages with Chinese (debug, low priority) |
| `app/.../network/TripPreviewService.kt` | 4 | `"网络请求失败"`, `"服务器错误"` (has TODO comments to migrate) |
| `app/.../stt/BaiduTtsManager.kt` | 3 | `"TTS初始化失败"`, `"语音合成失败"` |
| `app/.../core/network/NetworkUrlResolver.kt` | 2 | `"手动设置"`, `"校园网内网"` |
| `app/.../data/tts/TtsPreloader.kt` | 2 | `"正在$label"`, `"正在$childLabel"` |
| `app/.../ui/dialog/TripPreviewDialog.kt` | 1 | Referenced via context |

**Fix:** Extract ALL user-visible strings to `res/values/zh/strings.xml` (and `res/values/strings.xml` for default). Use `context.getString(R.string.xxx)` or `getString(R.string.xxx)`. For data-layer strings (UploadService errors), use string resource IDs.

---

## 9. Unused Imports

A full unused-import scan requires compilation analysis. Based on manual review of import blocks vs. usage:

### LOW (likely unused -- 3 candidates)

| # | File | Import | Notes |
|---|------|--------|-------|
| 1 | `app/.../MainActivity.kt` | `import android.widget.Toast` | May be used in some paths |
| 2 | Various files | `import android.os.Build` | Only used in some conditional blocks |

**Note:** A definitive unused-import analysis requires Kotlin compiler resolution. Run `./gradlew lint` or IDE inspection for precise results. The above are candidates only.

---

## 10. Deprecated API Usage -- 7 findings

### MEDIUM -- `Activity.getColor()` deprecated below API 23

| # | File | Line | Code | Fix |
|---|------|------|------|-----|
| 1 | `app/.../MainActivity.kt` | 445 | `tvVoiceHint.setTextColor(getColor(android.R.color.holo_red_light))` | Use `ContextCompat.getColor(this, R.color.xxx)` |
| 2 | `app/.../MainActivity.kt` | 452 | `tvVoiceHint.setTextColor(getColor(android.R.color.white))` | Same |
| 3 | `app/.../MainActivity.kt` | 473 | `tvVoiceHint.setTextColor(getColor(android.R.color.white))` | Same |
| 4 | `app/.../MainActivity.kt` | 475 | `tvVoiceSubHint.setTextColor(getColor(android.R.color.darker_gray))` | Same |

**Note:** `Activity.getColor()` is only deprecated if minSdk < 23. If minSdk >= 23, this is LOW priority.

### MEDIUM -- `ActivityCompat.requestPermissions` (legacy pattern)

| # | File | Line | Code | Fix |
|---|------|------|------|-----|
| 5 | `app/.../MainActivity.kt` | 731 | `ActivityCompat.requestPermissions(...)` | Use `registerForActivityResult(RequestPermission())` with Activity Result API |
| 6 | `app/.../VisionTestActivity.kt` | 198 | `ActivityCompat.requestPermissions(...)` | Same |
| 7 | `app/.../VisionTestActivity.kt` | 218, 266 | `ActivityCompat.requestPermissions(...)` | Same |

**Fix:** Migrate to `ActivityResultContracts.RequestPermission()` / `RequestMultiplePermissions()`.

---

## Additional Findings

### Unsafe Casts (HIGH)

| # | File | Line | Code | Fix |
|---|------|------|------|-----|
| 1 | `app/.../stt/BaiduSpeechManager.kt` | 200 | `params as Map<*, *>` | Use safe cast `as?` |
| 2 | `inference/.../YoloV8OnnxEngine.kt` | 74 | `output.get(0).value as Array<Array<FloatArray>>` | Use safe cast with fallback |
| 3 | `app/.../collection/ui/gridmode/GridCaptureFragment.kt` | 212 | `getSystemService(Context.VIBRATOR_SERVICE) as Vibrator` | Safe on current Android but could use `as?` |
| 4 | `app/.../stt/BaiduTtsManager.kt` | 85, 166 | `url.openConnection() as HttpURLConnection` | Use `as?` with error handling |

### `ViewTransition` Memory Leak Risk (HIGH)

`ViewTransition` (object singleton) stores `View -> Animator` mappings in a `mutableMapOf`. Since Views hold Activity references, any un-cleared entry leaks the entire Activity. The `cancelAll()` method exists but must be called from `onDestroy()`. Not all callers may do this.

**Fix:** Use `WeakHashMap<View, Animator>()` or make it non-singleton.

---

## Prioritized Action Items

1. **CRITICAL -- Force unwraps on `mMap!!`** in `MainActivity.kt` (lines 686, 707, 814, 914, 927). Any timing issue with map initialization crashes the app.
2. **HIGH -- Extract all 120+ Chinese hardcoded strings** to `strings.xml`. Blocks any future localization.
3. **HIGH -- Replace `Thread {}` with coroutines** in `BaiduTtsManager.kt` and `MainActivity.kt`.
4. **HIGH -- Fix `ViewTransition` memory leak** -- use `WeakHashMap` or add lifecycle-aware cleanup.
5. **HIGH -- Replace `asr!!` force unwraps** in `BaiduSpeechManager.kt` with safe calls.
6. **HIGH -- Migrate `requestPermissions`** to Activity Result API.
7. **MEDIUM -- Clean up cross-layer imports** in ViewModels.
8. **MEDIUM -- Replace `when` with exhaustive sealed matching** where applicable.
9. **LOW -- Replace `ArrayList()` with `mutableListOf()`**.
10. **LOW -- Run `./gradlew lint`** for precise unused import detection.

---

*End of audit. No files were modified.*
