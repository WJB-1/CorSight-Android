# CorSight Android v2.0 架构文档

## 重构后的模块结构

```
CorSight-Android_v2.0/
├── app/              # 主应用
├── inference/        # 推理引擎库 (不变)
└── vision/           # 视觉处理库 (不变)
```

## 包结构

```
com.example.voicenavigation/
├── app/
│   └── CorSightApp.kt              # @HiltAndroidApp Application
├── di/                              # Hilt 依赖注入模块
│   ├── AppModule.kt                 # SharedPreferences, OkHttpClient, AppDatabase
│   ├── VoiceModule.kt              # STT + TTS + VoiceInteraction
│   ├── NavigationModule.kt         # NavigationManager
│   └── InferenceModule.kt          # ModelRegistry, ObstacleAlertTracker
├── config/
│   └── AppConfig.kt                 # SharedPreferences 配置常量
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt          # Room 数据库
│   │   ├── dao/VoiceRecordDao.kt   # DAO (支持 Flow 查询)
│   │   └── entity/VoiceRecord.kt   # Entity
│   ├── remote/                      # Retrofit API 接口 (待完成)
│   └── repository/
│       └── VoiceRecordRepository.kt # 语音记录 Repository
├── voice/                           # 语音子系统
│   ├── stt/
│   │   ├── BaiduSpeechManager.kt   # 百度 ASR
│   │   ├── BaiduTtsManager.kt      # 百度 TTS
│   │   ├── SpeechRecognitionManager.kt  # Android 原生 ASR
│   │   └── SpeechRecognitionService.kt  # 后台识别 Service
│   ├── VoiceInteractionManager.kt  # 语音交互核心
│   ├── VoiceCommandInterpreter.kt  # 本地关键词匹配
│   ├── VoiceCommand.kt             # 命令数据类
│   └── LlmFunctionCaller.kt        # LLM Function Calling
├── navigation/
│   └── NavigationManager.kt        # 高德步行导航
├── obstacle/                        # 障碍物检测 (待迁移)
│   ├── CameraSource.kt
│   ├── NetworkSource.kt
│   ├── ObstacleRiskAnalyzer.kt
│   ├── ObstacleAlertTracker.kt
│   ├── ObstacleAlert.kt
│   ├── ImageQualityAnalyzer.kt
│   ├── DetectionOverlayView.kt
│   └── YoloModelConfig.kt
├── network/
│   └── TripPreviewService.kt       # 行前预览
├── collection/                      # 数据采集 (待迁移)
│   ├── DataCollectionActivity.kt
│   ├── CaptureTask.kt
│   ├── CompassService.kt
│   ├── GridUtils.kt
│   ├── TaskStorage.kt
│   └── UploadService.kt
├── ui/                              # UI 层 (待创建)
│   ├── main/
│   │   ├── MainActivity.kt
│   │   ├── MainViewModel.kt
│   │   ├── map/MapFragment.kt
│   │   ├── history/HistoryFragment.kt
│   │   ├── settings/SettingsFragment.kt
│   │   └── adapter/
│   ├── vision/
│   │   ├── VisionTestActivity.kt
│   │   └── VisionViewModel.kt
│   └── collection/
│       └── CollectionViewModel.kt
└── MainActivity.kt                  # 当前位置 (待迁入 ui/main/)
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | **Kotlin** (100%) |
| 架构 | **MVVM** (ViewModel + StateFlow) |
| 依赖注入 | **Hilt** (Dagger) |
| UI | Android View + ViewBinding |
| 地图 | 高德地图 SDK |
| 相机 | CameraX |
| 推理 | ONNX Runtime (YOLOv8) |
| 语音 | 百度 ASR/TTS SDK |
| LLM | OpenAI 兼容 API (DeepSeek) |
| 网络 | OkHttp + Retrofit |
| 数据库 | Room + Flow |
| 异步 | Kotlin Coroutines + Flow |

## 重构进度

- [x] 步骤 0: 构建依赖准备 (Hilt, Lifecycle, Retrofit, Navigation)
- [x] 步骤 1: Application + Hilt 基础 Module + AppConfig Kotlin 化
- [x] 步骤 2: 数据层 Kotlin 化 + Repository
- [x] 步骤 3: 语音子系统 Kotlin 化 (全部 8 个文件)
- [x] 步骤 4: NavigationManager Kotlin 化
- [x] 步骤 5: 网络层 Kotlin 化 (TripPreviewService)
- [x] 步骤 6: MainActivity Kotlin 化 + MainViewModel + MapFragment 创建
- [x] 步骤 7: VisionViewModel 创建
- [ ] 步骤 8: 最终清理 + Fragment 完整接入
