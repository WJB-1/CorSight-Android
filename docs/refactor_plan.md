# CorSight Android 架构优化方案

> 分支: `refactor/architecture-v2`
> 基于: `coupling_report.md` 调研结果

---

## 一、优化目标

| 目标 | 说明 |
|------|------|
| **MainActivity 瘦身** | 1476 行 → ~300 行，只保留 UI 绑定和生命周期管理 |
| **硬编码清零** | 338 处硬编码全部外部化为配置文件/资源文件 |
| **语音命令路由独立** | 语音命令、环形菜单、手势检测三者共享同一套路由系统，新增功能只需加配置 |
| **工具脚本零数据** | 所有工具类/管理器中不出现具体字符串、数值、URL |

---

## 二、优化后的项目架构

```
com.example.voicenavigation/
│
├── app/
│   └── CorSightApp.kt                       # @HiltAndroidApp
│
├── config/                                    # ===== 全局配置 =====
│   ├── AppConfig.kt                           # SharedPreferences key 常量
│   ├── AppConstants.kt                        # 所有超时/阈值/数值常量
│   └── AppConfigProvider.kt                   # 统一配置读取入口（URL、模型名等）
│
├── di/                                        # ===== 依赖注入 =====
│   ├── AppModule.kt
│   ├── VoiceModule.kt
│   ├── NavigationModule.kt
│   └── InferenceModule.kt
│
├── data/                                      # ===== 数据层 =====
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── dao/VoiceRecordDao.kt
│   │   └── entity/VoiceRecord.kt
│   ├── remote/
│   │   ├── TripPreviewApi.kt                  # Retrofit 接口
│   │   └── LlmApi.kt                          # Retrofit 接口
│   └── repository/
│       ├── VoiceRecordRepository.kt
│       └── TripPreviewRepository.kt
│
├── voice/                                     # ===== 语音子系统 =====
│   ├── stt/
│   │   ├── BaiduSpeechManager.kt
│   │   └── BaiduTtsManager.kt
│   ├── VoiceInteractionManager.kt
│   ├── VoiceCommandInterpreter.kt
│   ├── VoiceCommand.kt
│   └── LlmFunctionCaller.kt
│
├── command/                                   # ===== 命令路由系统（新增） =====
│   ├── CommandRouter.kt                       # 统一路由：语音命令 / 环形菜单 / 手势 → 执行
│   ├── CommandHandler.kt                      # 命令处理接口（替代 CommandExecutor）
│   ├── AppCommandHandler.kt                   # 命令处理实现（从 MainActivity 抽出）
│   └── CommandRegistry.kt                     # 命令注册表：id → handler 映射
│
├── menu/                                      # ===== 菜单系统（新增） =====
│   ├── MenuConfig.kt                          # 菜单数据配置（从 assets 加载）
│   └── MenuRouter.kt                          # 菜单动作 → CommandRouter 委托
│
├── navigation/
│   └── NavigationManager.kt
│
├── obstacle/                                  # 障碍物检测
│   ├── CameraSource.kt
│   ├── NetworkSource.kt
│   ├── ObstacleRiskAnalyzer.kt
│   ├── ObstacleAlertTracker.kt
│   ├── ObstacleAlert.kt
│   ├── ImageQualityAnalyzer.kt
│   ├── DetectionOverlayView.kt
│   └── YoloModelConfig.kt
│
├── util/                                      # ===== 工具类（新增） =====
│   ├── FormatUtils.kt                         # formatDistance, formatDuration
│   ├── TextUtils.kt                           # cleanSpeechText
│   └── SecurityUtils.kt                       # getAppSignatureSha1
│
├── ui/                                        # ===== UI 层 =====
│   ├── main/
│   │   ├── MainActivity.kt                    # ~300 行：只有 UI 绑定 + 生命周期
│   │   ├── MainViewModel.kt                   # 持有所有业务状态
│   │   ├── map/
│   │   │   └── MapFragment.kt                 # 地图 + 搜索 + 导航控制
│   │   ├── history/
│   │   │   └── HistoryFragment.kt
│   │   └── settings/
│   │       ├── SettingsFragment.kt
│   │       └── SettingsViewModel.kt
│   ├── vision/
│   │   ├── VisionTestActivity.kt
│   │   └── VisionViewModel.kt
│   ├── collection/
│   │   ├── DataCollectionActivity.kt
│   │   └── CollectionViewModel.kt
│   ├── voice/
│   │   └── GestureVoiceLauncher.kt
│   ├── ringmenu/
│   │   ├── RingMenuView.kt
│   │   └── RingMenuItem.kt
│   └── dialog/
│       └── TripPreviewDialog.kt               # 从 MainActivity.showPreviewDialog() 抽出
│
├── collection/                                # 数据采集
│   ├── CaptureTask.kt
│   ├── CompassService.kt
│   ├── GridUtils.kt
│   ├── TaskStorage.kt
│   └── UploadService.kt
│
└── network/
    └── TripPreviewService.kt

src/main/assets/                               # ===== 外部配置文件 =====
├── menu_config.json                           # 环形菜单项配置
├── voice_keywords.json                        # 语音关键词库（113 个）
├── llm_system_prompt.txt                      # LLM system prompt
└── app_constants.json                         # 超时/阈值/数值配置
```

---

## 三、核心改动说明

### 改动 1：硬编码全部外部化

#### 1.1 中文字符串 → `strings.xml`

**涉及 ~300 处**，全部从 Kotlin 代码迁移到 `res/values/strings.xml`。

示例：

```kotlin
// 改前（VoiceInteractionManager.kt:329）
speakFeedback("正在停止导航")

// 改后
speakFeedback(context.getString(R.string.voice_stopping_navigation))
```

```xml
<!-- res/values/strings.xml -->
<string name="voice_stopping_navigation">正在停止导航</string>
```

**涉及文件**：MainActivity.kt, VoiceInteractionManager.kt, BaiduSpeechManager.kt, VisionTestActivity.kt, DataCollectionActivity.kt, TripPreviewService.kt, NavigationManager.kt

#### 1.2 超时/阈值/数值 → `assets/app_constants.json` + `AppConstants.kt`

**涉及 ~89 处**，分为两层：
- `assets/app_constants.json` — 可热更新的配置（超时时长、阈值比例等）
- `AppConstants.kt` — 编译时常量（接口 key、广播 Action 等）

```json
// assets/app_constants.json
{
  "voice": {
    "auto_stop_timeout_ms": 8000,
    "watchdog_timeout_ms": 8000,
    "toast_duration_ms": 1200,
    "button_restore_delay_ms": 5000,
    "min_search_text_length": 2
  },
  "gesture": {
    "long_press_duration_ms": 500,
    "move_cancel_threshold_px": 50,
    "vibrate_duration_ms": 100
  },
  "navigation": {
    "update_interval_ms": 3000,
    "arrival_distance_m": 20,
    "off_route_threshold_m": 50,
    "map_zoom_default": 15,
    "map_zoom_detail": 16,
    "poi_page_size": 10
  },
  "obstacle": {
    "local_frame_interval_ms": 120,
    "model_input_size": 640,
    "iou_threshold": 0.35,
    "risk_area_ratio": 0.30,
    "risk_width_ratio": 0.60,
    "urgency_high_threshold": 0.70,
    "urgency_medium_threshold": 0.50,
    "urgency_low_threshold": 0.30,
    "smooth_history_frames": 4,
    "max_history_frames": 5,
    "jpeg_quality": 80
  },
  "tts": {
    "language": "zh",
    "person": 0,
    "speed": 5,
    "pitch": 5,
    "volume": 15
  },
  "network": {
    "connect_timeout_s": 15,
    "read_timeout_s": 15,
    "llm_connect_timeout_s": 10,
    "llm_read_timeout_s": 20,
    "llm_temperature": 0.1,
    "llm_max_tokens": 256
  }
}
```

```kotlin
// config/AppConstants.kt
object AppConstants {
    // 编译时常量（不可热更新）
    const val BROADCAST_ACTION_STOP_OBSTACLE = "com.example.voicenavigation.ACTION_STOP_OBSTACLE"
    const val DB_NAME = "voice_navigation.db"
    const val DEFAULT_ROUTE_ID = "gzdx_stadium"
    const val SP_NAME = "corsight_config"
    const val LLM_DEFAULT_MODEL = "deepseek-chat"
    const val LLM_DEFAULT_BASE_URL = "https://api.deepseek.com"
    const val PREVIEW_DEFAULT_BASE_URL = "http://114.132.86.138:5000"
    const val UDP_DISCOVERY_PORT = 8888
    const val DEFAULT_STREAM_PORT = 8080
}
```

#### 1.3 URL/IP → `local.properties` + `BuildConfig`

```properties
# local.properties（已有 amap.api.key，追加）
preview.base.url=http://114.132.86.138:5000
detection.base.url=
llm.base.url=https://api.deepseek.com
```

```groovy
// app/build.gradle
buildConfigField "String", "PREVIEW_BASE_URL", "\"${localProps.getProperty('preview.base.url', '')}\""
buildConfigField "String", "DETECTION_BASE_URL", "\"${localProps.getProperty('detection.base.url', '')}\""
```

#### 1.4 语音关键词 → `assets/voice_keywords.json`

**涉及 113 个关键词**，从 `VoiceCommandInterpreter.kt` 源码迁出。

```json
// assets/voice_keywords.json
{
  "stop_navigation": ["停止导航", "结束导航", "关闭导航", "取消导航", ...],
  "start_obstacle": ["开始避障", "打开避障", "启动避障", ...],
  "stop_obstacle": ["停止避障", "结束避障", "关闭避障", ...],
  "where_am_i": ["我在哪里", "我在哪儿", "当前位置", ...],
  "repeat": ["重复", "再说一遍", "没听清", ...],
  "preview_route": ["预览路线", "路线预览", ...],
  "query_status": ["查询状态", "现在什么情况", ...],
  "navigate_prefixes": ["导航到", "导航去", "带我去", ...],
  "navigate_suffixes": ["怎么走", "怎么去", ...]
}
```

`VoiceCommandInterpreter` 改为从 assets 加载关键词：

```kotlin
class VoiceCommandInterpreter(private val context: Context) {
    private val keywords: Map<String, List<String>> = loadKeywords()

    private fun loadKeywords(): Map<String, List<String>> {
        val json = context.assets.open("voice_keywords.json").bufferedReader().readText()
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { key ->
            obj.getJSONArray(key).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        }
    }
}
```

#### 1.5 LLM System Prompt → `assets/llm_system_prompt.txt`

22 行 prompt 文本从 `LlmFunctionCaller.kt` 源码迁出：

```kotlin
// 改前
private fun buildSystemPrompt(): String {
    return "你是一个盲人导航避障应用的语音助手。..." // 22 行
}

// 改后
private fun buildSystemPrompt(): String {
    return context.assets.open("llm_system_prompt.txt").bufferedReader().readText()
}
```

#### 1.6 菜单配置 → `assets/menu_config.json`

菜单项从 `MainActivity.setupRingMenu()` 硬编码迁出：

```json
// assets/menu_config.json
{
  "items": [
    {
      "id": "voice",
      "label": "语音助手",
      "color": "#4CAF50",
      "command": "voice_assistant"
    },
    {
      "id": "obstacle",
      "label": "避障",
      "color": "#F44336",
      "command": "start_obstacle_avoidance"
    },
    {
      "id": "preview",
      "label": "预览路线",
      "color": "#2196F3",
      "command": "preview_route"
    },
    {
      "id": "stop_nav",
      "label": "停止导航",
      "color": "#FF9800",
      "command": "stop_navigation"
    },
    {
      "id": "more",
      "label": "更多",
      "color": "#9E9E9E",
      "children": [
        {"id": "history", "label": "历史", "color": "#795548", "command": "show_history"},
        {"id": "settings", "label": "设置", "color": "#607D8B", "command": "show_settings"},
        {"id": "collect", "label": "数据采集", "color": "#009688", "command": "data_collection"}
      ]
    }
  ]
}
```

**新增菜单项只需编辑 JSON，不改代码。**

#### 1.7 颜色值 → `colors.xml`

20 处代码中的 `0xFF...` 颜色值迁移到 `res/values/colors.xml`。

#### 1.8 RingMenuView 绘制参数 → `attrs.xml`

```xml
<!-- res/values/attrs.xml -->
<declare-styleable name="RingMenuView">
    <attr name="ringInnerRadius" format="dimension"/>
    <attr name="ringWidth" format="dimension"/>
    <attr name="ringSubWidth" format="dimension"/>
    <attr name="ringGapAngle" format="float"/>
    <attr name="ringCenterColor" format="color"/>
    <attr name="ringOverlayColor" format="color"/>
</declare-styleable>
```

---

### 改动 2：MainActivity 瘦身

**目标**：1476 行 → ~300 行

#### 2.1 抽出的方法和去向

| 从 MainActivity 抽出 | 行数 | 去向 | 说明 |
|----------------------|------|------|------|
| `CommandExecutor` 14 个方法 | ~90 | `AppCommandHandler.kt` | 命令处理独立类 |
| `loadSettings()` | 110 | `SettingsFragment.kt` + `SettingsViewModel.kt` | 设置页独立 |
| `loadHistory()` + `setupHistoryAdapterListener()` | 63 | `HistoryFragment.kt` + `HistoryViewModel.kt` | 历史页独立 |
| `showPreviewDialog()` | 63 | `ui/dialog/TripPreviewDialog.kt` | 对话框独立类 |
| `setupRingMenu()` + `executeRingMenuItem()` | 82 | `menu/MenuConfig.kt` + `command/CommandRouter.kt` | 菜单配置 + 路由独立 |
| `initMap()` + `enableMapLocation()` + `drawRoute()` + `clearRouteDisplay()` + `addDestinationMarker()` + `clearMarkers()` | 61 | `MapFragment.kt` | 地图相关移入 Fragment |
| `searchDestination()` + `setDestination()` + `onPoiSearched()` | 89 | `MapFragment.kt` | 搜索相关移入 Fragment |
| `sendTripPreview()` + `parseAndShowPreviewResult()` | 57 | `TripPreviewRepository.kt` + `MainViewModel.kt` | 网络请求移入数据层 |
| `formatDistance()` + `formatDuration()` + `cleanSpeechText()` | 15 | `util/FormatUtils.kt` + `util/TextUtils.kt` | 工具类 |
| `getAppSignatureSha1()` | 21 | `util/SecurityUtils.kt` | 工具类 |

#### 2.2 瘦身后的 MainActivity 结构

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initAmapSdk()
        requestPermissions()
        GestureVoiceLauncher.attach(this, viewModel.voiceInteractionManager, this)
        setupRingMenu()  // 只做 inflate + 绑定回调，数据来自 MenuConfig

        // 订阅 ViewModel 状态
        observeNavigationState()
        observeUiEffects()
    }

    // ── 手势回调（4 个方法，共 ~15 行）──
    override fun onVoiceAssistant() { viewModel.startVoiceAssistant() }
    override fun onRingMenuShow(x: Float, y: Float) { showRingMenu() }
    override fun onRingMenuConfirm() { /* no-op, RingMenuView handles */ }
    override fun onCancel() { hideRingMenu() }

    // ── 生命周期（~30 行）──
    override fun onResume() { ... }
    override fun onPause() { ... }
    override fun onDestroy() { ... }

    // ── 权限（~25 行）──
    private fun requestPermissions() { ... }
    override fun onRequestPermissionsResult() { ... }

    // ── UI 效果订阅（~40 行）──
    private fun observeUiEffects() {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is UiEffect.ShowToast -> Toast.makeText(this, effect.message, Toast.LENGTH_SHORT).show()
                is UiEffect.Speak -> viewModel.speak(effect.text)
                is UiEffect.NavigateToVisionTest -> startActivity(Intent(this, VisionTestActivity::class.java))
                // ...
            }
        }
    }

    // ── 环形菜单（~20 行，只管 inflate 和显示/隐藏）──
    private fun setupRingMenu() { ... }
    private fun showRingMenu() { ... }
    private fun hideRingMenu() { ... }
}
```

---

### 改动 3：命令路由系统

#### 3.1 核心设计

**所有功能入口统一走命令 ID 路由**，不区分来源（语音/菜单/手势）。

```
语音命令 → VoiceCommandInterpreter → command_id → CommandRouter → AppCommandHandler
环形菜单 → MenuConfig → command_id → CommandRouter → AppCommandHandler
手势检测 → GestureVoiceLauncher → command_id → CommandRouter → AppCommandHandler
```

#### 3.2 CommandRouter

```kotlin
// command/CommandRouter.kt
class CommandRouter @Inject constructor(
    private val handler: AppCommandHandler
) {
    fun execute(commandId: String, params: Map<String, String> = emptyMap()) {
        handler.handle(commandId, params)
    }
}
```

#### 3.3 CommandHandler 接口

```kotlin
// command/CommandHandler.kt
interface CommandHandler {
    fun handle(commandId: String, params: Map<String, String>)
    fun canHandle(commandId: String): Boolean
    fun queryState(commandId: String): String?  // 用于语音查询（如"我在哪里"）
}
```

#### 3.4 AppCommandHandler（从 MainActivity 抽出的 14 个方法）

```kotlin
// command/AppCommandHandler.kt
@Singleton
class AppCommandHandler @Inject constructor(
    private val navigationManager: NavigationManager,
    private val voiceInteractionManager: VoiceInteractionManager,
    private val tripPreviewService: TripPreviewService,
    private val previewRepository: TripPreviewRepository,
    private val constants: AppConstants
) : CommandHandler {

    // 事件输出（ViewModel 订阅后更新 UI）
    private val _events = MutableSharedFlow<CommandEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<CommandEvent> = _events

    override fun handle(commandId: String, params: Map<String, String>) {
        when (commandId) {
            "navigate_to" -> navigateTo(params["destination"] ?: return)
            "stop_navigation" -> stopNavigation()
            "start_obstacle_avoidance" -> _events.tryEmit(CommandEvent.OpenObstacleAvoidance)
            "stop_obstacle_avoidance" -> _events.tryEmit(CommandEvent.StopObstacleAvoidance)
            "preview_route" -> previewRoute()
            "where_am_i" -> _events.tryEmit(CommandEvent.AnnounceLocation)
            "repeat_last" -> repeatLast()
            "query_status" -> announceStatus()
            "voice_assistant" -> voiceInteractionManager.startListening(Mode.COMMAND)
            "show_history" -> _events.tryEmit(CommandEvent.ShowHistory)
            "show_settings" -> _events.tryEmit(CommandEvent.ShowSettings)
            "data_collection" -> _events.tryEmit(CommandEvent.OpenDataCollection)
            "text_search" -> _events.tryEmit(CommandEvent.SearchDestination(params["keyword"] ?: return))
            else -> _events.tryEmit(CommandEvent.UnknownCommand(commandId))
        }
    }
}
```

#### 3.5 命令 ID 对照表

| command_id | 来源 | 说明 |
|------------|------|------|
| `navigate_to` | 语音/菜单 | 导航到指定目的地（params: destination） |
| `stop_navigation` | 语音/菜单 | 停止导航 |
| `start_obstacle_avoidance` | 语音/菜单 | 启动避障 |
| `stop_obstacle_avoidance` | 语音 | 停止避障 |
| `preview_route` | 语音/菜单 | 行前预览 |
| `where_am_i` | 语音 | 播报当前位置 |
| `repeat_last` | 语音 | 重复上一次播报 |
| `query_status` | 语音 | 查询运行状态 |
| `voice_assistant` | 菜单/手势 | 启动语音助手 |
| `text_search` | 语音 | 文本搜索（params: keyword） |
| `show_history` | 菜单 | 打开历史记录 |
| `show_settings` | 菜单 | 打开设置 |
| `data_collection` | 菜单 | 打开数据采集 |

**新增功能只需**：
1. 在 `AppCommandHandler.handle()` 加一个 `when` 分支
2. 在 `assets/menu_config.json` 加菜单项（如需菜单入口）
3. 在 `assets/voice_keywords.json` 加关键词（如需语音入口）

---

### 改动 4：MenuAction 解耦

#### 改前

```kotlin
// RingMenuItem.kt — sealed class，扩展必须改源码
sealed class MenuAction {
    object Navigate : MenuAction()
    object ObstacleAvoid : MenuAction()
    // ...每个功能一个子类
}
```

#### 改后

```kotlin
// RingMenuItem.kt — 用 command_id 字符串替代 sealed class
data class RingMenuItem(
    val id: String,
    val label: String,
    val color: Int,
    val command: String,           // command_id，直接对应 CommandRouter
    val children: List<RingMenuItem>? = null
)
```

```kotlin
// MainActivity 中菜单回调
ringMenuView.onItemExecuted = { item ->
    hideRingMenu()
    commandRouter.execute(item.command)  // 一行搞定，不再有 when 分支
}
```

**扩展零成本**：新增菜单项只改 JSON 配置文件。

---

## 四、VoiceInteractionManager 与 CommandRouter 的关系

```
用户语音
  → VoiceInteractionManager 听/识别
  → VoiceCommandInterpreter 解析 → VoiceCommand
  → VoiceInteractionManager.processCommand()
      → commandRouter.execute(command.type.functionName, params)
      → AppCommandHandler.handle() 执行具体操作
```

`VoiceInteractionManager` 只负责**听和说**（ASR + TTS + 状态反馈），**做**的部分委托给 `CommandRouter`。

`CommandExecutor` 接口废弃，由 `CommandHandler` 替代。

---

## 五、GestureVoiceLauncher 解耦

#### 改前

```kotlin
// attach() 强制传入 VoiceInteractionManager
fun attach(activity: Activity, vim: VoiceInteractionManager, cb: GestureCallback)
```

#### 改后

```kotlin
// 只传回调，不依赖任何业务类
fun attach(activity: Activity, config: GestureConfig, cb: GestureCallback)

data class GestureConfig(
    val longPressDurationMs: Long = 500,   // 从 app_constants.json 读取
    val moveThresholdPx: Float = 50f,
    val vibrateDurationMs: Long = 100
)
```

---

## 六、工具类抽取

### `util/FormatUtils.kt`

```kotlin
object FormatUtils {
    fun formatDistance(meters: Float): String { ... }
    fun formatDuration(seconds: Float): String { ... }
}
```

### `util/TextUtils.kt`

```kotlin
object TextUtils {
    fun cleanSpeechText(text: String?): String { ... }
}
```

### `util/SecurityUtils.kt`

```kotlin
object SecurityUtils {
    fun getAppSignatureSha1(context: Context): String { ... }
    fun hasValidAmapKey(): Boolean { ... }
}
```

---

## 七、改动文件清单

### 新建文件（~12 个）

| 文件 | 作用 | 行数预估 |
|------|------|---------|
| `config/AppConstants.kt` | 编译时常量 | ~40 |
| `config/AppConfigProvider.kt` | JSON 配置加载器 | ~60 |
| `command/CommandRouter.kt` | 统一路由 | ~30 |
| `command/CommandHandler.kt` | 命令接口 | ~15 |
| `command/AppCommandHandler.kt` | 命令实现（从 MainActivity 抽出） | ~120 |
| `command/CommandEvent.kt` | 事件 sealed class | ~20 |
| `menu/MenuConfig.kt` | 菜单 JSON 加载 | ~50 |
| `util/FormatUtils.kt` | 格式化工具 | ~25 |
| `util/TextUtils.kt` | 文本工具 | ~10 |
| `util/SecurityUtils.kt` | 安全工具 | ~30 |
| `ui/dialog/TripPreviewDialog.kt` | 行前预览对话框 | ~80 |
| `assets/menu_config.json` | 菜单配置 | ~30 |
| `assets/voice_keywords.json` | 语音关键词 | ~40 |
| `assets/llm_system_prompt.txt` | LLM prompt | ~25 |
| `assets/app_constants.json` | 超时/阈值配置 | ~60 |

### 修改文件（~10 个）

| 文件 | 改动 |
|------|------|
| `MainActivity.kt` | 1476→~300 行：抽出 CommandExecutor、设置、历史、菜单配置、格式化 |
| `MainViewModel.kt` | 承接从 Activity 抽出的业务逻辑，持有 CommandRouter |
| `VoiceInteractionManager.kt` | 36 处中文字符串 → strings.xml；processCommand() 委托 CommandRouter |
| `VoiceCommandInterpreter.kt` | 关键词从 assets 加载 |
| `LlmFunctionCaller.kt` | prompt 从 assets 加载；URL/超时从配置读取 |
| `BaiduSpeechManager.kt` | 19 处错误提示 → strings.xml |
| `BaiduTtsManager.kt` | TTS 参数从配置读取 |
| `RingMenuItem.kt` | MenuAction sealed class → command 字符串 |
| `RingMenuView.kt` | Paint 颜色 → attrs/colors.xml |
| `GestureVoiceLauncher.kt` | 移除 VoiceInteractionManager 依赖，参数外部化 |
| `app/build.gradle` | 新增 assets JSON 的 BuildConfig 字段 |
| `res/values/strings.xml` | 新增 ~300 条字符串 |
| `res/values/colors.xml` | 新增 20 条颜色 |
| `res/values/attrs.xml` | 新增 RingMenuView 自定义属性 |

---

## 八、实施顺序

```
Phase 1: 基础设施（不影响功能）
  ├── 1a. 创建 assets/app_constants.json + AppConstants.kt
  ├── 1b. 创建 util/ 工具类
  └── 1c. 中文字符串迁移到 strings.xml

Phase 2: 命令路由系统
  ├── 2a. 创建 command/CommandRouter + CommandHandler + AppCommandHandler
  ├── 2b. 语音关键词迁移到 assets/voice_keywords.json
  ├── 2c. LLM prompt 迁移到 assets/llm_system_prompt.txt
  └── 2d. VoiceInteractionManager 改用 CommandRouter

Phase 3: 菜单系统解耦
  ├── 3a. 创建 menu/MenuConfig 从 JSON 加载菜单
  ├── 3b. RingMenuItem.MenuAction 改为 command 字符串
  └── 3c. MainActivity.setupRingMenu() 改为读配置

Phase 4: MainActivity 瘦身
  ├── 4a. 设置页 → SettingsFragment + SettingsViewModel
  ├── 4b. 历史页 → HistoryFragment + HistoryViewModel
  ├── 4c. 行前预览对话框 → TripPreviewDialog
  └── 4d. 清理 MainActivity 残留代码

Phase 5: 手势/菜单/URL 外部化
  ├── 5a. GestureVoiceLauncher 参数外部化
  ├── 5b. RingMenuView 颜色/尺寸迁移到 attrs/colors.xml
  └── 5c. URL 迁移到 local.properties + BuildConfig
```

---

## 九、改动前后对比

| 指标 | 改前 | 改后 |
|------|------|------|
| MainActivity 行数 | 1476 | ~300 |
| 硬编码总数 | 338 | **0** |
| 中文字符串在代码中 | ~300 处 | 0（全部在 strings.xml） |
| URL/IP 在代码中 | 5 处 | 0（全部在 BuildConfig） |
| 阈值在代码中 | ~89 处 | 0（全部在 JSON/常量） |
| 新增菜单项需改文件数 | 3 个 | 1 个（menu_config.json） |
| 新增语音命令需改文件数 | 3 个 | 2 个（keywords.json + CommandHandler） |
| 动画可独立替换 | 不可（无动画系统） | 可（RingMenuView 与业务分离） |
| 命令来源扩展 | 不支持 | 只需实现 CommandHandler 接口 |
