# 2026-06-17 夜间自检修复报告

> 执行时间: 2026-06-17 22:00 ~ 2026-06-18 03:00
> 基线: `audit/2026-06-17/01_full_scan.md` (83 个发现)

---

## 一、架构层抽取（UseCase 层引入）

### 新增文件

| 文件 | 作用 |
|------|------|
| `domain/usecase/NavigationUseCase.kt` | 封装 NavigationManager，ViewModel 通过此接口操作导航 |
| `domain/usecase/VoiceUseCase.kt` | 封装 VoiceInteractionManager + TtsPlayer + TtsPreloader |
| `domain/usecase/TripPreviewUseCase.kt` | 封装 TripPreviewService 网络请求 |
| `di/DomainModule.kt` | Hilt Module，提供 UseCase 实例 |

### 依赖关系变化

```
改前：
  ViewModel → NavigationManager (具体类)
  ViewModel → VoiceInteractionManager (具体类)
  ViewModel → TtsPlayer (具体类)
  ViewModel → TripPreviewService (具体类)
  MainActivity → NavigationManager (直接注入)
  MainActivity → VoiceInteractionManager (直接注入)

改后：
  ViewModel → NavigationUseCase → NavigationManager
  ViewModel → VoiceUseCase → VoiceInteractionManager + TtsPlayer
  ViewModel → TripPreviewUseCase → TripPreviewService
  MainActivity → ViewModel.voiceUseCase（通过 ViewModel 访问）
```

---

## 二、CRITICAL 问题修复（5 个 !! 强制解包）

| # | 文件 | 行号 | 修复 |
|---|------|------|------|
| 1 | MainActivity.kt | 682 | `mMap!!.isMyLocationEnabled` → `val map = mMap ?: return; map.isMyLocationEnabled` |
| 2 | MainActivity.kt | 704 | `mMap!!.animateCamera` → `map.animateCamera` |
| 3 | MainActivity.kt | 812 | `poiResults!![0]` → `(poiResults ?: return)[0]` |
| 4 | MainActivity.kt | 912 | `mMap!!.addPolyline` → `val map = mMap ?: return; map.addPolyline` |
| 5 | MainActivity.kt | 926 | `mMap!!.addMarker` → `val map = mMap ?: return; map.addMarker` |

---

## 三、死代码清理

| 文件 | 原因 |
|------|------|
| `stt/SpeechRecognitionManager.kt` | 未被任何文件引用，未在 Manifest 注册 |
| `stt/SpeechRecognitionService.kt` | 同上 |
| `ObstacleWarningNotifier` (已删除) | 全局单例，listener 从未被设置，6 处调用全部为空操作 |

---

## 四、夜间自检总修复统计

### 对比 2026-06-16 审计结果

| 级别 | 06-16 发现 | 06-17 修复 | 累计修复率 |
|------|-----------|-----------|----------|
| **CRITICAL** | 11 | +5 | **100%** ✅ |
| **HIGH** | 36 | +2 (UseCase 层解决 H8) | **~90%** ✅ |
| **MEDIUM** | 22 | +2 (死代码清理) | ~50% |
| **LOW** | 17 | +2 | ~35% |

### 剩余未修复

| 级别 | 问题 | 原因 |
|------|------|------|
| MEDIUM | 根包文件未迁入子包（11个） | 需要大量 import 更新，风险高 |
| MEDIUM | 百度 API Key 明文在 strings.xml | 需要迁移到 local.properties |
| LOW | 不一致的字符串命名前缀 | 需要全局重命名，有破坏性 |
| LOW | 不一致的颜色/尺寸命名 | 同上 |

---

## 五、当前项目架构

```
com.example.voicenavigation/
├── app/CorSightApp.kt
├── di/                     # Hilt 模块 (5个)
│   ├── AppModule.kt
│   ├── VoiceModule.kt
│   ├── NavigationModule.kt
│   ├── InferenceModule.kt
│   ├── CommandModule.kt
│   └── DomainModule.kt     # 新增：UseCase 提供
├── domain/usecase/         # 新增：用例层
│   ├── NavigationUseCase.kt
│   ├── VoiceUseCase.kt
│   └── TripPreviewUseCase.kt
├── command/                # 命令路由
│   ├── CommandRouter.kt
│   ├── CommandEvent.kt
│   ├── MenuCommand.kt
│   └── commands/ (13个)
├── menu/
│   ├── MenuConfig.kt
│   └── RingMenuItem.kt
├── config/                 # 配置
│   ├── AppConfig.kt
│   └── AppConstants.kt
│   └── AppConfigProvider.kt
├── data/                   # 数据层
│   ├── local/ (Room)
│   ├── repository/
│   └── tts/ (TtsAudioCache, TtsPlayer, TtsPreloader)
├── voice/                  # 语音子系统
│   ├── stt/ (BaiduSpeechManager, BaiduTtsManager, UnifiedTtsManager)
│   ├── VoiceInteractionManager.kt
│   ├── VoiceCommandInterpreter.kt
│   └── LlmFunctionCaller.kt
├── navigation/
│   └── NavigationManager.kt
├── util/                   # 工具类
│   ├── FormatUtils.kt
│   ├── TextUtils.kt
│   ├── SecurityUtils.kt
│   └── RadialGeometry.kt
├── ui/                     # UI 层
│   ├── main/ (MainActivity, MainViewModel, Fragment 骨架)
│   ├── vision/ (VisionTestActivity, VisionViewModel)
│   ├── ringmenu/ (RingMenuView, RingMenuCoordinator, InteractionEvent)
│   ├── voice/ (GestureVoiceLauncher)
│   ├── dialog/ (TripPreviewDialog)
│   └── collection/ (CaptureHubActivity 等)
└── 根包 (待迁移)
    ├── MainActivity.kt
    ├── VisionTestActivity.kt
    ├── CameraSource.kt, NetworkSource.kt
    ├── ObstacleAlert*.kt, ImageQualityAnalyzer.kt
    ├── DetectionOverlayView.kt, YoloModelConfig.kt
    └── AppConfig.kt
```

---

## 六、关键指标

| 指标 | 重构前 | 当前 |
|------|--------|------|
| Java 文件数 | 16 | **0** |
| MainActivity 行数 | 1338 | **~1200** |
| 硬编码中文字符串 | ~300 | **~140** (已迁移一半) |
| !! 强制解包 | ~15 | **~8** (剩余在 BaiduSpeechManager 等 SDK 封装中) |
| ViewModel 直接依赖 domain 类 | 7 个 | **4 个** (UseCase 层解耦) |
| UseCase 层 | 无 | **3 个** |
| Hilt Module | 0 | **6 个** |
| 配置文件 (JSON) | 0 | **3 个** |
| 废弃文件 | 2 个 | **0** (已清理) |
