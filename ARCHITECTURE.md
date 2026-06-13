# CorSight Android v2.0 架构文档

> 瞳心引航 — 面向盲人/视障用户的语音导航 + 视觉避障 Android 应用
>
> 分支: `refactor/architecture-v2` | 最后更新: 2026-06-13

---

## 一、项目概览

| 项 | 值 |
|---|---|
| 包名 | `com.example.voicenavigation` |
| 应用名 | 瞳心引航 |
| 最低 SDK | 24 (Android 7.0) |
| 目标 SDK | 36 |
| Kotlin | 1.9.10 |
| AGP | 8.6.1 |
| Gradle | 8.10 |
| 语言 | **Kotlin 100%**（Java 源文件 0 个） |

---

## 二、Gradle 模块结构

```
CorSight-Android_v2.0/
├── app/          ← 主应用（所有 UI + 业务逻辑 + Hilt DI）
├── inference/    ← 推理引擎库（YOLOv8 ONNX Runtime）
└── vision/       ← 视觉处理库（ImageSource / VisionTool 抽象）
```

**模块依赖关系：**

```
app ──→ inference
app ──→ vision ──→ inference
```

### 2.1 inference 模块（3 文件）

纯推理层，无 Android Activity，无业务逻辑。

| 文件 | 职责 |
|---|---|
| `InferenceEngine.kt` | `InferenceEngine` / `ObjectDetector` 接口 + `Detection` 数据类 |
| `YoloV8OnnxEngine.kt` | ONNX Runtime YOLOv8 实现（letterbox → 推理 → NMS） |
| `ModelRegistry.kt` | 全局模型注册表（object 单例，管理模型生命周期） |

依赖：`onnxruntime-android:1.17.3`

### 2.2 vision 模块（5 文件）

视觉工具抽象层。

| 文件 | 职责 |
|---|---|
| `ImageSource.kt` | 图像源接口（`start`/`stop`/`isRunning`） |
| `VisionTool.kt` | 视觉工具接口 + `ToolResult` sealed class |
| `ToolRegistry.kt` | 工具注册表（单例，暴露 `activeTool: StateFlow`） |
| `Frame.kt` | 帧数据类（bitmap + rotation + source） |
| `tools/GenericDetectionTool.kt` | 通用检测工具（串联 ModelRegistry + ObjectDetector） |

依赖：`project(':inference')`, `kotlinx-coroutines-android:1.7.3`

### 2.3 app 模块（41 个 Kotlin 文件）

主应用，包含所有 UI、业务逻辑、依赖注入。

**关键依赖：**

| 类别 | 库 | 版本 |
|---|---|---|
| DI | Hilt (Dagger) | 2.51 |
| 架构 | Lifecycle ViewModel | 2.8.0 |
| 导航 | Navigation Component | 2.7.7 |
| UI | Material + ConstraintLayout + Fragment KTX | — |
| 地图 | 高德 3D 地图 + POI 搜索 | 10.0.600 / 9.5.0 |
| 相机 | CameraX | 1.3.1 |
| 数据库 | Room (runtime + ktx + compiler) | 2.6.1 |
| 网络 | OkHttp + Retrofit | 4.12.0 / 2.9.0 |
| 语音 | 百度 ASR/TTS SDK | AAR |
| 异步 | Kotlin Coroutines + Flow | 1.7.3 |

---

## 三、app 模块包结构

```
com.example.voicenavigation/
│
├── app/
│   └── CorSightApp.kt                  # @HiltAndroidApp Application
│
├── di/                                  # Hilt 依赖注入模块
│   ├── AppModule.kt                     # SharedPreferences, OkHttpClient, AppDatabase, DAO
│   ├── VoiceModule.kt                  # BaiduSpeechManager, BaiduTtsManager, VoiceInteractionManager, LlmFunctionCaller
│   ├── NavigationModule.kt             # NavigationManager
│   └── InferenceModule.kt              # ModelRegistry, ObstacleAlertTracker
│
├── config/
│   └── AppConfig.kt                     # SharedPreferences 配置常量 + 工具方法
│
├── data/                                # 数据层
│   ├── local/
│   │   ├── AppDatabase.kt              # Room 数据库（voice_records 表）
│   │   ├── VoiceRecordDao.kt           # DAO（同步查询 + Flow 查询）
│   │   └── VoiceRecord.kt              # Entity
│   └── VoiceRecordRepository.kt        # Repository（suspend + Flow 封装）
│
├── voice/                               # 语音子系统
│   ├── stt/
│   │   ├── BaiduSpeechManager.kt       # 百度 ASR（VAD 触摸模式 + 兜底结果机制）
│   │   ├── BaiduTtsManager.kt          # 百度 TTS（FIFO 队列顺序播报）
│   │   ├── SpeechRecognitionManager.kt # Android 原生 SpeechRecognizer（备选）
│   │   └── SpeechRecognitionService.kt # 后台识别 Service（备选）
│   ├── VoiceInteractionManager.kt      # 语音交互核心：「听→懂→做→说」闭环
│   ├── VoiceCommandInterpreter.kt      # 本地关键词匹配（离线快速）
│   ├── VoiceCommand.kt                 # 命令数据类（10 种 Type 枚举）
│   └── LlmFunctionCaller.kt            # LLM Function Calling（DeepSeek 等云端兜底）
│
├── navigation/
│   └── NavigationManager.kt            # 高德步行导航（路线规划 + 定位 + 偏航重规划 + 到达判定）
│
├── obstacle/                            # 障碍物检测子系统（尚未迁移到此包）
│   ├── CameraSource.kt                 # CameraX 实现 ImageSource
│   ├── NetworkSource.kt                # Socket 网络流实现 ImageSource（ESP32）
│   ├── ObstacleRiskAnalyzer.kt         # 风险区重叠分析（LOW/MEDIUM/HIGH）
│   ├── ObstacleAlertTracker.kt         # 跨帧追踪 + 升级语音触发
│   ├── ObstacleAlert.kt                # 数据类 + ObstacleWarningNotifier（全局事件分发）
│   ├── ImageQualityAnalyzer.kt         # 图像清晰度评估（拉普拉斯方差）
│   ├── DetectionOverlayView.kt         # 自定义 View（绘制检测框 + 风险区）
│   └── YoloModelConfig.kt              # 模型路径及阈值配置
│
├── network/
│   └── TripPreviewService.kt           # 行前预览 HTTP 请求
│
├── collection/                          # 数据采集子系统
│   ├── DataCollectionActivity.kt       # 8 方向街景拍照界面
│   ├── CaptureTask.kt                  # 采集任务数据
│   ├── CompassService.kt               # 磁力计指南针
│   ├── GridUtils.kt                    # Web Mercator 地理网格
│   ├── TaskStorage.kt                  # SharedPreferences 存储
│   └── UploadService.kt                # OkHttp 上传（重试 + 补传）
│
├── ui/                                  # UI 层（MVVM 架构）
│   ├── main/
│   │   ├── MainViewModel.kt            # 核心 ViewModel（导航状态 + POI + 语音命令）
│   │   └── map/
│   │       └── MapFragment.kt          # 地图 Fragment 骨架（待完善）
│   └── vision/
│       └── VisionViewModel.kt          # 避障 ViewModel（推理调度 + 结果稳定化）
│
├── MainActivity.kt                      # 主入口 Activity（1338 行，待拆分为 Fragment）
├── VisionTestActivity.kt               # 避障 Activity（751 行，待接入 VisionViewModel）
├── VoiceRecordAdapter.kt               # 历史记录 RecyclerView Adapter
└── SuggestionAdapter.kt                # POI 搜索建议 RecyclerView Adapter
```

---

## 四、核心数据流

### 4.1 语音交互流程

```
用户按住按钮 → BaiduSpeechManager（百度 ASR）
  → VoiceInteractionManager.onResult(text)
  → VoiceCommandInterpreter.interpret(text)
  ├─ 本地命中 → VoiceCommand → executeCommand() → TTS 播报
  └─ 本地未命中 → LlmFunctionCaller.call(text) → LLM Function Calling
       → mapLLMResultToCommand() → executeCommand() → TTS 播报
```

**支持的 10 种语音命令：**
`NAVIGATE_TO` / `START_OBSTACLE_AVOIDANCE` / `STOP_NAVIGATION` / `STOP_OBSTACLE_AVOIDANCE` / `WHERE_AM_I` / `REPEAT_LAST` / `PREVIEW_ROUTE` / `QUERY_STATUS` / `TEXT_SEARCH` / `UNKNOWN`

### 4.2 导航流程

```
语音/手动输入 → MainActivity.searchDestination()
  → 高德 PoiSearch → onPoiSearched()
  → NavigationManager.planRoute()
  → 高德 RouteSearch → onWalkRouteSearched()
  → AMapLocationClient 持续定位
  → updateNavigationProgress() → 偏航检测 → triggerReroute()
  → 到达判定 → onArrived()
```

### 4.3 避障检测流程

```
图像源（CameraSource / NetworkSource）
  → processFrame()
  ├─ 本地模式: ImageQualityAnalyzer → ToolRegistry → GenericDetectionTool → YoloV8OnnxEngine
  └─ 云端模式: OkHttp POST /api/detect → JSON 解析
  → ObstacleRiskAnalyzer（风险区重叠计算）
  → ObstacleAlertTracker（跨帧追踪 + 升级语音触发）
  → DetectionOverlayView（绘制）
  → BaiduTtsManager（语音播报）
```

### 4.4 数据采集流程

```
DataCollectionActivity
  → CompassService（磁力计对准 8 方向）
  → 相机拍照 → 本地存储
  → UploadService（OkHttp 上传云端）
```

---

## 五、依赖注入架构（Hilt）

```
┌─────────────────────────────────────────────────────┐
│                    CorSightApp                       │
│                  (@HiltAndroidApp)                   │
└──────────────┬──────────────────────────────────────┘
               │ SingletonComponent
    ┌──────────┼──────────┬──────────────┐
    ▼          ▼          ▼              ▼
┌────────┐ ┌────────┐ ┌──────────┐ ┌──────────────┐
│AppModule│ │Voice   │ │Navigation│ │Inference     │
│        │ │Module  │ │Module    │ │Module        │
├────────┤ ├────────┤ ├──────────┤ ├──────────────┤
│Shared  │ │Baidu   │ │Navigation│ │ModelRegistry │
│Prefs   │ │Speech  │ │Manager   │ │              │
│OkHttp  │ │Baidu   │ │          │ │ObstacleAlert │
│AppDB   │ │Tts     │ │          │ │Tracker       │
│DAO     │ │Voice   │ │          │ │              │
│        │ │Interact│ │          │ │              │
│        │ │LlmCall │ │          │ │              │
└────────┘ └────────┘ └──────────┘ └──────────────┘
```

---

## 六、Activity 与 Fragment 布局

| 组件 | 语言 | 行数 | 状态 |
|---|---|---|---|
| `MainActivity` | Kotlin | ~1338 | God Activity，待拆分 |
| `VisionTestActivity` | Kotlin | ~751 | 待接入 VisionViewModel |
| `DataCollectionActivity` | Kotlin | ~625 | 保持现状 |
| `MapFragment` | Kotlin | 骨架 | 待完善 |
| `MainViewModel` | Kotlin | — | 已创建，待接入 MainActivity |
| `VisionViewModel` | Kotlin | ~280 | 已创建，待接入 VisionTestActivity |

**布局文件（10 个）：**

| 文件 | 用途 |
|---|---|
| `activity_main.xml` | 主界面（地图 + 搜索 + 语音按钮 + 底部导航） |
| `activity_vision_test.xml` | 避障界面（相机预览 + 覆盖层） |
| `activity_data_collection.xml` | 数据采集（横屏变体 `layout-land/`） |
| `page_history.xml` | 历史记录页（嵌入 MainActivity） |
| `page_settings.xml` | 设置页（嵌入 MainActivity） |
| `dialog_preview.xml` | 数据采集预览 |
| `dialog_preview_result.xml` | 行前预览结果 |
| `item_voice_record.xml` | 历史记录列表项 |
| `item_suggestion.xml` | POI 搜索建议列表项 |

---

## 七、完整文件清单

### app 模块 Kotlin 文件（41 个）

| # | 包路径 | 文件 | 行数 | 职责 |
|---|---|---|---|---|
| 1 | `app/` | `CorSightApp.kt` | 6 | Hilt Application |
| 2 | `config/` | `AppConfig.kt` | 31 | 配置常量 |
| 3 | `di/` | `AppModule.kt` | 42 | SharedPreferences / OkHttp / Room |
| 4 | `di/` | `VoiceModule.kt` | 56 | 语音子系统 DI |
| 5 | `di/` | `NavigationModule.kt` | 21 | 导航 DI |
| 6 | `di/` | `InferenceModule.kt` | 23 | 推理 DI |
| 7 | `data/` | `AppDatabase.kt` | 11 | Room DB |
| 8 | `data/` | `VoiceRecord.kt` | 27 | Entity |
| 9 | `data/` | `VoiceRecordDao.kt` | 33 | DAO |
| 10 | `data/` | `VoiceRecordRepository.kt` | 33 | Repository |
| 11 | `data/` | `VoiceRecordAdapter.kt` | 65 | RecyclerView Adapter |
| 12 | `data/` | `SuggestionAdapter.kt` | 51 | RecyclerView Adapter |
| 13 | `voice/` | `VoiceCommand.kt` | ~60 | 命令数据类 |
| 14 | `voice/` | `VoiceCommandInterpreter.kt` | ~150 | 关键词匹配 |
| 15 | `voice/` | `VoiceInteractionManager.kt` | ~320 | 语音交互核心 |
| 16 | `voice/` | `LlmFunctionCaller.kt` | ~230 | LLM Function Calling |
| 17 | `voice/stt/` | `BaiduSpeechManager.kt` | ~280 | 百度 ASR |
| 18 | `voice/stt/` | `BaiduTtsManager.kt` | ~250 | 百度 TTS |
| 19 | `voice/stt/` | `SpeechRecognitionManager.kt` | ~160 | Android ASR |
| 20 | `voice/stt/` | `SpeechRecognitionService.kt` | ~150 | 后台 Service |
| 21 | `navigation/` | `NavigationManager.kt` | ~350 | 高德步行导航 |
| 22 | `network/` | `TripPreviewService.kt` | ~120 | 行前预览 |
| 23 | root | `MainActivity.kt` | ~1338 | 主入口（待拆分） |
| 24 | root | `VisionTestActivity.kt` | ~751 | 避障（待接入 VM） |
| 25 | root | `CameraSource.kt` | ~88 | CameraX 图像源 |
| 26 | root | `NetworkSource.kt` | ~94 | Socket 网络图像源 |
| 27 | root | `ObstacleAlert.kt` | ~42 | 告警数据类 + 通知器 |
| 28 | root | `ObstacleAlertTracker.kt` | ~103 | 跨帧追踪 |
| 29 | root | `ObstacleRiskAnalyzer.kt` | ~45 | 风险区分析 |
| 30 | root | `ImageQualityAnalyzer.kt` | ~50 | 清晰度评估 |
| 31 | root | `DetectionOverlayView.kt` | ~120 | 检测框绘制 |
| 32 | root | `YoloModelConfig.kt` | ~20 | 模型配置 |
| 33 | root | `AppConfig.kt` | 31 | 配置（同 #2） |
| 34 | `ui/main/` | `MainViewModel.kt` | ~80 | 核心 ViewModel |
| 35 | `ui/main/map/` | `MapFragment.kt` | ~30 | 地图 Fragment 骨架 |
| 36 | `ui/vision/` | `VisionViewModel.kt` | ~280 | 避障 ViewModel |
| 37 | `collection/` | `DataCollectionActivity.kt` | ~625 | 数据采集 |
| 38 | `collection/` | `CaptureTask.kt` | ~20 | 任务数据 |
| 39 | `collection/` | `CompassService.kt` | ~80 | 指南针 |
| 40 | `collection/` | `GridUtils.kt` | ~40 | 地理网格 |
| 41 | `collection/` | `TaskStorage.kt` | ~40 | 任务存储 |
| 42 | `collection/` | `UploadService.kt` | ~100 | 数据上传 |

### inference 模块（3 文件）

| 文件 | 职责 |
|---|---|
| `InferenceEngine.kt` | 接口 + 数据类 |
| `YoloV8OnnxEngine.kt` | ONNX 推理引擎 |
| `ModelRegistry.kt` | 模型注册表 |

### vision 模块（5 文件）

| 文件 | 职责 |
|---|---|
| `ImageSource.kt` | 图像源接口 |
| `VisionTool.kt` | 视觉工具接口 |
| `ToolRegistry.kt` | 工具注册表 |
| `Frame.kt` | 帧数据 |
| `tools/GenericDetectionTool.kt` | 通用检测工具 |

---

## 八、待完成事项

### 高优先级
- [ ] MainActivity 接入 `@AndroidEntryPoint` + Hilt 注入依赖
- [ ] MainActivity 拆分：MapFragment（地图+搜索+导航）、HistoryFragment、SettingsFragment
- [ ] VisionTestActivity 接入 VisionViewModel
- [ ] 将 `ObstacleWarningNotifier` 全局单例替换为 SharedFlow

### 中优先级
- [ ] 网络层统一为 Retrofit（TripPreviewService / LlmFunctionCaller / 云端检测）
- [ ] Navigation Component 图（nav_graph.xml）接入 BottomNavigationView
- [ ] MainActivity.kt 从根包迁入 `ui/main/` 包
- [ ] 障碍物子系统文件从根包迁入 `obstacle/` 包
- [ ] Adapter 文件迁入 `ui/main/adapter/` 包

### 低优先级
- [ ] 删除未使用的 `SpeechRecognitionManager` / `SpeechRecognitionService`
- [ ] 统一 TTS 实例（当前 MainActivity 和 VisionTestActivity 各自创建）
- [ ] Room migration 策略优化（当前 `fallbackToDestructiveMigration`）
- [ ] 接入 Retrofit 后删除直接 OkHttp 调用
