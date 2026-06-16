# Cross-Layer Import Violation Audit

> CorSight Android v2.0 | 2026-06-16
> Scope: all `.kt` files in `app/src/main/java/com/example/voicenavigation/`

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 5 |
| HIGH     | 24 |
| MEDIUM   | 8 |
| LOW      | 3 |
| **Total** | **40** |

---

## 1. CRITICAL -- UI layer directly depends on Data layer (Rule 1)

UI should go through domain/repository; Activity/Fragment/ViewModel must not directly use DAO, Database, or data-layer adapters.

| # | File | Line | Violating Import | Detail |
|---|------|------|-----------------|--------|
| 1 | `MainActivity.kt` | 57 | `com.example.voicenavigation.data.AppDatabase` | Activity directly references Room database class. Should be injected via repository. |
| 2 | `MainActivity.kt` | 67 | `com.example.voicenavigation.data.tts.TtsPlayer` | Activity reaches into data/tts layer. TTS playback should be wrapped in a domain service. |
| 3 | `MainActivity.kt` | 68 | `com.example.voicenavigation.data.tts.TtsPreloader` | Activity reaches into data/tts layer. |
| 4 | `MainActivity.kt` | 61 | `com.example.voicenavigation.data.VoiceRecordAdapter` | Adapter is placed in the `data/` package but used from UI. Either move adapter to `ui/` or use it through ViewModel. |
| 5 | `MainActivity.kt` | 60 | `com.example.voicenavigation.data.VoiceRecord` | Activity directly uses data-layer entity. History operations should go through ViewModel/Repository. |

---

## 2. HIGH -- UI layer bypasses domain layer (Rule 1, 5)

UI components directly depend on domain-layer concrete implementations instead of going through ViewModel abstractions.

| # | File | Line | Violating Import | Detail |
|---|------|------|-----------------|--------|
| 6 | `MainActivity.kt` | 62 | `com.example.voicenavigation.navigation.NavigationManager` | Activity directly couples to navigation manager. Should be mediated by MainViewModel. |
| 7 | `MainActivity.kt` | 63 | `com.example.voicenavigation.network.TripPreviewService` | Activity directly uses network service. |
| 8 | `MainActivity.kt` | 64 | `com.example.voicenavigation.stt.BaiduSpeechManager` | Activity directly creates/manages STT instance. |
| 9 | `MainActivity.kt` | 65 | `com.example.voicenavigation.stt.BaiduTtsManager` | Activity directly creates/manages TTS instance. |
| 10 | `MainActivity.kt` | 66 | `com.example.voicenavigation.stt.UnifiedTtsManager` | Activity directly uses unified TTS manager. |
| 11 | `MainActivity.kt` | 69 | `com.example.voicenavigation.voice.LLMFunctionCaller` | Activity directly references voice caller. |
| 12 | `MainActivity.kt` | 85 | `com.example.voicenavigation.voice.VoiceInteractionManager` | Activity directly uses voice interaction manager. |
| 13 | `MainActivity.kt` | 72 | `com.example.voicenavigation.command.CommandRouter` | Activity directly uses command router. |
| 14 | `MainActivity.kt` | 73 | `com.example.voicenavigation.menu.MenuConfig` | Activity directly uses menu config from domain layer. |
| 15 | `VisionTestActivity.kt` | 33 | `com.example.voicenavigation.stt.BaiduTtsManager` | Activity directly creates TTS instance instead of using ViewModel. |
| 16 | `VisionTestActivity.kt` | 34 | `com.example.voicenavigation.stt.UnifiedTtsManager` | Activity directly manages TTS. |
| 17 | `VisionViewModel.kt` | 13-18 | `com.example.voicenavigation.{ImageQualityAnalyzer,ObstacleAlert,ObstacleAlertTracker,ObstacleRiskAnalyzer,ObstacleSpeechEvent,ObstacleUrgency}` | ViewModel in `ui/` directly imports 6 domain/obstacle classes from root package. Obstacle logic should be behind a domain service interface. |
| 18 | `SettingsViewModel.kt` | 8 | `com.example.voicenavigation.network.TripPreviewService` | ViewModel directly uses network service. Should be wrapped by a use-case or repository. |
| 19 | `HistoryFragment.kt` | 17 | `com.example.voicenavigation.stt.UnifiedTtsManager` | Fragment directly depends on domain STT manager. |
| 20 | `HistoryViewModel.kt` | 7 | `com.example.voicenavigation.stt.UnifiedTtsManager` | ViewModel directly depends on domain STT manager. |
| 21 | `HistoryFragment.kt` | 16 | `com.example.voicenavigation.data.VoiceRecordAdapter` | Fragment imports adapter from `data/` package. Adapter should live in `ui/` package. |

---

## 3. HIGH -- Domain layer depends on UI layer (Rule 2)

Domain must never import Activities, Views, or UI-layer types.

| # | File | Line | Violating Import | Detail |
|---|------|------|-----------------|--------|
| 22 | `menu/MenuConfig.kt` | 6 | `com.example.voicenavigation.ui.ringmenu.RingMenuItem` | Domain-layer `MenuConfig` imports `RingMenuItem` from `ui.ringmenu`. The data model `RingMenuItem` must be moved to domain layer to break this reverse dependency. |
| 23 | `animation/RingMenuAnimations.kt` | 8 | `com.example.voicenavigation.ui.ringmenu.RingMenuView` | Animation layer (domain-level) imports a custom View from UI layer. Violates the stated architecture that "View does not contain animation logic" -- but the reverse (animation referencing View) also creates a circular dependency. |
| 24 | `animation/DetectionOverlayAnimations.kt` | 5 | `com.example.voicenavigation.DetectionOverlayView` | Animation layer imports root-level custom View. Same circular risk as above. |

---

## 4. HIGH -- Cross-boundary UI imports (UI to other UI features)

| # | File | Line | Violating Import | Detail |
|---|------|------|-----------------|--------|
| 25 | `ui/main/settings/SettingsFragment.kt` | 18 | `com.example.voicenavigation.collection.ui.hub.CaptureHubActivity` | Main settings UI directly launches a collection sub-feature Activity by class reference. Should use an intent action or navigation component. |

---

## 5. MEDIUM -- ViewModel bypasses repository layer

ViewModels should depend on repository/use-case abstractions, not directly on domain services or data entities.

| # | File | Line | Violating Import | Detail |
|---|------|------|-----------------|--------|
| 26 | `MainViewModel.kt` | 9 | `com.example.voicenavigation.navigation.NavigationManager` | ViewModel couples directly to concrete navigation manager. |
| 27 | `MainViewModel.kt` | 10 | `com.example.voicenavigation.network.TripPreviewService` | ViewModel couples directly to network service. |
| 28 | `MainViewModel.kt` | 11 | `com.example.voicenavigation.voice.VoiceInteractionManager` | ViewModel couples directly to voice manager. |
| 29 | `MainViewModel.kt` | 7 | `com.example.voicenavigation.command.CommandRouter` | ViewModel couples directly to command router. |
| 30 | `MainViewModel.kt` | 6 | `com.example.voicenavigation.command.CommandEvent` | ViewModel depends on domain event type. |
| 31 | `HistoryViewModel.kt` | 5 | `com.example.voicenavigation.data.VoiceRecord` | ViewModel uses data-layer entity directly. Entity types should ideally be mapped/converted at the repository boundary. |
| 32 | `SettingsViewModel.kt` | 6 | `com.example.voicenavigation.AppConfig` | ViewModel directly reads SharedPreferences config. Should use AppConfigProvider (which is already injected). |
| 33 | `SettingsViewModel.kt` | 7 | `com.example.voicenavigation.config.AppConstants` | ViewModel directly references config constants. Minor, but couples ViewModel to config implementation detail. |

---

## 6. LOW -- Misplaced adapters in data/ package

These adapters are UI components (RecyclerView.Adapter) placed in the `data/` package. While their own imports are clean, they are mislocated and create confusing layer boundaries.

| # | File | Line | Violating Import | Detail |
|---|------|------|-----------------|--------|
| 34 | `data/VoiceRecordAdapter.kt` | (file-level) | N/A -- package misplacement | RecyclerView adapter should be in `ui/main/adapter/` per architecture doc. Its `data/` placement causes UI files to import from the data layer. |
| 35 | `data/SuggestionAdapter.kt` | (file-level) | N/A -- package misplacement | Same issue as above. |

---

## 7. LOW -- Global singletons accessed directly (Rule 6)

| # | File | Line | Violating Import | Detail |
|---|------|------|-----------------|--------|
| 36 | `VisionViewModel.kt` | 11 | `com.corsight.vision.ToolRegistry` | Direct access to global singleton `ToolRegistry`. Should be injected via DI. |
| 37 | `VisionTestActivity.kt` | 29 | `com.corsight.vision.ToolRegistry` | Direct access to global singleton `ToolRegistry`. |
| 38 | `VisionTestActivity.kt` | 26 | `com.corsight.inference.ModelRegistry` | Direct access to global singleton `ModelRegistry`. Should be injected. |

---

## 8. LOW -- Obsolete import

| # | File | Line | Violating Import | Detail |
|---|------|------|-----------------|--------|
| 39 | `command/commands/StopNavigationCommand.kt` | 5 | `com.example.voicenavigation.navigation.NavigationManager` | Import is present but `NavigationManager` is injected yet never used in `execute()`. Dead import -- the command only returns `CommandEvent.StopNavigation`. |
| 40 | `data/tts/TtsPlayer.kt` and `data/tts/TtsPreloader.kt` | (file-level) | `com.example.voicenavigation.data.tts.*` package | TTS playback is a domain concern (audio output), not a data-layer concern. These classes are in `data/` but belong in `voice/stt/` or a dedicated `tts/` domain package. |

---

## Architectural Observations

### A. MainActivity.kt is the primary violation hotspot

`MainActivity.kt` (root package, ~1338 lines) accounts for **15 of 40 violations**. It directly imports from `data/`, `navigation/`, `network/`, `stt/`, `voice/`, `command/`, and `menu/` layers, plus its own `ui/` children. This "God Activity" pattern is the single biggest architectural debt.

**Recommendation:** Refactor into fragments (`MapFragment`, `HistoryFragment`, `SettingsFragment`) and move business logic into `MainViewModel` and dedicated use-cases.

### B. Obstacle subsystem files are in the root package

`ImageQualityAnalyzer.kt`, `ObstacleAlert.kt`, `ObstacleAlertTracker.kt`, `ObstacleRiskAnalyzer.kt`, `CameraSource.kt`, `NetworkSource.kt`, `DetectionOverlayView.kt`, and `YoloModelConfig.kt` are all in the root `com.example.voicenavigation` package instead of `obstacle/`. This causes `VisionViewModel` to import 6+ files from the root package, which looks like a cross-layer violation even though the classes are domain-layer components.

**Recommendation:** Move these files into `com.example.voicenavigation.obstacle/` to clarify their layer membership.

### C. Animation layer references View classes (circular dependency)

Per the architecture doc, "View does not contain animation logic" -- animation logic lives in `animation/`. However, `RingMenuAnimations.kt` and `DetectionOverlayAnimations.kt` must reference the View types they operate on. This creates a dependency cycle: `animation -> ui -> animation`.

**Recommendation:** Define animation property interfaces in the animation layer, have Views implement them, and have animation code operate on the interfaces rather than concrete View types.

### D. `RingMenuItem` data class is in the wrong layer

`RingMenuItem` is a pure data class (`data class RingMenuItem(...)`) located in `ui.ringmenu`, but it is consumed by `MenuConfig` (domain layer). This is the root cause of violation #22.

**Recommendation:** Move `RingMenuItem.kt` to `menu/` or `command/` (domain layer).

### E. `VoiceRecordAdapter` and `SuggestionAdapter` in data/ package

These are `RecyclerView.Adapter` subclasses (UI components) sitting in the `data/` package. This is a misplaced-file issue that amplifies other violations -- `HistoryFragment` importing from `data/` looks like a data-layer bypass, but it is actually a UI-to-UI import with the adapter in the wrong directory.

**Recommendation:** Move both adapters to `ui/main/adapter/` as specified in the architecture doc.

---

## Violation Map by Layer

```
                    +-----------+
                    | Config/   |  (leaf -- no violations)
                    | Util      |
                    +-----+-----+
                          ^
                          |
                    +-----+-----+
                    |  Data     |  Clean (no upward deps)
                    +-----+-----+
                          ^
                          |
                    +-----+-----+
                    |  Domain   |  3 violations: menu->ui, animation->ui (x2)
                    +-----+-----+
                          ^
                          |
                    +-----+-----+
                    |  UI       |  21+ violations: direct domain/data deps
                    +-----------+

DI layer: expected cross-layer -- composition root
Collection: separate feature -- less strict (not counted)
```

---

## Remediation Priority

1. **P0 -- Move `RingMenuItem` to domain layer** (fixes MenuConfig -> UI violation, single file move)
2. **P0 -- Move obstacle files to `obstacle/` package** (clarifies layer membership, fixes apparent violations in VisionViewModel)
3. **P1 -- Move adapters from `data/` to `ui/main/adapter/`** (fixes misplaced UI code)
4. **P1 -- Inject `ToolRegistry`/`ModelRegistry` via Hilt** instead of direct singleton access
5. **P1 -- Remove dead import** in `StopNavigationCommand.kt`
6. **P2 -- MainActivity decomposition** into fragments + ViewModel (addresses 15 violations at once)
7. **P2 -- Extract animation property interfaces** to break animation<->UI cycle
8. **P2 -- Move TTS classes** from `data/tts/` to domain layer
9. **P3 -- Create use-case/repository wrappers** for ViewModel-to-domain dependencies
