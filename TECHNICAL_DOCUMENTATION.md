# 语音导航应用技术文档

## 1. 项目概述

### 1.1 项目简介

本项目是一个基于 Android 平台的**语音导航应用**，支持语音输入目的地、POI 搜索选点、步行路线规划、实时导航（含偏航重规划）、TTS 语音播报以及历史记录管理。

### 1.2 核心功能

| 功能模块 | 功能描述 |
| :--- | :--- |
| 语音输入 | 百度语音识别 SDK 实现语音转文字 |
| POI 搜索 | 高德搜索 API，支持关键字搜索 + 列表选点 |
| 路线规划 | 高德步行路线规划，在地图上绘制蓝色路线折线 |
| 实时导航 | 高德定位 SDK 实时跟踪位置，显示剩余距离/时间 |
| 偏航重规划 | 偏离路线 > 50m 自动重新规划 |
| TTS 播报 | 百度语音合成 REST API，播报导航指令 |
| 历史记录 | Room 数据库保存导航历史，支持列表查看 |
| 设置页面 | 查看当前配置的 API Key 信息 |
| 底部导航栏 | 导航/历史/设置 三 tab 切换 |

### 1.3 技术栈

| 技术 | 用途 |
| :--- | :--- |
| 高德 3D Map SDK 9.7.0 | 地图显示 + 定位 |
| 高德 Search SDK 9.7.0 | POI 搜索 + 路线规划 |
| 百度语音 SDK (bdasr.aar) | 语音识别 (STT) |
| 百度语音合成 REST API | 语音播报 (TTS) |
| Room 2.6.1 | 本地数据持久化 |
| Material Components 1.11.0 | 底部导航栏 (BottomNavigationView) |
| AGP 8.7.2 + Gradle 8.10 | 构建系统 |

---

## 2. 项目结构

### 2.1 文件清单

```
app/
├── libs/
│   └── bdasr.aar                          # 百度语音识别 SDK
├── src/main/
│   ├── assets/
│   │   └── asr_param.json                 # 语音识别配置参数
│   ├── java/com/example/voicenavigation/
│   │   ├── MainActivity.java              # 主界面，协调所有模块
│   │   ├── data/
│   │   │   ├── AppDatabase.java           # Room 数据库
│   │   │   ├── VoiceRecord.java           # 导航记录实体
│   │   │   ├── VoiceRecordDao.java        # 数据访问接口
│   │   │   └── VoiceRecordAdapter.java    # 历史列表适配器
│   │   ├── navigation/
│   │   │   └── NavigationManager.java     # 导航管理（定位+路线规划+偏航检测）
│   │   ├── network/
│   │   │   └── TripPreviewService.java    # 行前预览网络请求（OkHttp）
│   │   └── stt/
│   │       ├── BaiduSpeechManager.java    # 百度语音识别管理
│   │       ├── BaiduTtsManager.java       # 百度语音合成（REST API）
│   │       └── SpeechRecognitionManager.java # （旧）原生语音，已弃用
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml          # 主界面布局（含容器页）
│   │   │   ├── page_history.xml           # 历史记录页
│   │   │   └── page_settings.xml          # 设置页
│   │   ├── menu/
│   │   │   └── menu_main_bottom.xml       # 底部导航菜单项
│   │   ├── color/
│   │   │   └── bottom_nav_color.xml       # 导航栏选中/未选中颜色
│   │   └── values/
│   │       ├── colors.xml                 # 颜色定义
│   │       ├── strings.xml                # 字符串 + API Key 配置
│   │       └── themes.xml                 # 主题
│   └── AndroidManifest.xml                # 权限 + 组件声明
├── build.gradle                           # 模块构建配置
├── build.gradle                           # 根构建配置（AGP 8.7.2）
└── settings.gradle                        # 仓库管理（FAIL_ON_PROJECT_REPOS）
```

### 2.2 分层架构

```
┌─────────────────────────────────────────────────────┐
│                    UI 层                              │
│   activity_main.xml (地图/导航面板)                   │
│   page_history.xml (历史记录 RecyclerView)            │
│   page_settings.xml (API Key 信息)                   │
│   BottomNavigationView (三 tab 切换)                 │
│   MainActivity (页面切换 + 事件绑定)                   │
├─────────────────────────────────────────────────────┤
│                  业务逻辑层                            │
│   BaiduSpeechManager  (语音识别)                      │
│   BaiduTtsManager     (语音合成)                      │
│   NavigationManager   (定位+路线规划+偏航检测)          │
│   TripPreviewService  (行前预览网络请求)                │
├─────────────────────────────────────────────────────┤
│                  数据层                               │
│   AppDatabase → VoiceRecordDao → VoiceRecord         │
│   VoiceRecordAdapter → RecyclerView                  │
├─────────────────────────────────────────────────────┤
│                  外部服务                              │
│   高德地图SDK | 高德搜索SDK | 百度语音SDK | 百度TTS API │
└─────────────────────────────────────────────────────┘
```

---

## 3. 关键技术实现

### 3.1 语音识别模块（BaiduSpeechManager）

**文件：** [BaiduSpeechManager.java](file:///d:/Program%20Files/Android/AndroidStudioProjects/V0/app/src/main/java/com/example/voicenavigation/stt/BaiduSpeechManager.java)

**SDK 方式：** 本地 AAR（`app/libs/bdasr.aar`），通过 `flatDir` 引入

**核心流程：**

```
BaiduSpeechManager 初始化
  → EventManagerFactory.create(context, "asr")
  → registerListener(EventListener)
       ↓
startListening()
  → 设置参数 (VAD_TOUCH / WP_VAD_ENABLE=false / VAD_ENDPOINT_TIMEOUT=0)
  → 发送 ASR_START
  → 8秒自动超时停止
       ↓
EventListener.onEvent(String name, String params)
  ├─ CALLBACK_EVENT_ASR_READY    : SDK 准备就绪
  ├─ CALLBACK_EVENT_ASR_PARTIAL  : 中间结果 → onPartialResult()
  ├─ CALLBACK_EVENT_ASR_FINISH   : 最终结果 → onResult()
  ├─ CALLBACK_EVENT_ASR_ERROR    : 识别错误 → onError()
  └─ CALLBACK_EVENT_ASR_EXIT     : 退出
```

**关键问题解决：**

| 问题 | 解决方案 |
| :--- | :--- |
| `RecognizerListener` 不存在 | SDK 实际接口是 `EventListener`，改用 Lambda 直接实现 |
| `VAD_DNN` 模型文件缺失 | 改用 `VAD_TOUCH` + 应用层 8 秒超时自动停止 |
| `OFFLINE_ASR_ENABLE` 常量不存在 | V3 版 SDK 无此常量，移除该配置 |
| 多次触发 `onResult` | 增加 `resultDelivered` 标志防止重复 |
| partial/final 分离 | 增加 `onPartialResult`/`onResult` 两个回调区分 |

**API Key 配置：**

```xml
<!-- strings.xml -->
<string name="baidu_speech_app_id">7669507</string>
<string name="baidu_speech_api_key">eT9Q9hXnZt0nFYdAmAsC6d69</string>
<string name="baidu_speech_secret_key">jKemN6xBxVfMlscTeyLsovXG9PhWblSS</string>
```

### 3.2 语音合成模块（BaiduTtsManager）

**文件：** [BaiduTtsManager.java](file:///d:/Program%20Files/Android/AndroidStudioProjects/V0/app/src/main/java/com/example/voicenavigation/stt/BaiduTtsManager.java)

**方式：** 百度语音合成 REST API（HTTP），无需额外 SDK

**工作流程：**

```
init()
  → POST https://openapi.baidu.com/oauth/2.0/token
    ?grant_type=client_credentials
    &client_id=API_KEY
    &client_secret=SECRET_KEY
  → 获取 access_token（有效期30天，缓存在内存中）

speak("前方500米右转")
  → 新线程 POST https://tsn.baidu.com/text2audio
    Content-Type: application/x-www-form-urlencoded
    tex=前方500米右转&lan=zh&ctp=1&tok=ACCESS_TOKEN
    &per=0&spd=5&pit=5&vol=15
  → 返回 MP3 音频 (Content-Type: audio/mp3)
  → 写入 cache/baidu_tts_temp.mp3
  → MediaPlayer 播放
```

**参数说明：**

| 参数 | 值 | 说明 |
| :--- | :--- | :--- |
| `per` | 0 | 发音人：女声 |
| `spd` | 5 | 语速 0~15 |
| `pit` | 5 | 音调 0~15 |
| `vol` | 15 | 音量 0~15 |

**设计决策：** 系统 TTS 在 MIUI 上返回 `status=-1` 不可用，因此选择百度在线 TTS 作为主要方案。

### 3.3 导航定位模块（NavigationManager）

**文件：** [NavigationManager.java](file:///d:/Program%20Files/Android/AndroidStudioProjects/V0/app/src/main/java/com/example/voicenavigation/navigation/NavigationManager.java)

**SDK：** 高德定位 SDK（`com.amap.api:3dmap:9.7.0`）

**路线模式：** 步行（`RouteSearch.WalkRouteQuery` / `RouteSearch.WalkDefault`）

**核心能力：**

| 能力 | 实现 |
| :--- | :--- |
| 定位 | `AMapLocationClient`，3 秒间隔，高精度模式 |
| 路线规划 | `RouteSearch.calculateWalkRouteAsyn()` |
| 偏航检测 | 距路线最近点距离 > 50m 触发 |
| 重规划 | `triggerReroute()` → 从当前位置到目的地重新规划 |
| 到达检测 | 剩余距离 < 20m 自动停止导航 |

**步行路线数据结构：**

| 驾车（旧） | 步行（当前） |
| :--- | :--- |
| `DriveRouteQuery` | `WalkRouteQuery` |
| `DriveRouteResult` | `WalkRouteResult` |
| `DrivePath` | `WalkPath` |
| `DriveStep` | `WalkStep` |
| `calculateDriveRouteAsyn()` | `calculateWalkRouteAsyn()` |
| `onDriveRouteSearched()` | `onWalkRouteSearched()` |

**权限合规（必须优先调用）：**

```java
AMapLocationClient.updatePrivacyShow(context, true, true);
AMapLocationClient.updatePrivacyAgree(context, true);
// 必须在 new AMapLocationClient() 之前调用
```

**AndroidManifest 必需配置：**

```xml
<!-- API Key -->
<meta-data
    android:name="com.amap.api.v2.apikey"
    android:value="@string/amap_api_key" />

<!-- 定位服务 -->
<service
    android:name="com.amap.api.location.APSService"
    android:exported="false" />
```

**回调接口 NavigationCallback：**

| 方法 | 触发时机 |
| :--- | :--- |
| `onLocationUpdated(Location)` | 每次定位更新 |
| `onRouteReady(points, dist, dur, instructions)` | 路线规划完成 |
| `onNavigationInfoUpdated(remainDist, remainDur, nextInstr)` | 每 3 秒导航更新 |
| `onReRouting()` | 检测到偏航 |
| `onArrived()` | 到达目的地 |
| `onNavigationStarted()` | 导航开始 |
| `onNavigationStopped()` | 导航停止 |
| `onNavigationError(String)` | 发生错误 |

### 3.4 POI 搜索模块

**SDK：** 高德搜索 SDK（`com.amap.api:search:9.7.0`）

**工作流程：**

```
searchDestination("北京西站")
  → PoiSearch.Query(keyword="北京西站", pageSize=20)
  → PoiSearch.searchPOIAsyn()
       ↓
  onPoiSearched(PoiResult, rCode=1000)
  → showPoiResultsDialog(List<PoiItem>)
  → AlertDialog 列表显示 (title + cityName + adName)
       ↓
  用户选择 → setDestination(LatLng, name)
  → 地图标记蓝色 Marker
  → animateCamera 到该位置
```

### 3.5 数据持久化（Room）

**数据库：** `voice_navigation.db`

| 表 | 说明 |
| :--- | :--- |
| `voice_records` | 导航历史记录（点击"开始导航"时写入） |

**VoiceRecord 实体：**

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | int (PK, autoGenerate) | 主键 |
| `content` | String | 目的地名称 |
| `filePath` | String | 保留字段（空字符串） |
| `timestamp` | long | 记录时间戳（导航开始时间） |
| `destination` | String | 目的地的完整名称 |

**写入时机：** 在 `toggleNavigation()` 点击"开始导航"时调用 `saveVoiceRecord()` 写入，而不是在语音识别时保存。

---

## 4. 主界面交互流程

### 4.1 布局结构

```
CoordinatorLayout (activity_main.xml)
├── FrameLayout (内容容器, weight=1)
│   ├── LinearLayout page_map (导航页)
│   │   ├── LinearLayout (顶部栏 - 紫色背景)
│   │   │   ├── TextView tv_status (状态提示)
│   │   │   └── LinearLayout (操作栏 - horizontal)
│   │   │       ├── EditText et_destination (输入框)
│   │   │       ├── Button btn_search (搜索)
│   │   │       ├── Button btn_voice_input (语音)
│   │   │       └── Button btn_start_navigation (导航)
│   │   ├── MapView (高德地图, weight=1)
│   │   └── LinearLayout layout_nav_info (导航面板)
│   │       ├── tv_nav_distance (剩余距离)
│   │       ├── tv_nav_duration (剩余时间)
│   │       └── tv_nav_instruction (下一步操作提示)
│   ├── LinearLayout page_history (历史页)
│   │   ├── TextView tv_history_empty (空状态提示)
│   │   └── RecyclerView rv_history (历史列表)
│   └── LinearLayout page_settings (设置页)
│       └── TextView tv_amap_key (API Key 信息)
└── BottomNavigationView (底部导航栏)
    ├── nav_tab_nav (导航)
    ├── nav_tab_history (历史)
    └── nav_tab_settings (设置)
```

### 4.2 完整交互流程

```
1. 语音输入
   ┌─────────────────────────────────────────┐
   │ 点击🎤 → 开始录音 → 说话 → 8秒自动停止    │
   │ 说话过程中：实时显示中间文字(partial)      │
   │ 停止后：输入框填入最终文字 → 自动POI搜索   │
   └─────────────────────────────────────────┘

2. 手动搜索
   ┌─────────────────────────────────────────┐
   │ 输入"北京西站" → 点击"搜索"按钮           │
   │ 或 输入"北京西站" → 键盘回车              │
   │ → PoiSearch → 弹出候选列表               │
   └─────────────────────────────────────────┘

3. 开始导航
   ┌─────────────────────────────────────────┐
   │ 点击"开始导航"                            │
   │ → 检查是否已选目的地 + 已有当前位置         │
   │ → 写入历史记录 (saveVoiceRecord)          │
   │ → RouteSearch 步行路线规划                 │
   │ → 蓝色路线折线绘制到地图上                  │
   │ → 底部面板显示：距离/时间/下个指令           │
   │ → TTS播报第一条指令                       │
   │ → 每3秒更新位置和进度                      │
   │ → 偏航 >50m → 自动重规划                   │
   │ → 到达 <20m → "已到达目的地附近"            │
   └─────────────────────────────────────────┘

4. 页面切换 (底部导航栏)
   ┌─────────────────────────────────────────┐
   │ 点击"导航" → 显示地图页 (默认页)           │
   │ 点击"历史" → loadHistory() → 展示导航记录  │
   │ 点击"设置" → 显示 API Key 配置信息         │
   └─────────────────────────────────────────┘
```

---

## 5. 构建配置

### 5.1 Gradle 配置

| 配置项 | 值 |
| :--- | :--- |
| AGP | 8.7.2 |
| Gradle | 8.10 |
| compileSdk | 34 |
| minSdk | 24 |
| targetSdk | 34 |

### 5.2 依赖说明

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://maven.aliyun.com/repository/public' }
        flatDir { dirs "${rootProject.projectDir}/app/libs" }
    }
}

dependencies {
    implementation 'com.amap.api:3dmap:9.7.0'      // 高德3D地图
    implementation 'com.amap.api:search:9.7.0'      // 高德搜索
    implementation 'androidx.room:room-runtime:2.6.1'
    annotationProcessor 'androidx.room:room-compiler:2.6.1'
    implementation 'com.google.android.material:material:1.11.0' // 底部导航栏
    implementation 'com.squareup.okhttp3:okhttp:4.12.0' // 行前预览网络请求
    implementation(name: 'bdasr', ext: 'aar')       // 百度语音SDK
}
```

### 5.3 权限

| 权限 | 用途 |
| :--- | :--- |
| `RECORD_AUDIO` | 语音识别录音 |
| `INTERNET` | 地图、语音API、TTS |
| `ACCESS_FINE_LOCATION` | GPS 精确定位 |
| `ACCESS_COARSE_LOCATION` | 网络粗略定位 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `POST_NOTIFICATIONS` | 通知权限 |

---

## 6. 行前预览模块（TripPreviewService）

**文件：** [`TripPreviewService.java`](app/src/main/java/com/example/voicenavigation/network/TripPreviewService.java)

**SDK：** OkHttp 4.12.0

**工作流程：**

```
new TripPreviewService(baseUrl)
  → sendPreviewRequest(originLat, originLng, destLat, destLng, callback)
    → 构建 JSON 请求体 { origin: {latitude, longitude}, destination: {latitude, longitude} }
    → POST {baseUrl}/api/trip/preview
    → 异步回调（主线程）
      ├─ onSuccess(String response)  : HTTP 200，返回后端 JSON
      └─ onError(String error)       : 网络失败 / 服务器错误
```

**关键配置：**

| 配置项 | 值 | 说明 |
| :--- | :--- | :--- |
| `CONNECT_TIMEOUT` | 15 秒 | 连接超时 |
| `READ_TIMEOUT` | 15 秒 | 读取超时 |
| `Content-Type` | `application/json` | 请求体格式 |

**后端接口约定：**

| 项目 | 内容 |
| :--- | :--- |
| 请求方法 | `POST` |
| 请求路径 | `/api/trip/preview` |
| 请求体 | JSON，包含 `origin` 和 `destination` 的经纬度 |
| 成功响应 | HTTP 200，JSON 格式 `{ code, message, data }` |

**调试标签：** `TripPreviewService`

**相关文档：** [`docs/TRIP_PREVIEW_API.md`](docs/TRIP_PREVIEW_API.md)

---

## 7. 已知限制

| 限制 | 说明 |
| :--- | :--- |
| 仅有步行模式 | 不支持驾车/公交/骑行导航模式 |
| TTS 需要网络 | 百度在线 TTS，无网络则不播报 |
| 单路线选择 | 默认使用第一条推荐路线 |
| 无导航语音提示音 | 播报前无"叮"提示音 |
| 历史记录不可操作 | 仅可查看列表，不支持删除/清空 |
| 定位受高德 API Key 绑定 | Key 需和高德平台注册的包名+SHA1匹配 |
| 行前预览需后端服务 | 需要独立部署后端或配置第三方 API |

---

## 8. 版本信息

| 项目信息 | 值 |
| :--- | :--- |
| 包名 | `com.example.voicenavigation` |
| 最小 SDK | 24 (Android 7.0) |
| 目标 SDK | 34 (Android 14) |
| 编译 SDK | 34 |
| 版本号 | 1.0 |
| 高德 API Key | `c9da34e66d08cf5bac59a307276db812` |
| 百度 App ID | `7669507` |

---

## 9. 调试指南

### 8.1 Logcat 标签

| 标签 | 模块 | 用途 |
| :--- | :--- | :--- |
| `MainActivity` | 主界面 | 界面交互、TTS 播报、历史记录读写 |
| `BaiduSpeechManager` | 语音识别 | STT 事件流、ASR 错误 |
| `BaiduTtsManager` | 语音合成 | Token获取、合成结果 |
| `NavigationManager` | 导航 | 定位、路线规划、偏航检测 |
| `TripPreviewService` | 行前预览 | 网络请求、响应日志 |
| `ASREngine` | 百度SDK | VAD/引擎内部日志 |

### 8.2 常见问题

| 现象 | 检查点 |
| :--- | :--- |
| 地图不显示 | API Key 是否注册？包名+SHA1 是否匹配？隐私合规是否调用？ |
| 定位失败 | AndroidManifest 中 APSService 是否声明？定位权限是否授予？ |
| 语音识别无结果 | VAD 模式是否正确？8秒超时是否触发？是否有网络？ |
| TTS 无声 | 媒体音量是否开启？百度 access_token 是否获取成功？ |
| 历史无内容 | 是否点击了"开始导航"？布局文件 `rv_history` 是否设置了 `LinearLayoutManager`？ |
| 底部导航栏崩溃 | 是否移除了 Material3 的 `style` 引用？是否使用 `com.google.android.material:material:1.11.0`？ |
