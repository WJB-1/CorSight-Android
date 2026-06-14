# 街景采样模块重构计划

> 版本：v1.1
> 日期：2026-06-14
> 状态：**待审核**
> 约束：采集者完成一个点位全部数据后再触发上传，暂不支持追增

---

## 一、重构目标

| # | 目标 | 说明 |
|---|------|------|
| 1 | **共享工具层** | 定位、罗盘、相机采集提取为独立可复用工具，供全项目使用 |
| 2 | **双模式采集** | 自由拍照（天桥/复杂路口等按需拍摄）+ 八方向模式（大路口专用，相机内方向提示） |
| 3 | **后台管理界面** | 独立管理页面：照片预览 + 补拍（含定位锁）+ 上传进度 + 单图重传 |
| 4 | **对齐后端 API** | 采用两步上传（先 metadata 后逐张 image），与 `api_contract.md` 一致 |
| 5 | **遵循分层规范** | 调用层 / 工具层 / 数据层不混搭，与上次重构架构保持一致 |

---

## 二、后端接口对齐要点

根据 `api_contract.md`，上传流程为：

```
① POST /api/upload/metadata
   Body: { point_id, location, scene_description, images[{bearing, fov, description}] }
   Resp: { upload_session_id }

② POST /api/upload/image  （逐张，同一 session 可并发）
   Body: multipart { upload_session_id, bearing, image(file), description? }
   Resp: { uploaded_count, total_count }

③ GET /api/upload/session/:sessionId  （查询进度）
   Resp: { status, uploaded_bearings[], pending_bearings[] }
```

**与现有代码的差异：**

| 维度 | 现有代码 | 后端要求 |
|------|---------|---------|
| 上传模式 | 单步 multipart（JSON + 8 张图一起发） | 两步（先 metadata 拿 session_id，再逐张上传） |
| 图片标识 | `image_N` / `image_NE` 方向字符串 | `bearing` 数值（0~360） |
| 进度查询 | 无 | `GET /api/upload/session/:sessionId` |
| 幂等重传 | 无 | 同 session + bearing 可覆盖重传 |
| session 恢复 | 无 | App 重启后可查询 session 继续上传 |

---

## 三、分层架构设计

### 原则

- **工具层**（`core/`）：纯硬件/系统能力封装，无 UI，无业务逻辑，任何模块可调用
- **数据层**（`collection/data/`）：数据模型 + 持久化
- **服务层**（`collection/service/`）：上传逻辑，纯业务，无 UI
- **UI 层**（`collection/ui/`）：Fragment + ViewModel，只做 UI 绑定

### 新增包结构

```
com.example.voicenavigation/
├── core/                              ← 【新增】共享工具层
│   ├── location/                      ← 定位工具
│   │   ├── LocationProvider.kt        # 接口：统一位置源
│   │   └── AMapLocationProvider.kt    # 实现：高德 SDK 封装
│   ├── compass/                       ← 罗盘工具
│   │   ├── CompassProvider.kt         # 接口：统一罗盘源
│   │   └── HardwareCompassProvider.kt # 实现：传感器罗盘（从 CompassService 重构而来）
│   └── camera/                        ← 相机工具
│       ├── CaptureMetadata.kt         # 拍照元数据（bearing/gps/fov/timestamp）
│       └── PhotoCapturer.kt           # CameraX 拍照封装（非 UI，返回 PhotoRecord）
│
├── collection/                        ← 【重构】采样模块
│   ├── data/                          # 数据模型 + 持久化
│   │   ├── CaptureTask.kt             # 采样点（对齐后端 point_id）
│   │   ├── PhotoRecord.kt             # 单张照片记录（对齐 bearing/fov）
│   │   └── TaskStorage.kt             # SharedPreferences 持久化
│   ├── service/                       # 上传服务
│   │   ├── UploadService.kt           # 两步上传（metadata → images）
│   │   └── CountingRequestBody.kt     # OkHttp 进度包装
│   └── ui/                            # UI 层
│       ├── hub/
│       │   └── CaptureHubActivity.kt  # 主 Activity（BottomNav 切换 Fragment）
│       ├── freemode/
│       │   ├── FreeCaptureFragment.kt # 自由拍照模式
│       │   └── FreeCaptureViewModel.kt
│       ├── gridmode/
│       │   ├── GridCaptureFragment.kt # 八方向模式
│       │   └── GridCaptureViewModel.kt
│       └── dashboard/                 # 后台管理（预览 + 补拍 + 上传）
│           ├── DashboardFragment.kt
│           ├── DashboardViewModel.kt
│           ├── PhotoPreviewDialog.kt  # 照片全屏预览（支持放大/滑动）
│           └── RetakeFragment.kt      # 补拍专用 Fragment（含定位锁）
│
├── di/                                ← 【更新】新增 Hilt Module
│   ├── CoreModule.kt                  # 注册 LocationProvider、CompassProvider
│   └── ...
```

### 依赖关系（箭头表示"依赖"）

```
┌─ core/location ──┐
├─ core/compass  ──┤──→ collection/ui (Fragment/ViewModel)
├─ core/camera   ──┤──→ collection/service (UploadService)
│                   │──→ collection/ui/dashboard (定位锁)
│  collection/data ─┤──→ collection/service
│                   │──→ collection/ui
│                   │
│  collection/service ──→ network (TripPreviewService 等，已有)
│
│  vision/、inference/ ──→ 不受影响，继续使用 core/camera 或独立 ImageSource
│  navigation/       ──→ 可迁移至 core/location（Phase 2）
```

---

## 四、共享工具层详细设计（`core/`）

### 4.1 定位工具 `core/location/`

**目的**：消除 `NavigationManager` 和 `DataCollectionActivity` 各自独立创建 `AMapLocationClient` 的重复。

```kotlin
// 接口
interface LocationProvider {
    /** 获取最新位置（挂起，超时返回 null） */
    suspend fun getLastLocation(timeoutMs: Long = 5000L): Location?

    /** 持续监听位置变化 */
    fun observe(intervalMs: Long = 3000L): Flow<Location>

    /** 停止监听 */
    fun stop()
}

// 高德实现
@Singleton
class AMapLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationProvider { ... }
```

- 连续模式（`observe`）供导航使用
- 单次模式（`getLastLocation`）供采样使用
- Hilt 注入为 Singleton，全局复用同一实例

### 4.2 罗盘工具 `core/compass/`

**目的**：将 `CompassService` 从 `collection/` 提升为全项目可复用，未来导航和障碍检测也能接入航向。

```kotlin
// 接口
interface CompassProvider {
    /** 航向数据流（连续，已做平滑和跳变过滤） */
    fun observe(): Flow<HeadingData>

    /** 停止监听 */
    fun stop()
}

data class HeadingData(
    val heading: Float,         // 0~360°，已平滑，已做横屏校正
    val accuracy: Float,        // 传感器精度
    val timestamp: Long
)

// 传感器实现
@Singleton
class HardwareCompassProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : CompassProvider { ... }
```

- 从现有 `CompassService` 重构，去掉 UI 依赖（AlertDialog、Toast 等）
- 保留 EMA 平滑、跳变拒绝、屏幕旋转补偿
- **去掉**八方向对齐逻辑（这是 UI 层职责，放在 `GridCaptureFragment`）

#### 横屏兼容设计

采样模块以横屏为主（相机取景更宽），罗盘必须正确处理屏幕旋转：

```kotlin
// HardwareCompassProvider 核心实现要点

class HardwareCompassProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : CompassProvider, SensorEventListener {

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var currentRotation = Surface.ROTATION_0  // 由外部 Activity 通知

    /**
     * 更新屏幕旋转方向，必须在 Activity.onConfigurationChanged 中调用。
     * CameraX 的 PreviewView 会自动处理预览旋转，
     * 但罗盘的 remapCoordinateSystem 需要知道当前屏幕方向才能计算正确航向。
     */
    fun setScreenRotation(rotation: Int) {
        currentRotation = rotation
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // 根据屏幕方向重映射坐标系
        // 竖屏 (ROTATION_0):   AXIS_X, AXIS_Y
        // 横屏 (ROTATION_90):  AXIS_Y, AXIS_MINUS_X  （设备顺时针转 90°）
        // 反横屏 (ROTATION_270): AXIS_MINUS_Y, AXIS_X
        when (currentRotation) {
            Surface.ROTATION_0 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedMatrix
                )
            }
            Surface.ROTATION_90 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix
                )
            }
            Surface.ROTATION_180 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix
                )
            }
            Surface.ROTATION_270 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix
                )
            }
        }

        SensorManager.getOrientation(remappedMatrix, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val bearing = ((azimuth + 360) % 360)

        // ... EMA 平滑 + 跳变拒绝 → emit HeadingData
    }
}
```

**调用方责任**：Fragment/Activity 在 `onConfigurationChanged()` 中调用 `compassProvider.setScreenRotation(windowManager.defaultDisplay.rotation)`，确保航向与实际画面方向一致。`CaptureHubActivity` 配置 `android:configChanges="orientation|screenSize"` 后统一转发旋转事件。

### 4.3 相机采集工具 `core/camera/`

**目的**：`CaptureMetadata` 和拍照结果封装为纯数据层，供采样和未来其他模块复用。

```kotlin
data class CaptureMetadata(
    val bearing: Float,          // 0~360°（来自 CompassProvider）
    val latitude: Double,        // WGS-84
    val longitude: Double,       // WGS-84
    val fovX: Float,             // 水平视场角
    val fovY: Float,             // 垂直视场角
    val focalLength: Float,      // 焦距 mm
    val zoomRatio: Float,        // 变焦倍率
    val timestamp: Long
)
```

- 与后端 `images[].bearing` / `images[].fov` 直接对齐
- 拍照逻辑（CameraX 的 `ImageCapture.takePicture`）保留在各 Fragment 中，因为 UI 交互（方向提示、快门状态）与 Fragment 耦合

### 4.4 Hilt 注册 `di/CoreModule.kt`

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {
    @Binds @Singleton
    abstract fun bindLocationProvider(impl: AMapLocationProvider): LocationProvider

    @Binds @Singleton
    abstract fun bindCompassProvider(impl: HardwareCompassProvider): CompassProvider
}
```

---

## 五、数据层详细设计（`collection/data/`）

### 5.1 `PhotoRecord.kt`

对齐后端 `images[]` 结构：

```kotlin
data class PhotoRecord(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    val bearing: Float,              // 0~360°，直接对应后端 bearing
    val fov: Float = 90f,            // 对应后端 fov
    val description: String = "",    // 对应后端 images[].description
    var uploadStatus: UploadStatus = UploadStatus.PENDING,
    var remotePath: String? = null   // 服务端返回的 path
)

enum class UploadStatus { PENDING, UPLOADING, UPLOADED, FAILED }
```

### 5.2 `CaptureTask.kt`

```kotlin
data class CaptureTask(
    val pointId: String,             // 格式：P_{timestamp}_{random5}，全局唯一
    val latitude: Double,            // WGS-84
    val longitude: Double,           // WGS-84
    val sceneDescription: String,    // 对应后端 scene_description
    val mode: CaptureMode,           // FREE 或 GRID
    val photos: MutableList<PhotoRecord>,
    var uploadSessionId: String? = null,   // 后端返回的 session_id
    var status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt
)

enum class CaptureMode { FREE, GRID }
enum class TaskStatus { PENDING, UPLOADING, SUCCESS, FAILED }
```

### 5.3 `TaskStorage.kt`

- 保持 SharedPreferences + JSON 方案（暂不迁移 Room，避免范围膨胀）
- JSON 结构对齐后端字段命名（`point_id`, `bearing`, `fov`, `upload_session_id`）
- 新增 `updatePhotoUploadStatus()` 方法支持单图状态更新

---

## 六、上传服务详细设计（`collection/service/`）

### 6.1 `UploadService.kt`

两步上传流程：

```
uploadTask(task: CaptureTask):
  ① POST /api/upload/metadata
     → 获取 upload_session_id
     → 保存到 task.uploadSessionId

  ② 遍历 task.photos，逐张 POST /api/upload/image
     → 并发控制：最多 3 张同时上传
     → 每张带进度回调
     → 更新 PhotoRecord.uploadStatus
     → 最后一张成功后更新 TaskStatus

uploadSinglePhoto(task: CaptureTask, photo: PhotoRecord):
  → 仅上传单张（重传场景）
  → 需要已有 uploadSessionId

checkSessionStatus(sessionId: String):
  → GET /api/upload/session/:sessionId
  → 用于恢复未完成的上传
```

### 6.2 `CountingRequestBody.kt`

OkHttp 进度包装器（从参考方案中提取）：
- 包装原始 `RequestBody`，在 `writeTo()` 中计数字节
- 回调 `(bytesSent: Long, totalBytes: Long) -> Unit`
- 用于驱动 UI 进度条

---

## 七、UI 层详细设计（`collection/ui/`）

### 7.1 `CaptureHubActivity` — 主入口

- 3 个 Tab：自由采集 · 八方向 · 后台管理
- 使用 `BottomNavigationView` + `FragmentContainerView`
- 布局：`activity_capture_hub.xml`

```xml
<LinearLayout vertical>
    <FrameLayout id="fragmentContainer" weight=1 />
    <BottomNavigationView id="bottomNav" menu="@menu/capture_nav" />
</LinearLayout>
```

菜单项：`nav_free` / `nav_grid` / `nav_dashboard`

**屏幕旋转处理：** 声明 `android:configChanges="orientation|screenSize|screenLayout"` 避免 Fragment 重建。在 `onConfigurationChanged()` 中通知 `CompassProvider.setScreenRotation()` 更新罗盘坐标系映射。

### 7.2 `FreeCaptureFragment` — 自由拍照模式

**交互流程：**

```
相机预览（CameraX PreviewView 全屏）
    │
    ├─ 左上角 DebugOverlay（bearing / GPS / FOV 实时显示）
    ├─ 底部快门按钮（始终可用，自由拍摄无方向约束）
    │
    └─ 拍照后 → 弹出标注对话框
         ├─ 场景类型选择（Spinner: 天桥/复杂路口/斑马线/公交站/隧道/其他）
         ├─ 方位角显示（只读，自动填充 bearing）
         ├─ 描述输入（可选）
         └─ 保存 / 放弃
```

**ViewModel 职责：**
- 管理 `photos: List<PhotoRecord>` 列表
- 组装 `CaptureTask`（mode = FREE）
- 调用 `TaskStorage.saveTask()`

### 7.3 `GridCaptureFragment` — 八方向模式

**交互流程：**

```
相机预览（CameraX PreviewView 全屏）
    │
    ├─ 中央方向提示 TextView（"请对准 NE" / "已对准 NE ✓"）
    ├─ 八方向进度指示器（顶部/侧边，8 格，已拍/未拍/当前）
    ├─ 快门按钮（未对准时 disabled + 0.3 透明度）
    │
    └─ 每拍一张 → 自动切到下一个未拍方向
         → 8 张全拍完 → 弹出保存对话框
```

**关键逻辑：**
- 不固定从 N 开始，找第一个未拍方向作为当前目标
- 8 方向映射：每 45° 一档（N=0, NE=45, E=90, ... NW=315）
- 对齐容差 ±12°（从现有 8° 放宽到 12°，减少用户对准难度）
- 未对准时 `setShutterEnabled(false)`，已对准时 `setShutterEnabled(true)` + 振动
- 每张照片的 `bearing` 用实际传感器值（不强制取 45 的整数倍）

### 7.4 `DashboardFragment` — 后台管理

后台管理页面承载三大职责：**查看采集结果**、**补拍不合格照片**、**管理上传进度**。

**界面布局：**

```
┌─────────────────────────────────────────────┐
│  采样点列表                                    │
│  ┌─────────────────────────────────────────┐│
│  │ P_1718367890123_a3f5 [FREE]             ││
│  │ 23.1291°N, 113.2644°E                   ││
│  │ "天桥入口附近有盲道"                      ││
│  │                                          ││
│  │ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐   ││
│  │ │ 📷  │ │ 📷  │ │ 📷  │ │ 📷  │   ││
│  │ │ 0°  │ │ 90° │ │180° │ │270° │   ││ ← 照片缩略图网格
│  │ │ ✓  │ │ ✓  │ │ ✗  │ │ ✓  │   ││
│  │ └──────┘ └──────┘ └──────┘ └──────┘   ││
│  │  ─── 点击缩略图可预览大图 ───             ││
│  │                                          ││
│  │ 180°: ❌ 失败  [重传]  [补拍]            ││ ← 失败照片可重传或补拍
│  │                                          ││
│  │ [上传全部]  [删除点位]                    ││
│  └─────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────┐│
│  │ P_1718367901234_b7c2 [GRID]             ││
│  │ 上传中: 5/8 张                            ││
│  │                                          ││
│  │ bearing=0°   ██████████ 100%  ✓         ││ ← 上传进度条
│  │ bearing=45°  ██████░░░░  60%  ↑         ││
│  │ bearing=90°  ░░░░░░░░░░   0%  •         ││
│  │ ...                                      ││
│  │  [上传全部]  [暂停]  [删除点位]           ││
│  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────┘
```

**三大子功能：**

#### A. 照片预览

- 点击缩略图 → 弹出 `PhotoPreviewDialog`（全屏，支持左右滑动切换同一 Task 内的照片）
- 显示元信息：bearing、fov、GPS 坐标、拍摄时间、上传状态
- 已上传照片可查看服务端返回的 `remotePath`

#### B. 补拍（含定位锁）

当某张照片拍摄效果不佳或上传失败时，可选择"补拍"进入 `RetakeFragment`：

```
RetakeFragment 流程:
  ① 加载原 Task 的 GPS 坐标（作为锚点）
  ② 获取当前位置，计算与锚点的 Haversine 距离
  ③ 若距离 > RETAKE_MAX_DISTANCE_M（默认 50m）:
     → 禁用快门，显示警告: "当前位置距采集点 XXm，超出允许范围（50m），请返回采集点附近"
     → 仅允许查看，不允许拍摄
  ④ 若距离 ≤ 50m:
     → 正常 CameraX 预览
     → 用户拍照后，替换原 PhotoRecord 的 filePath
     → 原文件删除，上传状态重置为 PENDING
     → 保存到 TaskStorage
```

**定位锁常量：**

| 常量 | 值 | 说明 |
|------|-----|------|
| `RETAKE_MAX_DISTANCE_M` | `50` | 补拍时允许的最大偏移距离（米） |
| `RETAKE_LOCATION_TIMEOUT_MS` | `5000` | 获取当前位置的超时时间 |
| `RETAKE_LOCATION_ACCURACY_M` | `10` | 要求 GPS 精度（水平精度 < 10m 才接受） |

**补拍的 PhotoRecord 处理：**
- `bearing`：用当前传感器实际值（不强制与原值相同，因为用户可能微调角度）
- `fov`：用当前相机实际值
- `uploadStatus`：重置为 `PENDING`（因为是新文件）
- `remotePath`：清空

#### C. 上传管理

- 每张照片独立进度条（0~100%），通过 `CountingRequestBody` 回调驱动
- 状态图标：⏳ 等待 · ↑ 上传中 · ✓ 完成 · ✗ 失败
- 单张重传按钮（调用 `UploadService.uploadSinglePhoto()`）
- 批量上传按钮（依次上传所有 PENDING/FAILED 任务的照片）
- "删除点位"按钮（删除 Task + 清理本地图片文件）

**DashboardViewModel 职责：**
```kotlin
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val taskStorage: TaskStorage,
    private val uploadService: UploadService,
    private val locationProvider: LocationProvider
) : ViewModel() {

    val tasks: StateFlow<List<CaptureTask>>          // 从 TaskStorage 流式加载
    val photoProgress: StateFlow<Map<String, Int>>    // photoId → 0~100

    fun uploadTask(task: CaptureTask)                 // 两步上传整 Task
    fun retryPhoto(taskId: String, photoId: String)   // 单张重传
    fun deleteTask(taskId: String)                    // 删除 Task + 清理文件

    /**
     * 检查是否允许补拍（定位锁）
     * @return null 表示允许，否则返回错误消息（含实际距离）
     */
    suspend fun checkRetakeEligibility(taskId: String): String? {
        val task = taskStorage.getTask(taskId) ?: return "任务不存在"
        val current = locationProvider.getLastLocation(RETAKE_LOCATION_TIMEOUT_MS)
            ?: return "无法获取当前位置，请检查 GPS 信号"
        if (current.accuracy > RETAKE_LOCATION_ACCURACY_M) {
            return "GPS 精度不足（${current.accuracy.toInt()}m），请等待信号稳定"
        }
        val distance = haversine(task.latitude, task.longitude, current.latitude, current.longitude)
        return if (distance > RETAKE_MAX_DISTANCE_M) {
            "当前位置距采集点 ${distance.toInt()}m，超出允许范围（${RETAKE_MAX_DISTANCE_M}m），请返回采集点附近"
        } else null
    }

    /** 替换照片并重置上传状态 */
    fun replacePhoto(taskId: String, oldPhotoId: String, newPhoto: PhotoRecord)
}
```

**定位锁的 Haversine 距离计算：**

```kotlin
// DashboardViewModel 或 utility
private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0  // 地球半径 (米)
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}
```

---

## 八、CameraX 集成方案

### 8.1 复用现有 `CameraSource.kt`

现有 `CameraSource` 已实现 `ImageSource` 接口（预览 + ImageAnalysis），但只有分析帧能力，没有拍照能力。方案：

- `core/camera/` 中 **不新建 CameraX Fragment**
- 采样模块的两个 Fragment **各自创建 CameraX 预览 + ImageCapture 用例**
- 共享 `CompassProvider` 和 `LocationProvider` 通过 Hilt 注入
- 未来如果避障模块也需要拍照能力，再提取公共 CameraFragment

### 8.2 新增依赖

`app/build.gradle` 已有 CameraX 依赖，**无需新增**。仅需新增：

```groovy
// app/build.gradle — 已存在，无需改动
implementation "androidx.camera:camera-camera2:1.3.1"
implementation "androidx.camera:camera-lifecycle:1.3.1"
implementation "androidx.camera:camera-view:1.3.1"
```

---

## 九、迁移与兼容策略

### 9.1 旧代码处理

| 文件 | 处理方式 |
|------|---------|
| `DataCollectionActivity.kt` | 重构后删除，由 `CaptureHubActivity` 替代 |
| `CompassService.kt` | 重构为 `HardwareCompassProvider`，原文件删除 |
| `CaptureTask.kt` | 重写，新字段结构 |
| `TaskStorage.kt` | 重写，SP 文件名改为 `capture_tasks_v2`（旧数据隔离） |
| `UploadService.kt` | 重写，两步上传替代单步上传 |
| `GridUtils.kt` | 保留，移入 `core/location/` 或保留在 `collection/data/` |

### 9.2 入口路由更新

- `AppCommandHandler` 中 `"data_collection"` → 改为启动 `CaptureHubActivity`
- `SettingsFragment` 中"数据采集"按钮 → 改为启动 `CaptureHubActivity`
- `menu_config.json` 中 `"command": "data_collection"` 不变

### 9.3 布局文件

| 新增 | 说明 |
|------|------|
| `activity_capture_hub.xml` | 主 Activity 布局 |
| `fragment_free_capture.xml` | 自由采集 Fragment |
| `fragment_grid_capture.xml` | 八方向采集 Fragment |
| `fragment_upload_manager.xml` | 上传管理 Fragment |
| `dialog_photo_label.xml` | 照片标注对话框 |
| `dialog_save_task.xml` | 任务保存确认对话框 |
| `item_photo_progress.xml` | 上传列表单张照片条目 |
| `res/menu/capture_nav.xml` | BottomNav 菜单 |

| 保留 | 说明 |
|------|------|
| `activity_data_collection.xml` | 重构完成后删除 |

---

## 十、实施阶段

### Phase 1：共享工具层（`core/`）

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1.1 | 创建 `core/location/LocationProvider.kt` 接口 | 新建 |
| 1.2 | 创建 `core/location/AMapLocationProvider.kt` 实现 | 新建，从 NavigationManager + DataCollectionActivity 抽取 |
| 1.3 | 创建 `core/compass/CompassProvider.kt` 接口 | 新建 |
| 1.4 | 创建 `core/compass/HardwareCompassProvider.kt` 实现 | 从 CompassService 重构 |
| 1.5 | 创建 `core/camera/CaptureMetadata.kt` | 新建 |
| 1.6 | 创建 `di/CoreModule.kt` Hilt 注册 | 新建 |
| 1.7 | 验证编译通过 | — |

### Phase 2：数据层（`collection/data/`）

| 步骤 | 任务 | 文件 |
|------|------|------|
| 2.1 | 创建 `collection/data/PhotoRecord.kt` | 新建 |
| 2.2 | 重写 `collection/data/CaptureTask.kt` | 重写 |
| 2.3 | 重写 `collection/data/TaskStorage.kt` | 重写 |
| 2.4 | 验证编译通过 | — |

### Phase 3：上传服务（`collection/service/`）

| 步骤 | 任务 | 文件 |
|------|------|------|
| 3.1 | 创建 `collection/service/CountingRequestBody.kt` | 新建 |
| 3.2 | 重写 `collection/service/UploadService.kt`（两步上传） | 重写 |
| 3.3 | 验证编译通过 | — |

### Phase 4：UI 层 — Hub + 自由采集

| 步骤 | 任务 | 文件 |
|------|------|------|
| 4.1 | 创建 `activity_capture_hub.xml` + `capture_nav.xml` | 新建 |
| 4.2 | 创建 `CaptureHubActivity.kt` | 新建 |
| 4.3 | 创建 `fragment_free_capture.xml` | 新建 |
| 4.4 | 创建 `FreeCaptureViewModel.kt` | 新建 |
| 4.5 | 创建 `FreeCaptureFragment.kt`（CameraX 预览 + 自由拍照） | 新建 |
| 4.6 | 更新入口路由（AppCommandHandler + SettingsFragment） | 修改 |
| 4.7 | 验证自由采集 + 保存功能 | — |

### Phase 5：UI 层 — 八方向采集

| 步骤 | 任务 | 文件 |
|------|------|------|
| 5.1 | 创建 `fragment_grid_capture.xml` | 新建 |
| 5.2 | 创建 `GridCaptureViewModel.kt` | 新建 |
| 5.3 | 创建 `GridCaptureFragment.kt`（CameraX + 方向提示 + 快门控制） | 新建 |
| 5.4 | 验证八方向采集 + 保存功能 | — |

### Phase 6：UI 层 — 后台管理（预览 + 补拍 + 上传）

| 步骤 | 任务 | 文件 |
|------|------|------|
| 6.1 | 创建 `fragment_dashboard.xml` + `item_task_card.xml` + `item_photo_grid.xml` | 新建 |
| 6.2 | 创建 `DashboardViewModel.kt`（含定位锁 checkRetakeEligibility） | 新建 |
| 6.3 | 创建 `DashboardFragment.kt`（任务列表 + 照片网格 + 上传进度） | 新建 |
| 6.4 | 创建 `PhotoPreviewDialog.kt`（全屏预览 + 左右滑动） | 新建 |
| 6.5 | 创建 `retake_fragment.xml` + `RetakeFragment.kt`（补拍 + 定位锁） | 新建 |
| 6.6 | 验证：照片预览 → 补拍（含超出距离拦截）→ 上传流程 — |

### Phase 7：清理与收尾

| 步骤 | 任务 | 文件 |
|------|------|------|
| 7.1 | 删除旧文件（DataCollectionActivity、CompassService 旧版） | 删除 |
| 7.2 | 删除旧布局（activity_data_collection.xml、dialog_preview.xml） | 删除 |
| 7.3 | 更新 strings.xml（新增采样相关字符串，去除旧字符串） | 修改 |
| 7.4 | 更新 AndroidManifest.xml（注册 CaptureHubActivity，注销 DataCollectionActivity） | 修改 |
| 7.5 | 全量编译 + 手动测试 | — |

---

## 十一、风险与决策记录

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 是否迁移到 Room | 否，暂保留 SharedPreferences | 避免范围膨胀，采样数据量有限 |
| 是否提取公共 CameraFragment | 否，各 Fragment 自建 CameraX | 当前仅采样模块用，过早抽象增加复杂度 |
| 八方向对齐容差 | 12°（原 8°） | 减少用户对准难度，参考方案建议 |
| 是否固定从 N 开始拍摄 | 否，找第一个未拍方向 | 你明确要求 |
| 后端数据结构 | 对齐 `api_contract.md` | bearing/fov/session_id 字段一致 |
| 旧数据迁移 | 不迁移，SP 文件名隔离 | 旧数据量小，可在旧版查看 |
| 罗盘横屏兼容 | remapCoordinateSystem 四方向映射 | 横屏是采样主力场景，必须正确处理 |
| "上传管理"改名 | → "后台管理" | 职责扩大：含预览 + 补拍 + 上传，"上传"不能覆盖全部功能 |
| 补拍定位锁距离 | 50m | 过小不便采集，过大失去定位锚定意义（参考 GPS 步行精度） |
| 补拍 GPS 精度要求 | < 10m 水平精度 | 防止 GPS 冷启动时精度差导致误判 |
| 上传时序 | 完成整个点位后才上传 | 用户明确要求，暂不支持逐张实时上传 |

---

## 十二、文件变更总览

### 新建（~20 个 Kotlin 文件）

```
core/location/LocationProvider.kt
core/location/AMapLocationProvider.kt
core/compass/CompassProvider.kt
core/compass/HardwareCompassProvider.kt
core/camera/CaptureMetadata.kt
di/CoreModule.kt
collection/data/PhotoRecord.kt
collection/data/CaptureTask.kt          (重写，同路径)
collection/data/TaskStorage.kt          (重写，同路径)
collection/service/UploadService.kt     (重写，同路径)
collection/service/CountingRequestBody.kt
collection/ui/hub/CaptureHubActivity.kt
collection/ui/freemode/FreeCaptureFragment.kt
collection/ui/freemode/FreeCaptureViewModel.kt
collection/ui/gridmode/GridCaptureFragment.kt
collection/ui/gridmode/GridCaptureViewModel.kt
collection/ui/dashboard/DashboardFragment.kt
collection/ui/dashboard/DashboardViewModel.kt
collection/ui/dashboard/PhotoPreviewDialog.kt
collection/ui/dashboard/RetakeFragment.kt
```

### 新建布局（~11 个文件）

```
res/layout/activity_capture_hub.xml
res/layout/fragment_free_capture.xml
res/layout/fragment_grid_capture.xml
res/layout/fragment_dashboard.xml
res/layout/fragment_retake.xml
res/layout/dialog_photo_preview.xml
res/layout/dialog_photo_label.xml
res/layout/dialog_save_task.xml
res/layout/item_task_card.xml
res/layout/item_photo_grid.xml
res/menu/capture_nav.xml
```

### 修改（~4 个文件）

```
command/AppCommandHandler.kt           (入口路由更新)
ui/main/settings/SettingsFragment.kt   (入口路由更新)
AndroidManifest.xml                    (Activity 注册)
res/values/strings.xml                 (字符串更新)
```

### 删除（~3 个文件）

```
collection/DataCollectionActivity.kt   (由 CaptureHubActivity 替代)
collection/CompassService.kt           (由 HardwareCompassProvider 替代)
res/layout/activity_data_collection.xml (旧布局)
res/layout/dialog_preview.xml          (旧对话框)
```

---

> 本计划待审核确认后开始实施。如有需要调整的设计决策，请在审核时提出。
