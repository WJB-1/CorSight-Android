# Health Check: Config System, Data Layer, Navigation Module

**Date:** 2026-06-14
**Scope:** config/, data/, navigation/, network/, util/, DI modules, related UI ViewModels/Fragments

---

## 1. AppConfig vs AppConstants -- Duplicate Constant Definitions

### Files Examined
- `app/src/main/java/com/example/voicenavigation/AppConfig.kt` (root-level singleton object)
- `app/src/main/java/com/example/voicenavigation/config/AppConstants.kt` (config package singleton object)

### Finding: DUPLICATE SharedPreferences Key Definitions

Both files define SharedPreferences key constants that overlap:

| Purpose | AppConfig.kt | AppConstants.kt |
|---------|-------------|-----------------|
| Preview server URL | `KEY_PREVIEW_SERVER_BASE_URL = "server_base_url"` | `SP_KEY_PREVIEW_SERVER = "server_base_url"` |
| Detection server URL | `KEY_DETECTION_SERVER_BASE_URL = "detection_server_base_url"` | `SP_KEY_DETECTION_SERVER = "detection_server_base_url"` |
| LLM enabled | `KEY_LLM_ENABLED = "llm_enabled"` | `SP_KEY_LLM_ENABLED = "llm_enabled"` |
| LLM base URL | `KEY_LLM_BASE_URL = "llm_base_url"` | `SP_KEY_LLM_BASE_URL = "llm_base_url"` |
| LLM API key | `KEY_LLM_API_KEY = "llm_api_key"` | `SP_KEY_LLM_API_KEY = "llm_api_key"` |
| LLM model | `KEY_LLM_MODEL = "llm_model"` | `SP_KEY_LLM_MODEL = "llm_model"` |
| SP name | `PREFS_NAME = "corsight_config"` | `SP_NAME = "corsight_config"` |

**Impact:** The actual string values are identical, so there is no runtime bug. However, the codebase is split on which constants to reference. `SettingsViewModel` uses `AppConfig.KEY_*` for most keys but `AppConstants.SP_KEY_USE_EXTERNAL_DEVICE` for the external device toggle. This split is confusing and error-prone.

### Finding: Hardcoded Earth Radius in Multiple Locations

`AppConstants.EARTH_RADIUS_M = 6371000.0` is defined but never used. The same literal `6371000.0` is hardcoded in:
- `NavigationManager.kt` line 348
- `RetakeFragment.kt` line 241
- `DashboardViewModel.kt` line 168

### Recommendation
- Consolidate all SharedPreferences keys and the SP name into a single location (`AppConstants` or `AppConfig`, not both).
- Delete the duplicate constants from whichever file is removed.
- Replace all hardcoded `6371000.0` with `AppConstants.EARTH_RADIUS_M`.

### Severity: LOW (no runtime bug, maintainability concern)

---

## 2. AppConfigProvider -- JSON Loading Correctness

### File: `app/src/main/java/com/example/voicenavigation/config/AppConfigProvider.kt`

### Finding: Loading Logic is Correct

`AppConfigProvider` reads `assets/app_constants.json` via `context.assets.open(CONFIG_FILE)`, parses it as `JSONObject`, and caches it in memory. Each section property uses `section("voice").optLong(...)` with sensible defaults that match the JSON file values.

### Finding: JSON File and Kotlin Defaults are Aligned

I verified every default value in `AppConfigProvider` against `app_constants.json`. All defaults match (e.g., `auto_stop_timeout_ms` = 8000 in both, `arrival_distance_m` = 20 in both, etc.).

### Finding: Missing Property for `obstacle.blur_skip`

`app_constants.json` defines `"obstacle.blur_skip": true` but `AppConfigProvider` has no corresponding property. This value is currently unreadable through the provider.

### Finding: JSON File Defines Extra Network Keys Not in AppConfigProvider

`app_constants.json` network section includes `udp_discovery_port` (8888) and `default_stream_port` (8080) but `AppConfigProvider` does not expose these. They exist as compile-time constants in `AppConstants` instead (`UDP_DISCOVERY_PORT`, `DEFAULT_STREAM_PORT`), which means they cannot be hot-updated as intended.

### Severity: LOW (missing properties, minor inconsistency)

---

## 3. VoiceRecordRepository -- Hilt Injection

### File: `app/src/main/java/com/example/voicenavigation/data/VoiceRecordRepository.kt`

### Finding: @Inject Constructor -- CORRECT

`VoiceRecordRepository` uses `@Inject constructor(private val dao: VoiceRecordDao)` with `@Singleton`. Since `VoiceRecordDao` is provided by `AppModule.provideVoiceRecordDao()`, the Hilt graph resolves this correctly. No explicit Hilt module binding is needed for the repository itself.

### Severity: NONE (correct)

---

## 4. NavigationManager -- Hardcoded Companion Constants

### File: `app/src/main/java/com/example/voicenavigation/navigation/NavigationManager.kt`

### Finding: HARDCODED Constants NOT Using AppConfigProvider

The companion object defines:
```kotlin
private const val UPDATE_INTERVAL = 3000L    // matches json: 3000
private const val ARRIVAL_DISTANCE = 20f     // matches json: 20
private const val OFF_ROUTE_THRESHOLD = 50f  // matches json: 50
```

These are used on lines 92, 148-149, 158, and 195 respectively. Meanwhile, `AppConfigProvider` exposes `navUpdateIntervalMs`, `navArrivalDistanceM`, and `navOffRouteThresholdM` with the exact same default values from the JSON file.

**The JSON values are completely ignored.** Changing `app_constants.json` at runtime (e.g., via asset hot-reload or a future remote config push) would have zero effect on `NavigationManager`.

Additionally, the search window constants on line 148-149 are also hardcoded:
```kotlin
val startSearch = Math.max(0, currentPolylineIndex - 5)   // matches json: route_search_backward = 5
val endSearch = Math.min(routePoints!!.size, currentPolylineIndex + 50)  // matches json: route_search_forward = 50
```

`AppConfigProvider` exposes `navRouteSearchBackward` and `navRouteSearchForward` for these, but they are not used.

### Recommendation
`NavigationManager` should accept `AppConfigProvider` via its constructor (or be refactored to use Hilt `@Inject`) and replace all companion constants with the provider's values.

### Severity: MEDIUM (architectural inconsistency; the whole point of the JSON config layer is defeated in this class)

---

## 5. TripPreviewService -- DEFAULT_BASE_URL and BuildConfig

### File: `app/src/main/java/com/example/voicenavigation/network/TripPreviewService.kt`

### Finding: Correctly References BuildConfig

```kotlin
val DEFAULT_BASE_URL: String = com.example.voicenavigation.BuildConfig.PREVIEW_BASE_URL
```

The `PREVIEW_BASE_URL` field is generated by `app/build.gradle` line 35:
```groovy
buildConfigField "String", "PREVIEW_BASE_URL", "\"${localProps.getProperty('preview.base.url', 'http://114.132.86.138:5000')}\""
```

This reads from `local.properties` with a hardcoded fallback, which is correct.

### Finding: Duplicate Default URL in AppConstants

`AppConstants.PREVIEW_DEFAULT_BASE_URL = "http://114.132.86.138:5000"` duplicates the same fallback value. `AppModule.provideBaseUrl()` uses `AppConstants.PREVIEW_DEFAULT_BASE_URL` as the fallback, while `TripPreviewService.DEFAULT_BASE_URL` uses `BuildConfig.PREVIEW_BASE_URL`. If `local.properties` is absent, both resolve to the same value, but this is fragile.

### Finding: OkHttpClient Not Shared

`TripPreviewService` creates its own `OkHttpClient` internally (line 30-33), ignoring the `@Singleton` `OkHttpClient` provided by `AppModule.provideOkHttpClient()`. The two clients have different timeouts (15s/15s in TripPreviewService vs. 15s/20s in AppModule). This wastes resources and creates inconsistent behavior.

### Severity: LOW (duplicate default URL, unshared OkHttpClient)

---

## 6. FormatUtils -- AppConfigProvider Parameter Chain

### File: `app/src/main/java/com/example/voicenavigation/util/FormatUtils.kt`

### Finding: Requires AppConfigProvider as Explicit Parameter

`FormatUtils.formatDistance()` and `formatDuration()` both take `config: AppConfigProvider` as a parameter. This is a stateless utility object, so the caller must pass the provider instance.

### Finding: AppConfigProvider is NOT Provided by Hilt

`AppConfigProvider` has `@Inject constructor(context: Context)` and `@Singleton`, which means Hilt can construct it. However, it is **not explicitly provided** in any Hilt module (`AppModule`, `CoreModule`, etc.). Hilt's `@Inject` annotation on the constructor is sufficient for it to be injectable, so this works automatically.

The problem is the **call chain**: `FormatUtils` is a static `object`, so any caller must manually obtain or receive an `AppConfigProvider` instance. In `MainActivity`, it is created via `private val appConfigProvider by lazy { AppConfigProvider(this) }` (line 174), bypassing Hilt entirely. This creates a parallel instance alongside any Hilt-managed one.

### Recommendation
Either inject `AppConfigProvider` via Hilt everywhere, or make `FormatUtils` accept individual float/int parameters instead of the entire provider, to avoid coupling a utility to the config system.

### Severity: LOW (works, but inconsistent DI strategy)

---

## 7. SettingsViewModel -- Hilt and SharedPreferences Injection

### File: `app/src/main/java/com/example/voicenavigation/ui/main/settings/SettingsViewModel.kt`

### Finding: @HiltViewModel -- CORRECT

Uses `@HiltViewModel` with `@Inject constructor(@ApplicationContext context: Context, prefs: SharedPreferences)`. `SharedPreferences` is provided by `AppModule.provideSharedPreferences()`. This is correct.

### Finding: SettingsFragment is @AndroidEntryPoint -- CORRECT

`SettingsFragment` uses `@AndroidEntryPoint` and `by viewModels()`, which is the correct pattern for Hilt-injected ViewModels.

### Severity: NONE (correct)

---

## 8. HistoryFragment -- @Inject BaiduTtsManager

### File: `app/src/main/java/com/example/voicenavigation/ui/main/history/HistoryFragment.kt`

### Finding: @AndroidEntryPoint is Present -- CORRECT

`HistoryFragment` has `@AndroidEntryPoint` (line 22), which is required for `@Inject` field injection. `BaiduTtsManager` is provided by `VoiceModule.provideBaiduTtsManager()`, so `@Inject lateinit var baiduTts: BaiduTtsManager` resolves correctly.

### Severity: NONE (correct)

---

## 9. Circular Dependencies in Hilt Graph

### Analysis of All DI Modules

Dependency graph:
- `AppModule` provides: `SharedPreferences`, `String(@BaseUrl)`, `AppDatabase`, `VoiceRecordDao`, `OkHttpClient`, `TripPreviewService`
- `CoreModule` binds: `LocationProvider`, `CompassProvider`
- `NavigationModule` provides: `NavigationManager` (depends on `Context`)
- `VoiceModule` provides: `BaiduSpeechManager`, `BaiduTtsManager`, `VoiceCommandInterpreter`, `LlmFunctionCaller`, `VoiceInteractionManager`
- `InferenceModule` provides: `ModelRegistry`, `ObstacleAlertTracker`
- `CommandModule` binds 13 `MenuCommand` implementations into a multibinding map

### Finding: NO Circular Dependencies Detected

The dependency graph is a DAG. `VoiceInteractionManager` depends on `BaiduSpeechManager`, `BaiduTtsManager`, and `LlmFunctionCaller`, all provided within the same module. No back-edges exist.

### Finding: Unused @BaseUrl Qualifier

`AppModule` provides a `String` annotated with `@BaseUrl`, but no class in the codebase injects `@BaseUrl String`. The `TripPreviewService` uses its own `DEFAULT_BASE_URL` field instead. This qualifier binding is dead code.

### Severity: LOW (dead code in DI graph)

---

## 10. Database -- Schema Version and Migration

### File: `app/src/main/java/com/example/voicenavigation/data/AppDatabase.kt`

### Finding: Version 2 with `fallbackToDestructiveMigration()`

```kotlin
@Database(entities = [VoiceRecord::class], version = 2, exportSchema = false)
```

Both `AppModule` (line 43) and `MainActivity` (line 519) use `.fallbackToDestructiveMigration()`.

### Finding: DUAL Database Instances

`AppModule.provideAppDatabase()` creates a Room singleton via Hilt. `MainActivity.onCreate()` creates a **second**, independent `AppDatabase` instance via `Room.databaseBuilder()` directly. Both use the same file name `"voice_navigation.db"` and both have `fallbackToDestructiveMigration()`.

Two `RoomDatabase` instances on the same database file from the same process is dangerous. Room maintains an internal cache and journal state; dual instances can cause:
- WAL checkpoint conflicts
- Stale cache reads
- Potential ANR or data corruption under concurrent writes

### Finding: No Migration Object

Version is `2` but there is no `Migration(1, 2)` object defined. Combined with `fallbackToDestructiveMigration()`, upgrading from version 1 to version 2 will **destroy all existing data**. If this was intentional (e.g., adding the `destination` column), it should be documented. If not, a proper `Migration` should be written.

### Finding: `exportSchema = false`

Schema export is disabled, which means there is no schema history to validate migrations against. This is acceptable for a small project but limits future migration safety.

### Severity: HIGH (dual database instances risk data corruption; destructive migration loses user data)

---

## Summary Table

| # | Check | Severity | Status |
|---|-------|----------|--------|
| 1 | AppConfig vs AppConstants duplicates | LOW | DUPLICATE CONSTANTS found |
| 2 | AppConfigProvider JSON loading | LOW | Correct, minor missing properties |
| 3 | VoiceRecordRepository @Inject | NONE | Correct |
| 4 | NavigationManager hardcoded constants | MEDIUM | NOT using AppConfigProvider |
| 5 | TripPreviewService DEFAULT_BASE_URL | LOW | Correct, but duplicate fallback + unshared OkHttpClient |
| 6 | FormatUtils AppConfigProvider chain | LOW | Works but bypasses Hilt in MainActivity |
| 7 | SettingsViewModel @HiltViewModel | NONE | Correct |
| 8 | HistoryFragment @Inject BaiduTtsManager | NONE | Correct (@AndroidEntryPoint present) |
| 9 | Circular dependencies | NONE | No cycles found |
| 10 | Database schema version | HIGH | Dual instances + destructive migration |

---

## Recommended Actions (Priority Order)

1. **[HIGH] Remove dual database instantiation.** Delete the `Room.databaseBuilder` call in `MainActivity` and inject `AppDatabase` or `VoiceRecordDao` via Hilt instead. The `NavigationManager` also needs to stop holding its own database reference.

2. **[HIGH] Add proper Migration(1, 2) or document the destructive migration.** If the `destination` column was added in version 2, write a migration: `ALTER TABLE voice_records ADD COLUMN destination TEXT`. This preserves existing user voice records.

3. **[MEDIUM] Make NavigationManager use AppConfigProvider.** Either inject it via the constructor (update `NavigationModule`) or pass the three threshold values as parameters. Replace the companion object constants with the provider's values.

4. **[LOW] Consolidate AppConfig and AppConstants.** Pick one location for all SharedPreferences key constants. The `config/AppConstants.kt` file is the better home since it already holds other compile-time constants. Delete the duplicates from `AppConfig.kt`.

5. **[LOW] Share OkHttpClient.** Inject the `AppModule`-provided `OkHttpClient` into `TripPreviewService` instead of creating a new one internally.

6. **[LOW] Remove dead `@BaseUrl` qualifier binding** from `AppModule`, or wire `TripPreviewService` to use it.

7. **[LOW] Add missing AppConfigProvider property** for `obstacle.blur_skip` and move `udp_discovery_port`/`default_stream_port` from compile-time constants to the JSON file if hot-update is desired.
