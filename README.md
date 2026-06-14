# CorSight Android v2.0

瞳心引航 — 面向盲人/视障用户的语音导航 + 视觉避障 Android 应用。

## 功能

- **语音交互**：百度 ASR 识别 + 本地关键词匹配（113 个关键词）+ LLM Function Calling 云端兜底
- **环形菜单**：长按屏幕 500ms 弹出，滑动选择功能，松手执行
- **地图导航**：高德 POI 搜索 + 步行路线规划 + 偏航自动重规划
- **视觉避障**：本地 YOLOv8 ONNX 推理 / 云端 HTTP 推理 + 风险区分析 + 语音告警
- **TTS 播报**：百度语音合成，FIFO 队列顺序播报
- **数据采集**：8 方向街景拍照 + GPS + 罗针对准 + 云端上传
- **行前预览**：调用 CorSight-Server 后端生成路线播报文案
- **ESP32 外设**：UDP 自动发现 + Socket 网络流相机

## 架构

```
命令入口                    路由层              执行层
┌──────────┐    ┌───────────────────┐    ┌─────────────────┐
│ 语音命令  │───→│                   │───→│ NavigateToCmd   │
│ 环形菜单  │───→│  CommandRouter    │───→│ StopNavCmd      │
│ 手势检测  │───→│  (Hilt Multibind) │───→│ StartObstacleCmd│
│ UI 按钮   │───→│                   │───→│ ...共 13 个命令  │
└──────────┘    └───────────────────┘    └─────────────────┘
                         ↓ events
                   MainActivity.collect()
                         ↓
                    UI 更新 / TTS 播报
```

**核心设计**：所有功能入口（语音/菜单/手势）统一走 `command_id` → `CommandRouter` → `MenuCommand` 路由。新增功能只需加一个 Command 类 + 一行 Hilt 绑定。

## 项目结构

```
CorSight-Android_v2.0/
├── app/
│   └── src/main/
│       ├── java/com/example/voicenavigation/
│       │   ├── app/              # CorSightApp (@HiltAndroidApp)
│       │   ├── config/           # AppConstants + AppConfigProvider + AppConfig
│       │   ├── command/          # 命令路由系统
│       │   │   ├── CommandRouter.kt    # Hilt 注入 Map<String, MenuCommand>
│       │   │   ├── MenuCommand.kt      # 命令接口
│       │   │   ├── CommandEvent.kt     # UI 事件 sealed class
│       │   │   └── commands/           # 13 个独立命令类
│       │   ├── di/               # Hilt 模块 (5 个)
│       │   ├── menu/MenuConfig.kt      # JSON 菜单配置加载
│       │   ├── data/             # Room + Repository
│       │   ├── voice/            # 语音子系统 (ASR/TTS/LLM)
│       │   ├── navigation/       # 高德步行导航
│       │   ├── network/          # 行前预览 HTTP
│       │   ├── obstacle/         # 障碍物检测
│       │   ├── util/             # FormatUtils / TextUtils / SecurityUtils
│       │   ├── ui/
│       │   │   ├── main/         # MainActivity + ViewModel + Fragment
│       │   │   ├── vision/       # VisionTestActivity + ViewModel
│       │   │   ├── ringmenu/     # 环形菜单 View + 数据模型
│       │   │   ├── voice/        # 手势检测
│       │   │   └── dialog/       # 行前预览对话框
│       │   └── collection/       # 数据采集
│       ├── assets/
│       │   ├── app_constants.json      # 超时/阈值配置 (89 项)
│       │   ├── menu_config.json        # 环形菜单配置
│       │   ├── voice_keywords.json     # 语音关键词 (113 个)
│       │   └── llm_system_prompt.txt   # LLM system prompt
│       └── res/values/strings.xml      # 250+ 条字符串资源
├── inference/            # 推理引擎库 (YOLOv8 ONNX)
├── vision/               # 视觉处理库 (ImageSource/VisionTool)
└── docs/
    ├── health_check/     # 健康检查报告 (4 份)
    ├── refactor_report.md
    ├── coupling_report.md
    └── refactor_plan.md
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 100% |
| 架构 | MVVM + 命令路由模式 |
| 依赖注入 | Hilt (Dagger) — 5 个 Module |
| 命令系统 | Hilt Multibinding @IntoMap |
| 地图 | 高德 3D Map + POI Search |
| 语音 | 百度 ASR/TTS SDK |
| LLM | OpenAI 兼容 API (DeepSeek Function Calling) |
| 推理 | ONNX Runtime (YOLOv8) |
| 相机 | CameraX |
| 网络 | OkHttp + Retrofit |
| 数据库 | Room + Flow |
| 配置 | assets JSON + AppConfigProvider |
| minSdk | 24 (Android 7.0) |

## 快速开始

1. 用 Android Studio 打开项目，等待 Gradle 同步
2. 确认 `app/libs/bdasr.aar` 存在（百度语音 SDK）
3. 在 `local.properties` 中配置：
   ```properties
   amap.api.key=你的高德Key
   preview.base.url=http://你的预览服务地址
   ```
4. 连接手机（USB 调试），点击 Run

## 文档

| 文档 | 说明 |
|------|------|
| [docs/health_check/summary.md](docs/health_check/summary.md) | 健康检查综合报告 |
| [docs/health_check/01_command_routing.md](docs/health_check/01_command_routing.md) | 命令路由系统检查 |
| [docs/health_check/02_voice_system.md](docs/health_check/02_voice_system.md) | 语音系统检查 |
| [docs/health_check/03_main_activity.md](docs/health_check/03_main_activity.md) | MainActivity 检查 |
| [docs/health_check/04_config_data_navigation.md](docs/health_check/04_config_data_navigation.md) | 配置/数据/导航检查 |
| [docs/refactor_report.md](docs/refactor_report.md) | 重构结项报告 |
| [docs/coupling_report.md](docs/coupling_report.md) | 硬编码与耦合度调研 |
| [docs/refactor_plan.md](docs/refactor_plan.md) | 架构优化方案 |

## 调试

Logcat 标签过滤：
- `VoiceInteractionManager` — 语音交互全流程
- `VoiceCommandInterpreter` — 关键词匹配
- `CommandRouter` — 命令路由
- `BaiduSpeechManager` — 语音识别
- `BaiduTtsManager` — 语音合成
- `NavigationManager` — 导航
- `VisionTest` — 避障检测
- `TripPreviewService` — 行前预览
- `MenuConfig` — 菜单加载
