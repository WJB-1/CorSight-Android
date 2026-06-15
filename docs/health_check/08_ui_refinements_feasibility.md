# UI 优化可行性分析

> 基于 `ui.md` 反馈 + 当前代码状态

---

## 一、状态栏兼容

### 现状

`themes.xml` 中 `AppTheme.NoActionBar` 设置了 `android:windowFullscreen=true`，状态栏被完全隐藏。这是旧做法，在 Android 15+ 已不推荐。

### 方案

用 `enableEdgeToEdge()` + `WindowInsets` 动态处理：

```kotlin
// MainActivity.onCreate()，setContentView 之前
enableEdgeToEdge()

// 关键 UI 元素添加状态栏内边距
ViewCompat.setOnApplyWindowInsetsListener(navInfoLayout) { view, insets ->
    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.updatePadding(top = systemBars.top)
    insets
}
```

**改动量：**
| 文件 | 改动 |
|------|------|
| `themes.xml` | 删除 `android:windowFullscreen`，保留 `windowNoTitle` |
| `activity_main.xml` | 导航信息浮层 `marginTop` 从 `56dp` 改为 `0dp`（由 WindowInsets 动态填充） |
| `MainActivity.kt` | `onCreate` 加 `enableEdgeToEdge()` + `setOnApplyWindowInsetsListener`（~10 行） |
| `VisionTestActivity.kt` | 同上处理（~5 行） |
| `CaptureHubActivity.kt` | 同上处理（~5 行） |

**可行性：完全可行。** `enableEdgeToEdge()` 是 AndroidX 库（`androidx.activity:activity`）自带的，已包含在项目依赖中。兼容 Android 7.0+。地图会自然延伸到状态栏下方，视觉效果更好。

**风险：低。** 唯一需要注意的是：状态栏变为透明后，深色背景上的状态栏图标颜色需要调整为白色：
```kotlin
WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
```

---

## 二、Logo "瞳心引航" 优化

### 现状

`activity_main.xml` 第 19-31 行：左上角 `TextView`，文字颜色 `#60FFFFFF`（白色 37% 透明度），`marginTop=36dp`，`elevation=4dp`。

### 用户需求

- 透明度调成 0（完全不可见？或者调低到几乎透明）
- 加阴影
- 放到左下角

### 分析

"透明度调成零"意味着**完全隐藏**。如果是有意隐藏品牌标识但保留技术标记（debug 用），改为 `alpha=0` 即可。如果是想做成"若隐若现"的效果，改为 `#10FFFFFF`（6% 透明度）。

### 方案

**方案 A：完全隐藏**（推荐，盲人用户看不到 logo）

```xml
<!-- 删除整个 TextView，或设为 gone -->
```

**方案 B：极淡阴影水印（左下角）**

```xml
<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginStart="12dp"
    android:layout_marginBottom="60dp"
    android:text="瞳心引航"
    android:textSize="14sp"
    android:textStyle="bold"
    android:textColor="#10FFFFFF"
    android:shadowColor="#000000"
    android:shadowDx="1"
    android:shadowDy="1"
    android:shadowRadius="3"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintBottom_toBottomOf="parent" />
```

**改动量：** `activity_main.xml` 修改 1 个 TextView（~5 行）
**可行性：完全可行。** 无风险。

---

## 三、菜单选中时的振动和语音反馈

### 现状

| 反馈 | 触发时机 | 现状 |
|------|---------|------|
| 振动 | 长按触发 | ✅ 已有（`RingMenuCoordinator` 中 100ms 振动） |
| 振动 | 手指滑到新扇区 | ❌ 没有 |
| 语音 | 手指滑到新扇区 | ❌ 没有 |
| 语音 | 松手执行命令 | ❌ 没有（只有 TTS 播报执行结果） |

### 方案

**滑到新扇区时：**
- 振动：短振动 20ms（提示"滑到了新区域"）
- 语音：TTS 读出菜单项名称（如"避障"、"语音助手"）

**松手执行时：**
- 振动：中振动 50ms（确认执行）
- 语音：TTS 读出"正在执行 XXX"（如"正在启动避障"）

### 实现位置

在 `RingMenuCoordinator.emitHighlightEvent()` 中，当检测到新高亮项时：

```kotlin
if (currentItem != null && currentItem != lastHighlightedItem) {
    lastHighlightedItem = currentItem
    animateSelectionExpand()
    // 新增：振动 + 语音反馈
    vibrate(20)  // 短振动
    emit(InteractionEvent.ItemHighlighted(currentItem))  // UI 层订阅后 TTS 播报
    // 或直接在此处调用 TTS
}
```

### TTS 方案选择

| 方案 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **A. Coordinator 直接调 TTS** | Coordinator 持有 `BaiduTtsManager` 引用 | 简单直接 | Coordinator 耦合了 TTS |
| **B. 通过事件流** | `InteractionEvent.ItemHighlighted` → MainActivity 收集 → 调 TTS | 解耦 | 有微小延迟 |
| **C. 专用 FeedbackManager** | 新建 `HapticFeedbackManager` 统一管理振动+语音 | 最干净 | 改动大 |

**推荐方案 B**：最小改动，解耦。MainActivity 中已有事件收集器，只需加一行 TTS 调用。

### 改动量

| 文件 | 改动 |
|------|------|
| `RingMenuCoordinator.kt` | `emitHighlightEvent()` 加 20ms 振动（~3 行） |
| `RingMenuCoordinator.kt` | `confirmCurrentSelection()` 加 50ms 振动（~3 行） |
| `MainActivity.kt` | `handleInteractionEvent()` 中对 `ItemHighlighted` 加 TTS 播报（~5 行） |

**可行性：完全可行。** 改动量 ~15 行。
**风险：低。** 需要注意 TTS 播报队列不能堆积——手指快速滑过多个扇区时，应该**打断上一条**再播新的，否则会排队播放"语…音…助…手…避…障…设…置…"。`BaiduTtsManager.flushQueue()` 已有此功能。

---

## 四、底部地图锁定

### 现状

地图 `MapView` 是全屏底层，用户在地图区域的滑动会被高德地图 SDK 拦截处理（平移/缩放）。当环形菜单弹出时，手指滑动同时会移动地图——用户松手后地图位置已变。

### 问题本质

`dispatchTouchEvent` 中，`RingMenuCoordinator.onTouchEvent()` 返回后 `super.dispatchTouchEvent()` 仍然会把事件传给地图。Coordinator 在 `RING_MENU`/`SELECTING` 状态下虽然返回 `true`（消费事件），但 `dispatchTouchEvent` 无视了返回值，总是调用 `super`。

### 方案

| 方案 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **A. dispatchTouchEvent 拦截** | 当 Coordinator 返回 `true` 时，不调 `super`，事件不传给地图 | 最简单 | 可能影响其他 View 的触摸 |
| **B. 地图 UI 设置禁用** | 菜单显示时调用 `mMap.uiSettings.setAllGesturesEnabled(false)`，隐藏时恢复 | 精确控制 | 需要持有 MapView 引用 |
| **C. FLAG_DISALLOW_INTERCEPT** | 菜单显示时在根布局上设置 `requestDisallowInterceptTouchEvent(true)` | 标准做法 | 仅对父 View 有效，地图是子 View 可能不响应 |
| **D. 地图容器 View 层级** | 在地图上方叠一个透明 View 消费事件 | 可靠 | 增加一层 View |

**推荐方案 B**：最精确，只锁地图手势，不影响其他交互。

```kotlin
// 菜单弹出时
mMap?.uiSettings?.setScrollGesturesEnabled(false)
mMap?.uiSettings?.setZoomGesturesEnabled(false)

// 菜单关闭时
mMap?.uiSettings?.setScrollGesturesEnabled(true)
mMap?.uiSettings?.setZoomGesturesEnabled(true)
```

### 改动量

| 文件 | 改动 |
|------|------|
| `MainActivity.kt` | `handleInteractionEvent()` 中，`ShowMenu` 事件时禁用地图手势，`DismissMenu`/`ItemExecuted`/`Cancelled`/`LaunchVoiceAssistant` 时恢复（~10 行） |

**可行性：完全可行。** 高德 AMap SDK 的 `UiSettings` 提供了细粒度的手势开关。改动量极小。
**风险：低。** 唯一注意：如果菜单动画中途异常退出（如 Activity 被杀），地图手势可能永远锁定。在 `onDestroy` 中恢复即可。

---

## 五、总结

| 优化项 | 可行性 | 改动量 | 优先级 |
|--------|--------|--------|--------|
| 状态栏兼容 (Edge-to-Edge) | ✅ 完全可行 | ~20 行 + XML | 高 |
| Logo 优化 | ✅ 完全可行 | ~5 行 XML | 低 |
| 菜单振动+语音反馈 | ✅ 完全可行 | ~15 行 | 高 |
| 地图锁定 | ✅ 完全可行 | ~10 行 | 高 |

**4 项全部可行，无技术风险。** 总改动量约 50 行代码。可以一次性做完。
