# CorSight Android v2.0

瞳心引航 — 面向盲人/视障用户的语音导航 + 视觉避障 Android 应用。

## 功能

- 语音输入目的地（百度 ASR）+ 语音助手（本地关键词 + LLM Function Calling 混合架构）
- POI 搜索与地图选点（高德地图 SDK）
- 实时步行导航 + 偏航自动重规划
- TTS 语音播报（百度 TTS）
- 视觉避障（本地 YOLOv8 ONNX 推理 / 云端 HTTP 推理）
- ESP32 外设自动发现（UDP 广播 + Socket 网络流）
- 行前路线预览（调用 CorSight-Server 后端）
- 8 方向街景数据采集 + 云端上传
- 导航历史记录（Room 本地存储）

## 项目结构

```
CorSight-Android_v2.0/
├── app/                  # 主应用
│   ├── src/main/java/
│   │   └── com/example/voicenavigation/
│   │       ├── app/          # CorSightApp (@HiltAndroidApp)
│   │       ├── di/           # Hilt 依赖注入 (4 个 Module)
│   │       ├── data/         # Room + Repository
│   │       ├── voice/        # 语音子系统 (ASR/TTS/LLM)
│   │       ├── navigation/   # 高德步行导航
│   │       ├── network/      # 行前预览 HTTP
│   │       ├── collection/   # 数据采集
│   │       ├── ui/           # ViewModel + Fragment
│   │       └── ...
│   └── build.gradle
├── inference/            # 推理引擎库 (YOLOv8 ONNX)
├── vision/               # 视觉处理库 (ImageSource/VisionTool)
├── docs/                 # 项目文档
├── debug/                # 调试截图
├── build.gradle          # 根构建配置
├── settings.gradle       # 模块声明
└── ARCHITECTURE.md       # 架构文档 (详见此文件)
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 100% |
| 架构 | MVVM (ViewModel + StateFlow) |
| 依赖注入 | Hilt (Dagger) |
| 地图 | 高德 3D Map + POI Search |
| 语音 | 百度 ASR/TTS SDK |
| LLM | OpenAI 兼容 API (DeepSeek) |
| 推理 | ONNX Runtime (YOLOv8) |
| 相机 | CameraX |
| 网络 | OkHttp + Retrofit |
| 数据库 | Room + Flow |
| minSdk | 24 (Android 7.0) |

## 快速开始

1. 用 Android Studio 打开项目，等待 Gradle 同步
2. 确认 `app/libs/bdasr.aar` 存在（百度语音 SDK）
3. 在 `local.properties` 中配置高德 API Key：
   ```
   amap.api.key=你的高德Key
   ```
4. 连接手机（USB 调试），点击 Run

## 文档

| 文档 | 说明 |
|------|------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 详细架构文档、包结构、数据流、文件清单 |
| [docs/TRIP_PREVIEW_API.md](docs/TRIP_PREVIEW_API.md) | 行前预览后端接口联调文档 |
| [docs/TECHNICAL_DOCUMENTATION.md](docs/TECHNICAL_DOCUMENTATION.md) | 技术设计文档 |
| [docs/安卓开发入门笔记.md](docs/安卓开发入门笔记.md) | 开发入门笔记 |

## 调试

Logcat 标签过滤：
- `VoiceInteractionManager` — 语音交互全流程
- `BaiduSpeechManager` — 语音识别
- `BaiduTtsManager` — 语音合成
- `NavigationManager` — 导航
- `VisionTest` — 避障检测
- `TripPreviewService` — 行前预览
