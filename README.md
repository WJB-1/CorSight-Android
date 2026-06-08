# CorSight Android

视障语音导航 Android 客户端。

## 项目结构

```
CorSight-Android/
├── app/
│   ├── src/main/java/com/example/voicenavigation/
│   │   ├── MainActivity.java              # 主界面
│   │   ├── data/                          # Room 数据库（导航历史）
│   │   ├── navigation/                    # 导航引擎
│   │   ├── network/
│   │   │   └── TripPreviewService.java    # 行前预览 HTTP 客户端
│   │   └── stt/                           # 百度语音 SDK 封装
│   ├── src/main/res/                      # 布局、资源、主题
│   └── build.gradle                       # 模块构建配置
├── build.gradle
├── settings.gradle
└── docs/
    └── TRIP_PREVIEW_API.md                # 后端接口联调文档
```

## 功能

- 语音输入目的地（百度语音识别）
- POI 搜索与地图选点（高德地图 SDK）
- 实时步行导航 + 偏航自动重规划
- TTS 语音播报（百度语音合成）
- 导航历史记录（Room 本地存储）
- **行前路线预览**（调用 CorSight-Server 后端）

## 技术栈

| 技术 | 用途 |
|:---|:---|
| 高德 3D Map SDK 9.7.0 | 地图显示 + 定位 + POI 搜索 |
| 高德 Search SDK 9.7.0 | 步行路线规划 |
| 百度语音 SDK (bdasr.aar) | 语音识别 (STT) |
| 百度语音合成 REST API | 语音播报 (TTS) |
| Room 2.6.1 | 本地导航历史 |
| OkHttp 4.12.0 | 行前预览网络请求 |
| minSdk 24 / targetSdk 34 | Android 7.0+ |

## 快速开始

### 环境要求

- Android Studio Hedgehog+
- JDK 17+
- Android SDK 34
- Android 7.0+ 真机或模拟器

### 运行步骤

1. **用 Android Studio 打开**项目根目录，等待 Gradle 同步

2. **检查 AAR 依赖**
   ```
   app/libs/bdasr.aar   # 百度语音识别 SDK
   ```
   如缺失，从[百度 AI 开放平台](https://ai.baidu.com/)下载。

3. **配置 API Key**

   在 `app/src/main/res/values/strings.xml` 中配置：
   ```xml
   <string name="amap_api_key">你的高德 Key</string>
   ```

   > 高德 Key 需要和包名 `com.example.voicenavigation` + 调试证书 SHA1 绑定。

4. **配置后端地址**

   打开 `app/src/main/java/com/example/voicenavigation/network/TripPreviewService.java`，
   修改第 65 行：
   ```java
   public static final String DEFAULT_BASE_URL = "https://your-backend-domain.com";
   ```

   | 环境 | 地址示例 |
   |------|----------|
   | 生产环境 | `"https://api.example.com"` |
   | 模拟器调试本机后端 | `"http://10.0.2.2:3002"` |
   | 真机局域网调试 | `"http://192.168.1.x:3002"` |

5. **连接手机**（开启 USB 调试），点击 **Run**

## 权限

| 权限 | 用途 |
|:---|:---|
| `RECORD_AUDIO` | 语音输入 |
| `INTERNET` | 地图、搜索、TTS、后端请求 |
| `ACCESS_FINE_LOCATION` | GPS 定位导航 |
| `ACCESS_COARSE_LOCATION` | 网络辅助定位 |

## 相关仓库

- [CorSight-Server](https://github.com/你的用户名/CorSight-Server) — 后端服务（语义地图 + 智能导航）

## 调试

Logcat 标签过滤：
- `TripPreviewService` — 行前预览网络请求
- `NavigationManager` — 定位、路线规划、偏航检测
- `BaiduSpeechManager` — 语音识别
- `BaiduTtsManager` — 语音合成
