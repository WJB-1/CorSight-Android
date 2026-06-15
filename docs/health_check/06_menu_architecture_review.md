# 菜单交互架构评审与中介者模式分析

> 基于 `05_menu_interaction_architecture.md`、`menu_pro.md`、`menu.md` 综合整理

---

## 一、现有目录导航架构

```
用户手指按下
  │
  ▼
Activity.dispatchTouchEvent()          ← 所有触摸事件的入口
  │
  ├─→ GestureVoiceLauncher             ← 全局单例，挂在 dispatchTouchEvent 上
  │     │  内部状态：IDLE → PRESSING → LONG_PRESSING
  │     │  500ms 定时器触发后：
  │     ├─→ callback.onRingMenuShow(x, y)  → MainActivity.showRingMenu()
  │     │                                     → ViewTransition.scaleInFrom()
  │     │                                     → ringMenuContainer.visibility = VISIBLE
  │     │
  │     └─→ ACTION_UP 时：
  │           callback.onRingMenuConfirm()   → MainActivity（空方法，什么都没做）
  │
  └─→ super.dispatchTouchEvent()       ← 事件继续传递给 Android 框架
        │
        ▼
      RingMenuView.onTouchEvent()      ← 菜单 View 自己处理触摸
        │
        ├─ ACTION_MOVE → handleMove(x, y)
        │    ├─ 计算角度（atan2，0°在3点钟方向）
        │    ├─ 计算距离（是否在环内）
        │    ├─ 设置 selectedIndex / selectedChildIndex
        │    └─ invalidate() → onDraw() 重绘高亮
        │
        └─ ACTION_UP → handleUp()
             ├─ 有子菜单项 → 展开子菜单（不执行命令）
             ├─ 普通菜单项 → onItemExecuted(item)
             │     └─ MainActivity lambda:
             │          hideRingMenu()
             │          commandRouter.execute(item.command)
             │              └─ MenuCommand.execute() → CommandEvent
             │              └─ SharedFlow.tryEmit(event)
             │              └─ MainActivity lifecycleScope.collect
             │              └─ handleCommandEvent(event)
             │              └─ 实际 UI 操作（跳转/TTS/等）
             └─ 无选中项 → onCenterClicked() → hideRingMenu()
```

---

## 二、已确认的 Bug 清单

| # | 严重度 | 问题 | 根因 | 位置 |
|---|--------|------|------|------|
| B1 | **CRITICAL** | 长按后直接松手不会启动语音助手，菜单弹出又立刻关闭 | `GestureVoiceLauncher.ACTION_UP` 调用 `onRingMenuConfirm()`（空方法），不调用 `onVoiceAssistant()` | GestureVoiceLauncher:94 |
| B2 | **CRITICAL** | 点击区域和实际高亮不匹配（点"停止导航"弹出"更多"的子菜单） | `atan2` 的 0° 在 3 点钟方向，但绘制从 12 点钟开始，角度偏移 90° | RingMenuView:186 |
| B3 | **HIGH** | 手指松开后菜单不执行命令（需要菜单动画完成才能接收触摸） | `ringMenuContainer` 从 GONE 变 VISIBLE 需要 350ms 动画，动画期间 View 不接收触摸 | MainActivity:1097 |
| B4 | **HIGH** | "停止导航"执行两次 | `StopNavigationCommand.execute()` 直接调 `stopNavigation()` + 返回事件，`handleCommandEvent` 又调一次 | StopNavigationCommand + MainActivity:1120 |
| B5 | **MEDIUM** | 选中扇形发光效果无效 | `brighten()` 中 `glowAlpha` 被 `coerceAtMost(0xFF)` 钳位，永远是 255 | RingMenuView:397 |
| B6 | **MEDIUM** | 二级菜单扇区与一级菜单重叠 | 子菜单从父级起始角开始展开，3 个子项各占 120°，超出父扇区范围 | RingMenuView:334 |
| B7 | **MEDIUM** | 菜单始终画在屏幕中心，但动画从触摸点展开 | `onSizeChanged` 设置 `centerX = w/2`，动画 pivot 是触摸坐标 | RingMenuView:285 |
| B8 | **LOW** | 10px 内环间距是绝对像素，不适配不同屏幕密度 | 硬编码 `10f` | RingMenuView:199 |

---

## 三、这些问题的分类

### A 类：触摸/手势逻辑问题（交互流程）

| Bug | 本质 |
|-----|------|
| B1 | 手势状态机缺少"快速抬起=语音助手"的分支 |
| B2 | 坐标系不一致：绘制用 12 点钟为 0°，触摸用 3 点钟为 0° |
| B3 | 动画期间 View 不可见，触摸事件丢失 |
| B4 | 命令在 Command 层和 Event 处理层重复执行 |

### B 类：绘制/渲染问题（视觉呈现）

| Bug | 本质 |
|-----|------|
| B5 | 发光算法数学错误 |
| B6 | 子菜单角度计算逻辑错误 |
| B7 | 菜单中心点与动画中心点不一致 |
| B8 | 尺寸不适配 |

---

## 四、中介者模式（RingMenuCoordinator）能解决什么？

### ✅ 能解决的问题

| Bug | 怎么解决 |
|-----|---------|
| **B1** | Coordinator 统一管理手势状态机，`ACTION_UP` 时根据 `selectedIndex` 判断：无选中→语音助手，有选中→执行命令。不再依赖两个独立组件的回调拼接 |
| **B2** | Coordinator 内部统一角度计算，绘制和触摸共用同一个 `calculateIndex(angle)` 方法，消除坐标系不一致 |
| **B3** | Coordinator 持有内部状态，不依赖 View 是否可见。手指移动时 Coordinator 直接更新选中索引，View 只负责绘制。即使动画未完成，状态已正确 |
| **B7** | Coordinator 统一管理 `centerX/centerY`，绘制和动画使用同一个坐标 |
| **回调碎片化** | 三方耦合（GestureVoiceLauncher + RingMenuView + MainActivity）合并为一个 Coordinator + 一个事件流 |

### ❌ 不能解决的问题（需要硬编码修复）

| Bug | 原因 | 必须怎么修 |
|-----|------|-----------|
| **B4** | `StopNavigationCommand` 内部直接执行副作用 + 返回事件导致重复执行。这是命令实现层的设计问题，与交互层无关 | 修改 `StopNavigationCommand`：要么只返回事件（不直接执行），要么不返回事件（直接执行并跳过 `handleCommandEvent`） |
| **B5** | `brighten()` 的数学错误，纯算法 bug | 修复 `coerceAtMost` 逻辑，改为用 `glowAlpha` 控制 RGB 提亮而非 alpha |
| **B6** | 子菜单角度计算逻辑错误，纯算法 bug | 重写子菜单角度分配：以父扇区中心为基准，均匀分布子项 |
| **B8** | 10px 是绝对像素 | 改为 `minDim * 比例` |

---

## 五、解决方案对比

### 问题 B1：长按松手→语音助手

| 方案 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **A. 中介者模式** | Coordinator 状态机：`IDLE→PRESSING→LONG_PRESS→RING_MENU`。`ACTION_UP` 时如果 `RING_MENU` 状态且 `selectedIndex==-1` → 发射 `VoiceStart` | 一劳永逸，手势逻辑集中 | 改动大，需重写 GestureVoiceLauncher + RingMenuView 触摸逻辑 |
| **B. 最小修复** | 在 `GestureVoiceLauncher.ACTION_UP` 中加判断：如果 `isLongPressing && 没有滑动` → 调 `onVoiceAssistant()`，否则调 `onRingMenuConfirm()` | 改动极小（3 行） | 手势逻辑仍然分散在两个类中 |
| **推荐** | **先 B 后 A**。B 是快速止血，A 是长期方案 |

### 问题 B2：角度偏移 90°

| 方案 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **A. 绘制偏移** | `onDraw` 中把起始角度减 90° | 改动最小（1 行） | 绘制和触摸仍然用不同坐标系，后续维护容易再错 |
| **B. 触摸偏移** | `handleMove` 中 `angle = (rawAngle + 90f) % 360f` | 改动最小（1 行） | 同上 |
| **C. 统一坐标系** | 绘制和触摸共用一个 `calculateSectorIndex(angle)` 方法，内部处理偏移 | 根治 | 改动中等 |
| **D. 中介者模式** | Coordinator 统一角度计算 | 根治 + 解耦 | 改动大 |
| **推荐** | **C**。一行代码都不多，但保证绘制和触摸永远一致 |

### 问题 B3：动画期间触摸丢失

| 方案 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **A. 去掉动画** | 直接 `visibility = VISIBLE/GONE` | 立即生效 | 视觉体验差 |
| **B. 缩短动画** | 350ms → 50ms | 简单 | 只是减少概率，不根治 |
| **C. 先设 VISIBLE 再动画** | `container.visibility = VISIBLE` 在动画之前，不在动画回调中 | 根治 | 需要检查 ViewTransition 实现 |
| **D. 中介者模式** | Coordinator 管理状态，不依赖 View 可见性 | 根治 + 解耦 | 改动大 |
| **推荐** | **C**。一行改动，立即修复 |

### 问题 B4：StopNavigation 双重执行

| 方案 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **A. Command 只返回事件** | `StopNavigationCommand.execute()` 不直接调 `stopNavigation()`，只返回 `CommandEvent.StopNavigation` | 统一模式 | 需要改 Command |
| **B. Command 只执行不返回** | `execute()` 直接执行，返回 `null` | 简单 | 与其他 Command 模式不一致 |
| **C. handleCommandEvent 中加守卫** | 检查是否已在 Command 中执行过 | 兜底 | 脆弱 |
| **推荐** | **A**。所有 Command 统一为"只返回事件，不执行副作用"，副作用全在 `handleCommandEvent` 中 |

### 问题 B5/B6/B8：纯算法 bug

| Bug | 修复方式 |
|-----|---------|
| B5 `brighten()` | `val boost = 1.0f + (glowAlpha / 255f) * 0.3f; r = (r * boost).coerceAtMost(255)` |
| B6 子菜单角度 | 以父扇区中心为基准，`(parentCenter - childTotalAngle/2) + i * childAngle` |
| B8 10px 间距 | `val gap = minDim * 0.012f`（与 onSizeChanged 中其他比例一致） |

---

## 六、推荐实施路径

### 第一步：快速止血（改 4 个文件，修复 B1-B4）

| 改动 | 文件 | 行数 |
|------|------|------|
| B1 修复：ACTION_UP 判断是否有选中，无选中→语音助手 | GestureVoiceLauncher.kt | ~10 行 |
| B2 修复：统一角度计算方法 | RingMenuView.kt | ~5 行 |
| B3 修复：动画前先设 VISIBLE | MainActivity.kt | ~2 行 |
| B4 修复：StopNavigationCommand 只返回事件 | StopNavigationCommand.kt | ~3 行 |
| B5/B6/B8 修复 | RingMenuView.kt | ~20 行 |

**预计改动量：~40 行代码，4 个文件**

### 第二步：中介者模式重构（长期方案）

| 改动 | 说明 |
|------|------|
| 新建 `RingMenuCoordinator.kt` | 合并 GestureVoiceLauncher + RingMenuView 触摸逻辑 |
| 重写 `RingMenuView.kt` | 只保留绘制逻辑，移除 onTouchEvent |
| 重写 `GestureVoiceLauncher.kt` | 删除（逻辑移入 Coordinator） |
| 更新 `MainActivity.kt` | 只监听 `coordinator.events`，不直接操作 View |

**预计改动量：~300 行新增，~200 行删除**

### 第三步：动画系统接入（可选）

| 改动 | 说明 |
|------|------|
| `RingMenuCoordinator` 驱动 `RingMenuView` 的可动画属性 | 弹出/收起/高亮/二级展开 |
| 移除 `ViewTransition` 直接操作菜单容器 | 由 Coordinator 内部管理 |

---

## 七、中介者模式架构图（目标状态）

```
用户手指按下
  │
  ▼
Activity.dispatchTouchEvent()
  │
  └─→ RingMenuCoordinator.onTouchEvent()     ← 唯一入口
        │
        │  内部状态机：
        │  IDLE → PRESSING(计时500ms) → RING_MENU(弹出) → SELECTING(滑动)
        │       → EXECUTING(松手) → IDLE(收起)
        │
        │  内部持有：
        │  - RingMenuView（只负责绘制，不处理触摸）
        │  - MenuConfig（菜单数据）
        │  - 角度计算统一方法
        │
        └─→ events: SharedFlow<InteractionEvent>    ← 唯一出口
              │
              ▼
          Activity.collect
              │
              ├─ MenuExecute(item) → CommandRouter.execute(item.command)
              ├─ VoiceStart → voiceInteractionManager.startListening()
              ├─ MenuDismiss → 恢复地图操作
              └─ MapScroll → 不处理（事件穿透到地图）
```

**对比现有架构：**

| 维度 | 现有 | 中介者模式后 |
|------|------|-------------|
| 触摸入口 | 2 个（GestureVoiceLauncher + RingMenuView） | 1 个（Coordinator） |
| 角度计算 | 2 套（绘制一套、触摸一套，不一致） | 1 套（统一方法） |
| 状态管理 | 3 处分散（GestureVoiceLauncher.isLongPressing + RingMenuView.selectedIndex + ringMenuContainer.visibility） | 1 处（Coordinator 内部状态机） |
| 事件出口 | 3 个回调（GestureCallback + onItemExecuted + onCenterClicked） | 1 个（SharedFlow） |
| 新增手势 | 改 GestureVoiceLauncher + MainActivity 回调 | 只改 Coordinator |

---

## 八、总结

| 问题类型 | 能否用中介者模式解决 | 推荐方案 |
|---------|-------------------|---------|
| 手势状态机混乱（B1） | ✅ 能 | 先快速修复，后用 Coordinator 重写 |
| 角度坐标系不一致（B2） | ✅ 能 | 统一计算方法即可，不必等 Coordinator |
| 动画期间触摸丢失（B3） | ✅ 能 | 一行修复（先 VISIBLE 再动画） |
| 命令双重执行（B4） | ❌ 不能 | 需改 Command 层设计（只返回事件，不执行副作用） |
| 发光算法错误（B5） | ❌ 不能 | 纯数学修复 |
| 子菜单角度重叠（B6） | ❌ 不能 | 纯几何修复 |
| 菜单中心偏移（B7） | ✅ 能 | Coordinator 统一坐标 |
| 像素不适配（B8） | ❌ 不能 | 改为比例值 |

**结论：中介者模式能解决 4/8 个问题（B1/B2/B3/B7），但 B4/B5/B6/B8 必须单独硬编码修复。建议先快速修复全部 8 个 bug（~40 行），再用中介者模式做长期重构。**
