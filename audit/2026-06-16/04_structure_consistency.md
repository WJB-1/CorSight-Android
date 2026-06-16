# Structure Consistency Audit

**Date:** 2026-06-16
**Project:** CorSight-Android_v2.0
**Scope:** File organization, naming conventions, import hygiene, configuration, resources, build artifacts

---

## Executive Summary

The project has **3 Gradle modules** (`:app`, `:inference`, `:vision`) containing **103 Kotlin files** and zero Java files. Overall code quality is moderate: the feature packages (`collection`, `command`, `voice`, `stt`) are well organized, and all resource cross-references resolve correctly. However, the root package of the `app` module acts as a dumping ground with 11 misplaced files, there is a critical `com.example` vs `com.corsight` namespace mismatch, and 4 files contain wildcard imports with 6 unused imports found. One manifest-declared Activity has no corresponding source file.

| Severity | Count | Summary |
|----------|-------|---------|
| **Critical** | 1 | Dead manifest entry for `DataCollectionActivity` (no source file) |
| **High** | 2 | `com.example` vs `com.corsight` namespace split; 11 files dumped in root package |
| **Medium** | 5 | 4 wildcard imports, 6 unused imports, TTS split across `data/tts/` and `stt/`, 2 UI adapters in `data/`, missing proguard files in library modules |
| **Low** | 5 | Inconsistent string/color/drawable/style naming, empty `layout-land/` directory |

---

## 1. File Organization

### 1.1 Root Package Dumping Ground

The root package `com.example.voicenavigation` contains 11 files that belong in sub-packages. This is the single largest organizational problem.

| File | Type | Recommended Package |
|------|------|---------------------|
| `MainActivity.kt` | Activity (UI) | `ui/main/` |
| `VisionTestActivity.kt` | Activity (UI) | `ui/` |
| `AppConfig.kt` | SharedPreferences helper | `config/` (alongside `AppConfigProvider`, `AppConstants`) |
| `CameraSource.kt` | CameraX ImageSource impl | `core/camera/` |
| `NetworkSource.kt` | Socket-based ImageSource impl | `core/` |
| `DetectionOverlayView.kt` | Custom View | `ui/vision/` |
| `ImageQualityAnalyzer.kt` | Bitmap sharpness utility | `util/` or `core/camera/` |
| `ObstacleAlert.kt` | Data class + enum | `core/obstacle/` or new `model/` |
| `ObstacleAlertTracker.kt` | Stateful tracker service | `core/obstacle/` |
| `ObstacleRiskAnalyzer.kt` | Risk analysis service | `core/obstacle/` |
| `YoloModelConfig.kt` | ONNX model config constants | `config/` |

**All 11 root-package files should be relocated.**

### 1.2 Unjustified Single-File Packages

Four packages contain only a single file. Three of these are not justified:

| Package | File | Lines | Recommendation |
|---------|------|-------|----------------|
| `app/` | `CorSightApp.kt` | 7 | Merge into root package (just `@HiltAndroidApp class CorSightApp : Application()`) |
| `menu/` | `MenuConfig.kt` | ~50 | Merge into `ui/ringmenu/` (depends on `RingMenuItem` from that package) |
| `network/` | `TripPreviewService.kt` | ~80 | Merge into `data/` or `core/` |
| `navigation/` | `NavigationManager.kt` | ~500 | Borderline; recommend moving to `core/navigation/` for consistency with `core/camera/`, `core/compass/`, `core/location/` |

### 1.3 Cross-Layer Misplacements

**UI adapters in `data/` package (should be in `ui/`):**
- `data/SuggestionAdapter.kt` -- `RecyclerView.Adapter` for POI suggestions. Should be in `ui/main/map/`.
- `data/VoiceRecordAdapter.kt` -- `RecyclerView.Adapter` for voice records. Should be in `ui/main/history/`.

**TTS service code in `data/` package (should be in `stt/` or `tts/`):**
- `data/tts/TtsAudioCache.kt` -- HTTP calls to Baidu TTS API, audio caching.
- `data/tts/TtsPlayer.kt` -- Audio playback management.
- `data/tts/TtsPreloader.kt` -- Preloads TTS audio.

These are service-layer classes doing HTTP and audio playback, not data-access code. Meanwhile `stt/BaiduTtsManager.kt` already exists in the `stt/` package doing similar TTS work. This split is confusing.

### 1.4 Well-Organized Packages (no issues)

These packages are properly structured and require no changes:

| Package | Files | Notes |
|---------|-------|-------|
| `collection/` | 18 | Best-organized feature: clean `data/`, `service/`, `ui/` sub-packages |
| `command/` | 16 | Clear command pattern with Hilt multibinding |
| `voice/` | 4 | Cohesive voice feature module |
| `animation/` | 9 | Self-contained animation system |
| `di/` | 7 | Clean Hilt module organization |
| `config/` | 2 | Good, but should absorb `AppConfig.kt` and `YoloModelConfig.kt` from root |
| `util/` | 5 | Pure utility functions |
| `core/camera/` | 1 | Should absorb `CameraSource.kt` from root |
| `core/compass/` | 2 | Interface + implementation |
| `core/location/` | 2 | Interface + implementation |

---

## 2. Naming Conventions

### 2.1 Kotlin File Naming -- PASS

All 103 Kotlin file names use PascalCase and match their contained type declarations. No violations found.

### 2.2 Package Naming -- ISSUE FOUND

**Critical mismatch:** The `app` module uses `com.example.voicenavigation` while library modules use `com.corsight.*`.

| Module | Namespace / Package |
|--------|---------------------|
| `:app` | `com.example.voicenavigation` |
| `:inference` | `com.corsight.inference` |
| `:vision` | `com.corsight.vision` |

The `com.example` prefix is the Android Studio default placeholder. The `applicationId` in `app/build.gradle` is also `com.example.voicenavigation`, which would be the published package name. The library modules use the intended brand namespace `com.corsight`. This should be unified before release.

### 2.3 String Resource Naming

The project has ~258 string resources with a well-organized prefix system (`msg_`, `tts_`, `stt_`, `vision_`, `collect_`, `nav_`, `menu_`, `preview_dialog_`, `stage_`, `ui_`, `btn_`, `title_`, `status_`).

**18 strings lack any prefix** (lines 2-19 of `strings.xml`):

`speech_hint`, `listening`, `start_navigation`, `stop_navigation`, `destination_hint`, `permission_audio_denied`, `permission_location_denied`, `navigating_to`, `preview_route`, `preview_no_destination`, `preview_no_location`, `preview_requesting`, `preview_success`, `preview_failed`, `baidu_speech_app_id`, `baidu_speech_api_key`, `baidu_speech_secret_key`

The first 18 are clearly older strings predating the prefix convention. The `baidu_speech_*` entries are config values stored as strings (also a security concern -- see Section 6).

**String duplication:** `status_navigating`/`status_not_navigating`/`status_obstacle_on`/`status_obstacle_off` duplicate the meaning of `tts_nav_active`/`tts_nav_inactive`/`tts_obstacle_active`/`tts_obstacle_inactive`.

### 2.4 Color Resource Naming -- INCONSISTENT

Three different naming conventions mixed in `colors.xml`:
- Material shade suffixes: `purple_500`, `purple_700`, `teal_200`
- Plain names: `black`, `white`, `gray`, `orange`
- Domain-prefixed: `vision_green`
- Descriptive: `semi_transparent`, `light_gray`

### 2.5 Drawable Naming -- MOSTLY CONSISTENT

17 of 20 drawables follow the `bg_` (backgrounds) or `ic_` (icons) prefix convention. Three exceptions:

| Current Name | Suggested Name |
|-------------|----------------|
| `edit_text_background.xml` | `bg_edit_text.xml` |
| `circle_purple_light.xml` | `shape_circle_purple_light.xml` |
| `card_background.xml` | `bg_card.xml` |

### 2.6 Layout Naming -- MOSTLY CONSISTENT

All 18 layouts have a type prefix. Two use `page_` instead of `fragment_`:

| Current Name | Component Type | Suggested Name |
|-------------|----------------|----------------|
| `page_history.xml` | HistoryFragment | `fragment_history.xml` |
| `page_settings.xml` | SettingsFragment | `fragment_settings.xml` |

### 2.7 Style/Theme Naming -- INCONSISTENT

Two different root prefixes for themes:
- `Theme.VoiceNavigation` (themes.xml) -- app-level theme
- `AppTheme.NoActionBar`, `AppTheme.FullScreen` (themes.xml) -- activity themes

These should use a single root prefix. Widget styles (`VoiceButton`, `CircleShape`) use raw PascalCase with no prefix.

---

## 3. Import Hygiene

### 3.1 Wildcard Imports (4 files)

| File | Line | Wildcard Import |
|------|------|-----------------|
| `di/CommandModule.kt` | 4 | `import com.example.voicenavigation.command.commands.*` |
| `VisionTestActivity.kt` | 37 | `import kotlinx.coroutines.*` |
| `CameraSource.kt` | 8 | `import androidx.camera.core.*` |
| `collection/GridUtils.kt` | 3 | `import kotlin.math.*` |

All should be expanded to explicit imports.

### 3.2 Unused Imports (6 instances in 4 files)

| File | Line | Unused Import |
|------|------|---------------|
| `MainActivity.kt` | 32 | `androidx.appcompat.widget.SwitchCompat` |
| `VisionTestActivity.kt` | 33 | `com.example.voicenavigation.stt.BaiduTtsManager` |
| `VisionTestActivity.kt` | 52 | `java.net.InetSocketAddress` |
| `ui/ringmenu/RingMenuCoordinator.kt` | 6 | `android.os.Build` |
| `core/location/LocationProvider.kt` | 5 | `android.location.LocationManager` |
| `voice/LlmFunctionCaller.kt` | 8 | `okhttp3.MediaType` (redundant with companion import on line 9) |

### 3.3 Import Cross-Package Issues

No imports from wrong or non-existent packages detected. All cross-module imports (`:app` importing from `:inference` and `:vision`) are valid.

---

## 4. Configuration Consistency

### 4.1 build.gradle: Compile/Target SDK -- CONSISTENT

All three modules use identical values:
- `compileSdk 36`
- `targetSdk 36`
- `minSdk 24`
- `buildToolsVersion "36.1.0"`
- `jvmTarget '17'`
- `sourceCompatibility / targetCompatibility: JavaVersion.VERSION_17`

### 4.2 build.gradle: Dependency Versions -- CONSISTENT

Checked for version conflicts across modules:

| Dependency | app/build.gradle | vision/build.gradle | inference/build.gradle | Status |
|-----------|-----------------|--------------------|-----------------------|--------|
| Kotlin Coroutines 1.7.3 | Yes | Yes | N/A | OK |
| ONNX Runtime 1.17.3 | N/A | N/A | Yes | OK |
| Hilt 2.51 | Yes (both impl + kapt) | N/A | N/A | OK |
| Room 2.6.1 | Yes (runtime + ktx + compiler) | N/A | N/A | OK |

No duplicate dependency declarations found. No version conflicts.

### 4.3 AndroidManifest: Activity/Service Registration -- ISSUE FOUND

**Declared in manifest but NO source file exists:**

```xml
<activity
    android:name=".collection.DataCollectionActivity"
    android:theme="@style/AppTheme.NoActionBar"
    android:exported="false" />
```

There is no `DataCollectionActivity.kt` file anywhere in the project. This is a **dead manifest entry** that will cause a runtime crash (or manifest merge error) if the Activity is referenced. The only collection-related command is `DataCollectionCommand.kt` which may have once launched this Activity.

**Activities with source files -- all correctly registered:**
- `.MainActivity` -- `MainActivity.kt` exists
- `.VisionTestActivity` -- `VisionTestActivity.kt` exists
- `.collection.ui.hub.CaptureHubActivity` -- `CaptureHubActivity.kt` exists

**Application class correctly registered:**
- `.app.CorSightApp` -- `CorSightApp.kt` exists

### 4.4 Missing Proguard/Consumer Rules in Library Modules

Both `inference/build.gradle` and `vision/build.gradle` declare:
```groovy
consumerProguardFiles "consumer-rules.pro"
```

Neither module has a `consumer-rules.pro` file on disk. Similarly, neither has a `proguard-rules.pro` file, which is referenced in the `release` build type:
```groovy
proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
```

This will cause a build error if `minifyEnabled` is set to `true` for release builds, and silently ignores the missing consumer rules for debug builds. Since `minifyEnabled false` is currently set, this is not a build-breaking issue today, but the referenced files should be created.

### 4.5 settings.gradle -- OK

Root project name `VoiceNavigation` includes all 3 modules. Aliyun mirrors are properly configured for Chinese network conditions. `flatDir` correctly points to `app/libs` for the Baidu AAR.

### 4.6 gradle.properties -- OK

- JVM heap: 2048m
- JDK path: hardcoded to `C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot` -- this is environment-specific and works, but other developers will need their own path. The `local.properties` approach would be better for this.
- `android.enableJetifier=true` -- should be reviewed; Jetifier may no longer be needed if all dependencies are AndroidX-native.

---

## 5. Resource Consistency

### 5.1 Layout File Usage -- ALL ACCOUNTED FOR

All 17 layout XML files are referenced in Kotlin code via `R.layout.*`. No unused layout files detected.

Note: The exploration reported 18 layouts, but `dialog_preview_result.xml` was the 18th and is referenced. All 18 are used.

### 5.2 String Resource Usage

The `baidu_speech_app_id` string (line 3) is declared in `strings.xml` but only referenced via the manifest `<meta-data>` tag, not in Kotlin code. This is valid but worth noting -- the `baidu_speech_api_key` and `baidu_speech_secret_key` strings ARE referenced in Kotlin code (likely for runtime SDK initialization).

### 5.3 Hardcoded Strings in Layouts -- ISSUE FOUND

Layout XML files contain **zero `@string/` references**. All text is hardcoded inline in the layout XML. This means:
- The app cannot be localized/translated without modifying layout files
- The ~258 string resources in `strings.xml` are only used from Kotlin code, not from layouts

### 5.4 Drawable Usage

All drawable XML files referenced in Kotlin code (`bg_direction_cell_*`, `bg_dot`) and layout XMLs (`edit_text_background`, `circle_purple_light`, `bg_shutter_*`, `card_background`, `bg_voice_*`, `ic_microphone`) exist. No broken references.

### 5.5 Empty/Stub Directories

| Directory | Status | Recommendation |
|-----------|--------|----------------|
| `res/layout-land/` | Empty | Remove if no landscape layouts are planned |
| `app/src/main/assets/` | NOT empty -- contains `app_constants.json`, `asr_param.json`, `llm_system_prompt.txt`, `menu_config.json`, `voice_keywords.json` | No action needed |

### 5.6 Unused Drawable Resources

The following drawables exist in `res/drawable/` but are NOT referenced in any layout XML or Kotlin code:
- `bg_voice_button_active.xml` -- referenced in layouts? Let me note: this was listed as referenced in layout `activity_main.xml`. No unused drawables found after thorough cross-reference.

Actually, cross-referencing all drawables:
- `ic_history.xml` -- referenced in `menu_main_bottom.xml`
- `ic_launcher_background.xml` -- referenced in `mipmap-anydpi-v26/ic_launcher.xml`
- `ic_launcher_foreground.xml` -- referenced in `mipmap-anydpi-v26/ic_launcher.xml`
- `ic_nav.xml` -- referenced in `menu_main_bottom.xml`
- `ic_settings.xml` -- referenced in `menu_main_bottom.xml`
- `ic_vision.xml` -- referenced in `menu_main_bottom.xml`

All drawables are accounted for.

---

## 6. Security Concerns (Bonus Finding)

### 6.1 API Keys in Version Control

**Baidu Speech SDK credentials are stored in plaintext** in `strings.xml` (lines 3-5):

```xml
<string name="baidu_speech_app_id">7669507</string>
<string name="baidu_speech_api_key">eT9Q9hXnZt0nFYdAmAsC6d69</string>
<string name="baidu_speech_secret_key">jKemN6xBxVfMlscTeyLsovXG9PhWblSS</string>
```

The AMap API key is properly handled via `local.properties` + manifest placeholders, but the Baidu keys are not. These should be moved to `local.properties` with the same `manifestPlaceholders` or `BuildConfig` approach used for the AMap key.

---

## 7. Git/Build Artifacts

### 7.1 .gitignore -- ADEQUATE

The `.gitignore` covers:
- `build/`, `app/build/`, `inference/build/`, `vision/build/` -- build outputs
- `.gradle/` -- Gradle cache
- `local.properties` -- local config
- `.idea/`, `*.iml` -- IDE files
- `debug/` -- debug artifacts
- `*.apk`, `*.aab`, `*.dex` -- binary outputs
- `*.o`, `*.so`, `*.h` -- native artifacts

### 7.2 Committed Artifacts -- NONE FOUND

Build directories exist on disk (expected for a working project) but are not tracked by git. `local.properties` exists locally but is properly gitignored. No `.class` files or generated sources were found committed.

### 7.3 Inconsistency: `debug/` in .gitignore but `debug/result.png` exists

The `.gitignore` contains `debug/`, which should exclude the `debug/` directory from git. The `debug/result.png` file (15KB screenshot) exists on disk. If this was committed before the gitignore rule was added, it may still be tracked.

---

## 8. Test Coverage

**Zero test files** exist across all three modules. The `app/build.gradle` declares test dependencies (`junit:4.13.2`, `espresso-core:3.5.1`, `androidx.test.ext:junit:1.1.5`) but no test classes have been written. The `testInstrumentationRunner` is configured in all three modules.

---

## 9. Module Architecture Notes

### 9.1 Library Module Namespaces

The library modules use the correct `com.corsight.*` namespace convention:
- `:inference` -> `com.corsight.inference` (3 files: `InferenceEngine`, `ModelRegistry`, `YoloV8OnnxEngine`)
- `:vision` -> `com.corsight.vision` (5 files: `Frame`, `ImageSource`, `ToolRegistry`, `VisionTool`, `GenericDetectionTool`)

These are clean, well-scoped modules.

### 9.2 Dependency Direction

```
:app  -->  :vision  -->  :inference
```

This is a correct unidirectional dependency chain. No circular dependencies detected.

---

## Prioritized Recommendations

### Critical (fix immediately)

| # | Issue | Action |
|---|-------|--------|
| 1 | `DataCollectionActivity` declared in manifest but no source file | Remove the `<activity>` entry from `AndroidManifest.xml` or create the missing source file |

### High (fix soon)

| # | Issue | Action |
|---|-------|--------|
| 2 | `com.example` namespace in app module | Plan migration to `com.corsight.voicenavigation` (or similar) including `applicationId`, all package declarations, and imports |
| 3 | 11 files dumped in root package | Relocate each file to its proper sub-package (see table in Section 1.1) |
| 4 | Baidu API keys in `strings.xml` | Move to `local.properties` with `BuildConfig` or `manifestPlaceholders` |

### Medium (improve)

| # | Issue | Action |
|---|-------|--------|
| 5 | 4 wildcard imports | Expand to explicit imports |
| 6 | 6 unused imports | Remove dead imports |
| 7 | 2 UI adapters in `data/` package | Move `SuggestionAdapter` to `ui/main/map/`, `VoiceRecordAdapter` to `ui/main/history/` |
| 8 | TTS code split between `data/tts/` and `stt/` | Consolidate all speech/audio code into `stt/` or a unified `tts/` package |
| 9 | Missing `consumer-rules.pro` and `proguard-rules.pro` in library modules | Create empty placeholder files |
| 10 | 3 single-file packages (`app/`, `menu/`, `network/`) | Merge into adjacent packages |

### Low (polish)

| # | Issue | Action |
|---|-------|--------|
| 11 | 18 string names lack prefix convention | Add `msg_`, `ui_`, `btn_` prefixes |
| 12 | String duplication (`status_*` vs `tts_*`) | Remove one set, use shared references |
| 13 | Color naming inconsistency | Adopt a single convention (e.g., `color_*` prefix) |
| 14 | Style naming (`Theme.*` vs `AppTheme.*`) | Unify to one root prefix |
| 15 | 3 drawable names lack `bg_`/`ic_` prefix | Rename for consistency |
| 16 | `page_history.xml`/`page_settings.xml` | Rename to `fragment_history.xml`/`fragment_settings.xml` |
| 17 | Empty `layout-land/` directory | Remove if unused |
| 18 | Hardcoded strings in layout XMLs | Externalize to `strings.xml` |
| 19 | `debug/result.png` possibly committed | Verify and remove from git if tracked |
| 20 | `android.enableJetifier=true` | Evaluate if still needed |

---

*Audit completed 2026-06-16. Total issues found: 20 (1 critical, 3 high, 6 medium, 10 low).*
