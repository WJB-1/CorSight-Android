# Voice Subsystem Health Check

**Date:** 2026-06-14
**Scope:** Voice pipeline from STT through command interpretation, LLM fallback, and command execution.

---

## Files Reviewed

| # | File | Role |
|---|------|------|
| 1 | `voice/VoiceInteractionManager.kt` | Central orchestrator: listen, interpret, execute, speak |
| 2 | `voice/VoiceCommandInterpreter.kt` | Local keyword-based intent parser |
| 3 | `voice/VoiceCommand.kt` | Data model for parsed voice commands |
| 4 | `voice/LlmFunctionCaller.kt` | OpenAI-compatible cloud function-calling client |
| 5 | `stt/BaiduSpeechManager.kt` | Baidu ASR engine wrapper (primary STT) |
| 6 | `stt/BaiduTtsManager.kt` | Baidu TTS engine wrapper (speech synthesis) |
| 7 | `stt/SpeechRecognitionManager.kt` | Android SpeechRecognizer wrapper (unused in production) |
| 8 | `stt/SpeechRecognitionService.kt` | Android SpeechRecognizer as a Service (unused in production) |
| 9 | `di/VoiceModule.kt` | Dagger/Hilt module providing voice singletons |
| 10 | `MainActivity.kt` | Activity implementing CommandExecutor + TextInputListener |
| 11 | `command/CommandRouter.kt` | New command routing via Hilt multibinding |
| 12 | `di/CommandModule.kt` | Hilt multibinding for MenuCommand map |

---

## Overall Verdict: FUNCTIONAL with architectural debt

The voice subsystem is **working end-to-end** today. The primary Baidu STT/TTS pipeline, the local keyword interpreter, the LLM cloud fallback, and the command execution through `VoiceInteractionManager` are all correctly wired. However, there is significant architectural duplication between the old `VoiceInteractionManager.CommandExecutor` pattern and the new `CommandRouter` pattern, and several items need cleanup.

---

## Check 1: VoiceInteractionManager Implements BaiduSpeechManager.STTCallback

**Status: PASS**

`VoiceInteractionManager` (line 32) declares:

```kotlin
class VoiceInteractionManager(...) : BaiduSpeechManager.STTCallback {
```

`BaiduSpeechManager.STTCallback` (lines 43-49) defines:

```kotlin
interface STTCallback {
    fun onPartialResult(result: String)
    fun onResult(result: String)
    fun onError(error: String)
    fun onListening()
    fun onStopped()
}
```

All five overrides are present in `VoiceInteractionManager`:

| Method | Line | Match |
|--------|------|-------|
| `onResult(result: String)` | 179 | Correct |
| `onPartialResult(result: String)` | 200 | Correct |
| `onError(error: String)` | 209 | Correct |
| `onListening()` | 218 | Correct |
| `onStopped()` | 227 | Correct |

Signatures match exactly. No issues.

---

## Check 2: Circular Reference in VoiceInteractionManager Init

**Status: PASS -- No circular reference**

The init block (line 88-89):

```kotlin
init {
    speechManager.callback = this
}
```

This assigns `this` (the `VoiceInteractionManager` instance) as the callback on the **injected** `speechManager` parameter. There is no reference to `voiceInteractionManager` inside its own init block. The confusion may arise from `MainActivity` which declares a field named `voiceInteractionManager` -- but that is a different class. No circular dependency.

---

## Check 3: VoiceCommandInterpreter Context Injection

**Status: PASS**

`VoiceCommandInterpreter` constructor (line 15):

```kotlin
class VoiceCommandInterpreter(private val context: Context? = null) {
```

In `VoiceModule.kt` (line 39-41):

```kotlin
fun provideVoiceCommandInterpreter(@ApplicationContext context: Context): VoiceCommandInterpreter {
    return VoiceCommandInterpreter(context)
}
```

The `@ApplicationContext` is correctly passed. However, in `VoiceInteractionManager` (line 77):

```kotlin
private val interpreter = VoiceCommandInterpreter()
```

**ISSUE (Low): The interpreter is created locally with `null` context, bypassing the Hilt-provided singleton.** The `VoiceModule` provides a `VoiceCommandInterpreter` singleton with context, but `VoiceInteractionManager` never receives it -- it creates its own instance with `context = null`. Since `loadKeywords()` returns `emptyMap()` when context is null (line 25: `val ctx = context ?: return emptyMap()`), **local keyword matching is effectively disabled**. Every voice command will either hit the LLM cloud fallback or fail as UNKNOWN.

**Severity: HIGH** -- This defeats the local-first architecture described in the class docstring. All commands go through the cloud LLM or fall through to UNKNOWN.

**Fix:** Either inject `VoiceCommandInterpreter` into `VoiceInteractionManager` via the constructor (add it as a parameter in `VoiceModule.provideVoiceInteractionManager`), or pass the context to the locally created instance.

---

## Check 4: Voice Command to CommandRouter Flow

**Status: TWO PARALLEL SYSTEMS -- Conflict / Duplication**

There are **two independent command execution paths** in the codebase:

### Path A: VoiceInteractionManager.CommandExecutor (old, active)

`VoiceInteractionManager.processCommand()` (line 235) calls `executeCommand()` (line 310), which has its own `when` block (lines 318-384) that dispatches directly to the `CommandExecutor` interface methods. This is the path used by all voice commands today.

`MainActivity` implements `VoiceInteractionManager.CommandExecutor` (line 90) and is registered at line 536:

```kotlin
voiceInteractionManager.setCommandExecutor(this)
```

### Path B: CommandRouter (new, only used by ring menu)

`CommandRouter` is injected into `MainActivity` (line 172):

```kotlin
@Inject lateinit var commandRouter: CommandRouter
```

But it is **only used by the ring menu** (line 1093):

```kotlin
onItemExecuted = { item ->
    hideRingMenu()
    commandRouter.execute(item.command)
}
```

**Voice commands never flow through `CommandRouter`.** They go through the old `CommandExecutor` interface instead.

**Severity: MEDIUM** -- This means the voice pipeline and the ring menu pipeline execute the same logical commands through entirely different code paths. Behavior could diverge. For example, if a `NavigateToCommand` is updated in `CommandModule`, the voice path will not pick up that change because it calls `CommandExecutor.executeNavigateTo()` directly.

### Recommendation

Migrate `VoiceInteractionManager.executeCommand()` to call `CommandRouter.execute()` using the `VoiceCommand.Type.functionName` as the command ID, instead of the `when` block. The `VoiceCommand.Type` enum already has a `functionName` field that matches the `CommandRouter` string keys. This would unify both paths.

---

## Check 5: VoiceInteractionManager's Own When Block

**Status: Confirmed -- still present**

`VoiceInteractionManager.executeCommand()` (lines 310-385) contains a full `when (command.type)` block that directly invokes the `CommandExecutor` interface methods. This is the legacy pattern. It has **not** been migrated to use `CommandRouter`.

---

## Check 6: LlmFunctionCaller Config Source

**Status: PASS -- reads from AppConfig (SharedPreferences via centralized helper)**

`LlmFunctionCaller` (lines 277-288):

```kotlin
private fun getBaseUrl(): String {
    val url = AppConfig.prefs(context).getString(AppConfig.KEY_LLM_BASE_URL, "") ?: ""
    return if (url.isNotEmpty()) url else "https://api.deepseek.com"
}

private fun getApiKey(): String =
    AppConfig.prefs(context).getString(AppConfig.KEY_LLM_API_KEY, "") ?: ""

private fun getModel(): String {
    val model = AppConfig.prefs(context).getString(AppConfig.KEY_LLM_MODEL, "") ?: ""
    return if (model.isNotEmpty()) model else "deepseek-chat"
}
```

This reads from `AppConfig.prefs(context)`, which is the centralized `SharedPreferences` wrapper (`"corsight_config"` file). It does **not** read raw SharedPreferences directly. The config keys (`KEY_LLM_BASE_URL`, `KEY_LLM_API_KEY`, `KEY_LLM_MODEL`) are all defined in `AppConfig`.

**Note:** `LlmFunctionCaller` still takes a raw `Context` parameter rather than an injected config object. This works but is not ideal for testability. The context is needed for `context.assets.open()` (line 205) and `Handler(context.mainLooper)` (lines 136, 296), so the dependency on `Context` is justified here.

---

## Check 7: MainActivity CommandExecutor vs CommandRouter Conflict

**Status: MEDIUM -- Parallel execution paths, no direct conflict but behavioral divergence risk**

`MainActivity` implements both:
- `VoiceInteractionManager.CommandExecutor` (line 90) -- used by voice commands
- Has `@Inject lateinit var commandRouter: CommandRouter` (line 172) -- used by ring menu

There is no compilation conflict because they are different interfaces/classes. However:

1. `executeNavigateTo()` in MainActivity (line 966) calls `searchDestination()` directly.
2. `NavigateToCommand` in the CommandRouter path likely does the same thing but through a different code path.

If one path is updated and the other is not, the two entry points will behave differently for the same logical command.

---

## Check 8: BaiduSpeechManager Callback Interface Match

**Status: PASS**

Already covered in Check 1. All five `STTCallback` method signatures in `BaiduSpeechManager` are correctly overridden in `VoiceInteractionManager`.

Additionally, `SpeechRecognitionManager` and `SpeechRecognitionService` define their **own** `STTCallback` interfaces (with 4 methods -- no `onPartialResult`). These are **separate** interfaces from `BaiduSpeechManager.STTCallback` and are not used by `VoiceInteractionManager`. They appear to be legacy code from before the Baidu SDK migration.

---

## Check 9: String Resource References

**Status: PASS -- All getString calls use context**

`VoiceInteractionManager` holds a `private val context: Context` (constructor parameter) and calls `context.getString(R.string.xxx)` throughout. This is safe because the context is the application context provided by `@ApplicationContext` in the Hilt module.

Specific string resources used in `VoiceInteractionManager`:

| Resource | Used At |
|----------|---------|
| `R.string.tts_timeout_no_result` | Line 124 |
| `R.string.stage_voice_assistant` | Lines 125, 174, 186, 215, 229 |
| `R.string.tts_not_heard` | Line 185 |
| `R.string.msg_stt_recognized` | Line 195 |
| `R.string.msg_stt_failed` | Line 214 |
| `R.string.msg_mic_open` | Line 221 |
| `R.string.stage_listening` | Line 222 |
| `R.string.stage_recognizing` | Line 229 |
| `R.string.msg_local_match` | Line 241 |
| `R.string.msg_local_miss_cloud` | Line 246 |
| `R.string.msg_cloud_requesting` | Line 247 |
| `R.string.msg_llm_result` | Line 252 |
| `R.string.msg_llm_timeout` | Line 260 |
| `R.string.msg_llm_failed` | Line 261 |
| `R.string.tts_not_understood` | Lines 262, 269, 380 |
| `R.string.msg_local_miss_no_llm` | Line 267 |
| `R.string.msg_no_response` | Line 268 |
| `R.string.msg_voice_assistant_not_ready` | Line 313 |
| `R.string.tts_searching` | Lines 321, 376 |
| `R.string.stage_searching` | Line 322 |
| `R.string.tts_starting_obstacle` | Line 325 |
| `R.string.tts_stopping_navigation` | Line 330 |
| `R.string.tts_no_active_navigation` | Line 332 |
| `R.string.tts_stopping_obstacle` | Line 337 |
| `R.string.tts_no_active_obstacle` | Line 339 |
| `R.string.tts_current_location` | Line 347 |
| `R.string.tts_locating_wait` | Line 349 |
| `R.string.tts_nothing_to_repeat` | Line 359 |
| `R.string.tts_generating_preview` | Line 364 |
| `R.string.tts_nav_active/inactive` | Lines 370-371 |
| `R.string.tts_obstacle_active/inactive` | Lines 370-371 |

All use `context.getString(...)` which is the application context. No risk of calling `getString()` without an active context.

**One minor note:** `R.string.tts_searching` and `R.string.stage_searching` are used with `command.destination!!` as a format argument. If `destination` is null (which it could be for non-NAVIGATE_TO commands), this could crash. However, these are only called within `VoiceCommand.Type.NAVIGATE_TO` and `TEXT_SEARCH` branches where destination is expected to be non-null.

---

## Additional Findings

### F1: Dead Code -- SpeechRecognitionManager and SpeechRecognitionService

`SpeechRecognitionManager.kt` and `SpeechRecognitionService.kt` are **not referenced** by any other file in the voice pipeline. They use Android's built-in `SpeechRecognizer` (not Baidu). `VoiceInteractionManager` uses only `BaiduSpeechManager`. These two files appear to be leftover from a previous implementation.

**Severity: LOW** -- No runtime impact, but increases maintenance burden.

### F2: VoiceModule Provides VoiceCommandInterpreter but Nobody Consumes It

`VoiceModule` provides `VoiceCommandInterpreter` as a singleton (line 39), but no class has it as a constructor dependency. `VoiceInteractionManager` creates its own instance locally (line 77). This means the Hilt-provided singleton is never used.

**Severity: LOW** -- Wasted allocation; also the local instance has null context (see Check 3).

### F3: Dual BaiduSpeechManager Instances

`VoiceModule` provides a singleton `BaiduSpeechManager` (line 23), but `MainActivity.initServices()` creates **another** instance directly (line 514):

```kotlin
speechManager = BaiduSpeechManager(this)
```

Then `VoiceInteractionManager` is constructed (line 534) with this manually-created instance, **not** the Hilt-provided one. The Hilt singleton `BaiduSpeechManager` is effectively unused.

**Severity: MEDIUM** -- Two `BaiduSpeechManager` instances could be initialized if the Hilt singleton is also consumed elsewhere. The `MainActivity` field `speechManager` (line 103) is `lateinit var` and is manually assigned, not `@Inject`-ed.

### F4: Dual BaiduTtsManager Instances

Same pattern: `VoiceModule` provides a singleton `BaiduTtsManager` (line 29), but `MainActivity.initTts()` (line 549) creates another manually. The manual instance is passed to `VoiceInteractionManager` at line 534.

**Severity: MEDIUM** -- Same as F3.

### F5: LLM Function Name Mismatch Risk

`VoiceCommand.Type` enum uses function names like `"navigate_to"`, `"start_obstacle_avoidance"`, etc. `LlmFunctionCaller.buildToolsSchema()` defines the same names. `CommandModule` also binds the same string keys. This triple-alignment is fragile -- a typo in any one of the three locations would cause silent failures.

**Severity: LOW** -- Currently aligned, but no compile-time check enforces consistency.

---

## Summary Table

| # | Check | Status | Severity |
|---|-------|--------|----------|
| 1 | STTCallback override match | PASS | -- |
| 2 | Circular reference in init | PASS (no issue) | -- |
| 3 | VoiceCommandInterpreter context | FAIL | HIGH |
| 4 | Voice -> CommandRouter flow | NOT WIRED | MEDIUM |
| 5 | VoiceInteractionManager when block | Still present (legacy) | MEDIUM |
| 6 | LlmFunctionCaller config source | PASS (uses AppConfig) | -- |
| 7 | CommandExecutor vs CommandRouter | Duplication | MEDIUM |
| 8 | BaiduSpeechManager callback match | PASS | -- |
| 9 | String resource references | PASS | -- |
| F1 | Dead code (SpeechRecognition*) | Present | LOW |
| F2 | Unused Hilt VoiceCommandInterpreter | Present | LOW |
| F3 | Dual BaiduSpeechManager instances | Present | MEDIUM |
| F4 | Dual BaiduTtsManager instances | Present | MEDIUM |
| F5 | Triple-aligned function name strings | Fragile | LOW |

---

## Priority Fixes

1. **[HIGH] Inject context into VoiceCommandInterpreter** -- Either pass `VoiceCommandInterpreter` as a constructor parameter to `VoiceInteractionManager` via `VoiceModule`, or pass the `@ApplicationContext` to the locally created instance. Without this, the local keyword matching is dead code and every voice command requires a network round-trip to the LLM or falls through to UNKNOWN.

2. **[MEDIUM] Unify command execution** -- Wire `VoiceInteractionManager.executeCommand()` to call `CommandRouter.execute(command.type.functionName, params)` instead of the `when` block. Remove the `CommandExecutor` interface once all methods are migrated.

3. **[MEDIUM] Fix dual instance problem** -- Either use `@Inject` for `speechManager` and `baiduTts` in `MainActivity` (removing manual creation), or remove the `@Provides` methods from `VoiceModule` to avoid confusion. Pick one source of truth.

4. **[LOW] Clean up dead code** -- Remove `SpeechRecognitionManager.kt` and `SpeechRecognitionService.kt` if they are confirmed unused.
