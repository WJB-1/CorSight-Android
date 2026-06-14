# 街景采样架构全景分析

> 生成时间：2026-06-14
>
> 分析范围：`CorSight-Android_v2.0/app` 模块 `collection/` 包及相关入口、配置、布局

---

## 1. 项目背景

**CorSight（瞳心引航）** 是一款面向视障用户的 Android 应用，提供语音导航和障碍物检测。其中的**街景采样模块**用于在实地采集 8 方向全景照片，上传后端构建语义地图，供路线预览功能使用。

项目技术栈：100% Kotlin · Hilt DI · MVVM · Android 7.0+（SDK 24）

三个 Gradle 模块：

| 模块 | 职责 |
|---|---|
| `app/` | 主应用，包含全部 UI 和业务逻辑（街景采样在此） |
| `inference/` | YOLOv8 ONNX 推理引擎（障碍物检测，不涉及采样） |
| `vision/` | 视觉工具抽象层（障碍物检测，不涉及采样） |

---

## 2. 核心文件清单（`collection/` 包）

| 文件 | 职责 |
|---|---|
| `DataCollectionActivity.kt` | **主 Activity** — 串联定位、罗盘、拍照、预览、云同步全部流程 |
| `CaptureTask.kt` | 数据模型 — 一个采样点（pointId, chunkId, 经纬度, 8 张图, 状态） |
| `CompassService.kt` | 罗盘服务 — 加速度计 + 磁力计，指数平滑航向，8 方向对齐检测 |
| `GridUtils.kt` | 网格工具 — 经纬度 → Web Mercator 瓦片 ID（zoom 16） |
| `TaskStorage.kt` | 持久层 — SharedPreferences + JSON 序列化存储采样任务 |
| `UploadService.kt` | 上传服务 — OkHttp multipart 上传，含重试和缺失图片补传 |

---

## 3. 入口与路由

| 入口 | 路由方式 |
|---|---|
| **SettingsFragment** "数据采集" 按钮 | `startActivity(Intent(DataCollectionActivity))` |
| **环形菜单** "数据采集" 项 | `menu_config.json` → command: `"data_collection"` → `AppCommandHandler` → `CommandEvent.OpenDataCollection` |

相关文件：

- `ui/main/settings/SettingsFragment.kt` — 按钮绑定（line 50, 101-102）
- `command/AppCommandHandler.kt` — 命令路由（line 93-95）
- `command/CommandEvent.kt` — 事件定义 `OpenDataCollection`（line 28）
- `assets/menu_config.json` — 环形菜单配置（id: `"collect"`）

---

## 4. 数据流（5 个阶段）

```
┌─────────────────────────────────────────────────────────────────┐
│ ① GPS 定位                                                      │
│    AMapLocationClient（高精度模式）→ 经纬度回调                    │
│    → GridUtils.getChunkId(lat, lon, zoom=16)                    │
│    → chunk ID 格式: "{zoom}_{x}_{y}" (e.g. "16_55678_28543")    │
├─────────────────────────────────────────────────────────────────┤
│ ② 罗盘引导方向对齐                                               │
│    CompassService → 加速度计 + 磁力计                             │
│    → SensorManager.getRotationMatrix() + getOrientation()       │
│    → 指数平滑（α=0.1）+ 跳变拒绝（120°）                          │
│    → 映射到 8 方向（每 45°一档）                                   │
│    → isAligned() ±8°容差 → 振动提示 → 启用拍摄按钮                │
├─────────────────────────────────────────────────────────────────┤
│ ③ 拍照采集 8 方向                                                │
│    方向顺序: N → NE → E → SE → S → SW → W → NW                  │
│    用户物理旋转 → 罗盘对齐 → 点击"拍摄"                           │
│    → Camera Intent + FileProvider                                │
│    → capture_{timestamp}.jpg → filesDir                          │
│    → capturedStatus Map 跟踪完成状态                              │
│    → 全部拍完 → showPreviewDialog()（可重拍单方向）                │
├─────────────────────────────────────────────────────────────────┤
│ ④ 本地存储                                                      │
│    CaptureTask(                                                │
│      pointId = "P_{timestamp}_{random5}",                      │
│      chunkId, latitude, longitude,                             │
│      sceneDescription (默认"未描述", max 50字符),                │
│      images = Map<direction, filePath>,                        │
│      status = "pending"                                        │
│    )                                                           │
│    → TaskStorage.saveTask() → SharedPreferences                │
│      文件: "capture_tasks" · Key: "semantic_map_tasks"         │
│      格式: JSON 数组                                            │
├─────────────────────────────────────────────────────────────────┤
│ ⑤ 云端上传                                                      │
│    用户点击"同步到云端" → 确认待上传数量                            │
│    → 读取 serverBaseUrl (SharedPreferences)                     │
│    → UploadService.uploadTask():                               │
│       Step A: POST {baseUrl}/api/upload/sampling_point          │
│              multipart: jsonData + 8 张 JPEG                    │
│       Step B: 解析响应，检查 data.images.{direction}             │
│       Step C: 缺失方向 → POST {baseUrl}/api/upload/image 补传   │
│    → 重试: 最多 3 次，间隔 2s                                    │
│    → 超时: connect 15s / read 60s / write 60s                   │
│    → TaskStorage.updateStatus(pointId, "success"|"failed")      │
│    → clearSuccessTasks() 清理已完成任务                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. 架构图

```
┌─ 入口 ─────────────────────────────────────────────────────────┐
│  SettingsFragment · Ring Menu · Voice Command                  │
│         ↓ startActivity / CommandEvent.OpenDataCollection      │
├─ DataCollectionActivity (UI + 全流程编排) ─────────────────────┤
│  │                                                             │
│  ├─ AMapLocationClient → GPS                                  │
│  │     └→ GridUtils.getChunkId() → chunk ID                   │
│  │                                                             │
│  ├─ CompassService → 平滑航向 → 8 方向对齐检测                  │
│  │     └→ callback(heading, direction, isAligned)              │
│  │                                                             │
│  ├─ Camera Intent → capture_{ts}.jpg (FileProvider)            │
│  │                                                             │
│  ├─ Preview Dialog → 8 缩略图 + 场景描述输入                    │
│  │                                                             │
│  ├─ saveCaptureTask() → CaptureTask                           │
│  │     └→ TaskStorage → SharedPreferences (JSON)               │
│  │                                                             │
│  └─ syncToCloud() → UploadService                             │
│        └→ POST /api/upload/sampling_point + 补传               │
│           └→ TaskStorage.updateStatus()                        │
├────────────────────────────────────────────────────────────────┤
│  下游消费方:                                                    │
│  TripPreviewService → POST /api/navigation/preview             │
│  MainActivity → "预设采样点路线" 预览功能                         │
└────────────────────────────────────────────────────────────────┘
```

---

## 6. 关键类与接口

### 数据模型

| 类 | 说明 |
|---|---|
| `CaptureTask` | data class，表示一个采样点。含 pointId、chunkId、经纬度、sceneDescription、images Map、status 及时间戳 |

### 服务层

| 类 | 说明 |
|---|---|
| `CompassService : SensorEventListener` | 读取加速度计 + 磁力计，计算平滑航向，报告对齐状态 |
| `GridUtils` (singleton object) | `getTileCoordinate(lat, lon, zoom)` 返回瓦片坐标；`getChunkId()` 返回字符串 ID |
| `TaskStorage` | SharedPreferences 持久层，JSON 序列化/反序列化 CaptureTask 列表 |
| `UploadService` | OkHttp 上传客户端，multipart 编码、重试逻辑、缺失图片补传 |

### UI 层

| 类 | 说明 |
|---|---|
| `DataCollectionActivity : AppCompatActivity` | 唯一 UI 控制器，管理罗盘生命周期、相机 Intent、旋转状态保持（onSaveInstanceState）、云同步 |

---

## 7. 关键配置常量

### 硬编码常量

| 配置项 | 值 | 所在文件 | 说明 |
|---|---|---|---|
| 方向列表 | `["N","NE","E","SE","S","SW","W","NW"]` | DataCollectionActivity / CompassService | 8 方向，每 45° 一档，不可配置 |
| 网格缩放级别 | `16` | `GridUtils.getChunkId()` 默认参数 | Web Mercator zoom，硬编码 |
| 罗盘对齐容差 | `±8°` | `CompassService.alignTolerance` | 航向与目标方向的最大偏差 |
| 航向平滑因子 | `0.1` | `CompassService.smoothingFactor` | 指数移动平均 α 值 |
| 罗盘回调间隔 | `100ms` | `CompassService.callbackInterval` | 两次回调间最短间隔 |
| 跳变拒绝阈值 | `120°` | `CompassService.onSensorChanged()` | 过滤罗盘突变 |
| 上传最大重试 | `3` | `UploadService.MAX_RETRY` | 失败重试次数 |
| 上传重试间隔 | `2000ms` | `UploadService.RETRY_DELAY_MS` | 重试等待时间 |
| 上传连接超时 | `15s` | UploadService OkHttp Builder | 连接超时 |
| 上传读写超时 | `60s` | UploadService OkHttp Builder | 读写超时（大图传输） |
| 场景描述长度 | `50 字符` | `dialog_preview.xml` `maxLength` | 用户输入上限 |
| 采样点 ID 格式 | `P_{timestamp}_{random5}` | `DataCollectionActivity.saveCaptureTask()` | 唯一标识符 |
| SP 文件名 | `"capture_tasks"` | `TaskStorage` | 持久化文件 |
| SP Key | `"semantic_map_tasks"` | `TaskStorage.key` | JSON 数组存储键 |
| 批量上传接口 | `/api/upload/sampling_point` | `UploadService.uploadAll()` | 一次上传 JSON + 8 张图 |
| 单图补传接口 | `/api/upload/image` | `UploadService.uploadSingleImage()` | 补传缺失方向 |
| 默认服务端地址 | `http://114.132.86.138:5000` | `AppConstants.PREVIEW_DEFAULT_BASE_URL` | 后端服务器 |

### 运行时可配置

| 配置项 | 来源 | 说明 |
|---|---|---|
| 服务端 Base URL | SharedPreferences `AppConfig.KEY_PREVIEW_SERVER_BASE_URL` | 可通过 SettingsFragment 修改 |

---

## 8. 布局文件

| 文件 | 说明 |
|---|---|
| `res/layout/activity_data_collection.xml` | 竖屏布局：chunk 信息、罗盘引导、8 方向进度网格、同步/刷新按钮 |
| `res/layout-land/activity_data_collection.xml` | 横屏布局（双栏） |
| `res/layout/dialog_preview.xml` | 预览对话框：双列图片网格 + 场景描述输入框 |

---

## 9. 重构前需关注的核心问题

### 9.1 上帝 Activity

`DataCollectionActivity` 承载了定位、罗盘、拍照、预览、存储、上传**全部逻辑**，文件行数大、职责过重，严重违反单一职责原则。任何修改都可能引发连锁反应。

### 9.2 纯手动采集

- 无自动触发机制（距离 / 时间 / 地理围栏）
- 用户需手动旋转到每个方向逐一拍摄，操作繁琐
- 对视障用户不友好（主要目标用户）

### 9.3 存储方案薄弱

- SharedPreferences + JSON 序列化不适合大量采样数据
- 缺乏查询、过滤、分页能力
- 数据量增大后性能下降明显

### 9.4 配置硬编码

- 方向数、网格级别、罗盘参数、上传重试策略等全部写死
- 无法运行时调整，修改需重新编译

### 9.5 无离线队列管理

- 上传失败后仅标记 `status = "failed"`
- 无自动重试队列、无断点续传、无进度回调

### 9.6 采样系统与视觉模块完全隔离

- 未利用 `inference/` 模块的 YOLOv8 推理能力做场景预分析
- 采集时无智能辅助（如自动识别适合拍摄的时机/角度）

---

## 10. 重构方向建议

| 方向 | 重点 |
|---|---|
| **架构解耦** | 拆分 ViewModel + Repository + UseCase，Activity 仅负责 UI 绑定 |
| **存储升级** | SharedPreferences → Room 数据库，支持查询/索引/迁移 |
| **配置外置** | 关键参数迁移到 `app_constants.json` 或 Remote Config |
| **自动采集** | 引入距离触发 / 地理围栏 / 实时视频流采集能力 |
| **离线队列** | WorkManager 管理上传队列，支持自动重试和断点续传 |
| **智能辅助** | 接入 YOLOv8，提供场景识别 / 拍摄质量评估 / 最佳角度建议 |

---

> 本文档为架构分析产物，可作为重构方案设计的输入依据。
