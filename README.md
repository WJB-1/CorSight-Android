# 🗺️ 语音导航 - Voice Navigation

基于 Android 平台的**语音步行导航应用**，支持语音输入目的地、POI 搜索选点、实时步行导航、偏航自动重规划以及 TTS 语音播报。

---

## ✨ 功能特性

| 功能 | 说明 |
|:---|:---|
| 🎤 **语音输入目的地** | 点击麦克风按钮说话，自动识别并填入搜索框 |
| 🔍 **POI 搜索选点** | 输入关键字搜索地点，弹窗列表选择目的地 |
| 🚶 **步行路线规划** | 高德步行导航，在地图上绘制蓝色路线 |
| 📍 **实时位置跟踪** | 导航过程中每 3 秒更新位置和剩余距离 |
| 🔄 **偏航自动重规划** | 偏离路线超过 50 米自动重新规划 |
| 🔊 **TTS 语音播报** | 导航指令通过百度语音合成实时播报 |
| 🏁 **到达提醒** | 距离目的地 20 米内自动提示到达 |
| 📋 **导航历史记录** | 已完成的导航记录保存并可查看 |
| 📱 **底部导航栏** | 导航 / 历史 / 设置 三页面快捷切换 |

---

## 📸 界面预览

```
┌──────────────────────────────┐
│  🎤 输入目的地  🔍 搜索  🚶  │  ← 顶部操作栏
├──────────────────────────────┤
│                              │
│        🗺️ 高德地图           │  ← 地图区域
│                              │
│     ● 当前位置                │
│     ⬤ 目的地 (蓝色标记)       │
│     ━━━ 步行路线 (蓝色折线)    │
│                              │
│  ┌──────────────────────┐    │
│  │ 剩余 1.2km  约 15 分钟 │    │  ← 导航信息面板
│  │ 沿当前道路向西出发     │    │
│  └──────────────────────┘    │
├──────────────────────────────┤
│  📍 导航   📋 历史   ⚙️ 设置 │  ← 底部导航栏
└──────────────────────────────┘
```

---

## 🛠️ 技术栈

| 技术 | 用途 |
|:---|:---|
| **高德 3D Map SDK 9.7.0** | 地图显示 + 定位服务 |
| **高德 Search SDK 9.7.0** | POI 搜索 + 步行路线规划 |
| **百度语音 SDK (bdasr.aar)** | 语音识别 (STT) |
| **百度语音合成 REST API** | 语音播报 (TTS) |
| **Room 2.6.1** | 本地导航历史记录存储 |
| **OkHttp 4.12.0** | 行前预览网络请求 |
| **Material Components 1.11.0** | 底部导航栏 UI |
| **AppCompat 1.6.1** | AndroidX 兼容支持 |
| **ConstraintLayout 2.1.4** | 页面布局 |
| **AGP 8.7.2 + Gradle 8.10** | 构建系统 |
| **minSdk 24 / targetSdk 34** | 支持 Android 7.0+ |

---

## 📂 项目结构

```
app/
├── libs/
│   └── bdasr.aar                      # 百度语音识别 SDK
├── src/main/
│   ├── assets/
│   │   └── asr_param.json             # 语音识别配置
│   ├── kotlin/                        # Kotlin 目录（预留，当前为空）
│   ├── java/com/example/voicenavigation/
│   │   ├── MainActivity.java          # 主界面（协调所有模块）
│   │   ├── data/
│   │   │   ├── AppDatabase.java       # Room 数据库
│   │   │   ├── VoiceRecord.java       # 导航记录实体
│   │   │   ├── VoiceRecordDao.java    # 数据访问层
│   │   │   └── VoiceRecordAdapter.java # 历史列表适配器
│   │   ├── navigation/
│   │   │   └── NavigationManager.java # 导航引擎（定位+规划+偏航检测）
│   │   ├── network/
│   │   │   └── TripPreviewService.java # 行前预览网络请求（OkHttp）
│   │   └── stt/
│   │       ├── BaiduSpeechManager.java       # 语音识别管理（百度 SDK）
│   │       ├── BaiduTtsManager.java          # 语音合成（百度 REST API）
│   │       ├── SpeechRecognitionManager.java # 原生 Android SpeechRecognizer（已弃用，保留参考）
│   │       └── SpeechRecognitionService.java # 原生语音识别服务（已弃用，保留参考）
│   ├── res/
│   │   ├── layout/                     # 页面布局 XML
│   │   ├── values/                     # 颜色、字符串、主题
│   │   ├── menu/                       # 底部导航菜单
│   │   └── color/                      # 导航栏颜色选择器
│   └── AndroidManifest.xml            # 应用配置
├── build.gradle                       # 模块构建配置
└── settings.gradle                    # 仓库管理
```

---

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog 或更高版本
- JDK 17+
- Android SDK 34
- 一台 Android 7.0+ 真机（推荐）或模拟器

> ⚠️ **重要提示**：高德地图 API Key 需要和应用的包名 (`com.example.voicenavigation`) + 调试证书 SHA1 绑定。如果地图显示空白，请在高德开放平台配置你的 SHA1。

### 运行步骤

1. **克隆项目**
   ```bash
   git clone https://github.com/weiyu66/voiceNavigation.git
   ```

2. **用 Android Studio 打开**项目根目录，等待 Gradle 同步完成

3. **检查本地 AAR 依赖**
   
   百度语音识别 SDK 通过 `app/libs/bdasr.aar` + `flatDir` 本地引入。如果 Gradle 同步时报找不到 `bdasr` 依赖，请确认 AAR 文件存在于 `app/libs/` 目录下。

4. **配置 API Key**
   
   项目已内置测试 Key（位于 `res/values/strings.xml`），可直接运行。如需替换：
   
   | 服务 | 申请地址 | 配置位置 |
   |:---|:---|:---|
   | 高德地图 | [lbs.amap.com](https://lbs.amap.com) | `strings.xml` → `amap_api_key` |
   | 百度语音 | [ai.baidu.com](https://ai.baidu.com) | `strings.xml` → `baidu_speech_*` |

5. **连接手机**（开启 USB 调试），点击 **Run** ▶️

---

## 🔑 权限说明

| 权限 | 用途 |
|:---|:---|
| `RECORD_AUDIO` | 语音输入目的地 |
| `INTERNET` | 地图加载、POI 搜索、TTS 语音合成 |
| `ACCESS_FINE_LOCATION` | GPS 精确定位和导航 |
| `ACCESS_COARSE_LOCATION` | 网络辅助定位 |
| `ACCESS_NETWORK_STATE` | 检测网络连接状态 |
| `POST_NOTIFICATIONS` | 通知权限（Android 13+） |

---

## 🧭 使用指南

```
1. 语音输入
   点击 🎤 按钮 → 说话 → 自动识别填入输入框 → 自动搜索

2. 手动搜索
   在输入框输入目的地 → 点击"搜索"或按键盘回车

3. 选择目的地
   从搜索结果列表中选择 → 地图标记蓝色位置标记

4. 开始导航
   点击"开始导航" → 规划步行路线 → 实时语音引导
   → 走错路自动重新规划 → 到达自动提醒
```

---

## 🧪 调试

查看应用日志（Logcat）的标签：

| 标签 | 模块 |
|:---|:---|
| `MainActivity` | 主界面交互、TTS 播报、历史记录 |
| `BaiduSpeechManager` | 语音识别事件流 |
| `BaiduTtsManager` | 语音合成 Token 和播放 |
| `NavigationManager` | 定位、路线规划、偏航检测 |

---

## 📋 待改进

- [ ] 支持驾车/公交/骑行导航模式
- [ ] 导航路线支持多条选择
- [ ] 地图选点功能（点击地图指定目的地）
- [ ] 历史记录删除和清空功能
- [ ] 导航语音播报前添加提示音
- [ ] 深色模式支持

---

## 📄 许可

本项目仅供个人学习使用。

---

## 🙏 致谢

- [高德开放平台](https://lbs.amap.com/) — 地图和导航服务
- [百度 AI 开放平台](https://ai.baidu.com/) — 语音识别和合成服务
