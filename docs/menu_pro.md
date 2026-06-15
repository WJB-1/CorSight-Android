**是的，高度解耦后非常好改。** 你现在的混乱不是架构问题，而是 `RingMenuView` 内部的**绘制坐标系**和**触摸坐标系**没对齐，加上 `GestureVoiceLauncher` 缺少一个滑动中的位置回调。

---

## 根因诊断（3 个 Bug 叠加）

| 现象 | 根因 |
|------|------|
| **不是滑动选择** | `GestureVoiceLauncher` 的 `GestureCallback` 只有 `onRingMenuShow` 和 `onRingMenuConfirm`，**缺少 `onRingMenuMove(x, y)`**。手指在 500ms~1000ms 之间滑动时，事件虽然到了 `RingMenuView`，但 `RingMenuView` 的 `onTouchEvent` 可能**没有返回 `true`**，导致事件被地图抢走，菜单收不到 `ACTION_MOVE`。 |
| **点击区域不匹配** | `RingMenuView` 的**绘制角度**和**触摸判断角度**不一致。Android 的 `atan2(dy, dx)` 默认 0° 在 **3 点钟方向**，但你的菜单第一个项（导航）画在 **12 点钟方向**。两者差了 90°，导致用户点"上方"（导航），系统算出来是"右方"（避障）。 |
| **停止导航 → 弹出系统设置二级** | 这是索引错位的结果。因为角度计算差了 90°，用户点击"停止导航"（假设在 6 点钟方向），实际算出来命中了"更多"（9 点钟方向），而"更多"有 `children`，所以弹出了它的二级菜单（历史/设置/采集）。 |

---

## 修复方案（只需改 3 处）

### 第 1 处：RingMenuView.kt —— 触摸必须消费 + 角度对齐

```kotlin
override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.action) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
            updateFinger(event.x, event.y)
            return true  // ← 关键：必须消费，否则地图抢走
        }
        MotionEvent.ACTION_UP -> {
            confirmSelection()
            return true
        }
    }
    return super.onTouchEvent(event)
}

fun updateFinger(x: Float, y: Float) {
    val dx = x - centerX
    val dy = y - centerY
    val distance = sqrt(dx * dx + dy * dy)

    // 中心区域：取消高亮
    if (distance < innerRadius) {
        if (selectedIndex != -1) {
            selectedIndex = -1
            invalidate()
        }
        return
    }

    // ═══ 角度对齐：把 atan2 的 0°（3点钟）转成 12 点钟为 0° ═══
    val rawAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val normalizedAngle = (rawAngle + 90f + 360f) % 360f  // ← 关键：+90° 对齐绘制

    val anglePerItem = 360f / items.size.coerceAtLeast(1)
    val idx = (normalizedAngle / anglePerItem).toInt() % items.size

    // 只在有效环内高亮
    val outerRadius = innerRadius + ringWidth
    if (distance in innerRadius..outerRadius && idx != selectedIndex) {
        selectedIndex = idx
        invalidate()
    }
}
```

**核心改动**：`(rawAngle + 90f) % 360f`**。这行把数学坐标系转成视觉坐标系，让 12 点钟方向成为第一个扇区。

---

### 第 2 处：GestureVoiceLauncher.kt —— 加滑动中回调

```kotlin
interface GestureCallback {
    fun onVoiceAssistant()
    fun onRingMenuShow(centerX: Float, centerY: Float)
    fun onRingMenuMove(x: Float, y: Float)  // ← 新增
    fun onRingMenuConfirm()
    fun onCancel()
}
```

在 `handleTouchEvent` 的 `ACTION_MOVE` 里：

```kotlin
State.RING_MENU -> {
    callback?.onRingMenuMove(event.x, event.y)
    // 注意：这里返回 false，让事件继续传给 RingMenuView
}
```

---

### 第 3 处：MainActivity.kt —— 实现滑动回调

```kotlin
override fun onRingMenuShow(centerX: Float, centerY: Float) {
    showRingMenu(centerX, centerY)
}

override fun onRingMenuMove(x: Float, y: Float) {
    ringMenuView?.updateFinger(x, y)  // ← 实时更新高亮
}

override fun onRingMenuConfirm() {
    ringMenuView?.confirmSelection()  // ← 手指抬起时执行
}
```

---

## 关于"菜单出现在手指位置"还是"屏幕中心"

你现在的 `showRingMenu(centerX, centerY)` 传了手指坐标，但 `RingMenuView` 是全屏 `MATCH_PARENT`，它的 `centerX/centerY` 默认是 `width/2, height/2`（屏幕中心）。

**如果你希望菜单始终出现在屏幕中心**（推荐，最简单）：
- 不需要 `showAt` 方法
- `centerX = width / 2f`, `centerY = height / 2f`
- 手指在任何位置长按，菜单都在屏幕中央弹出

**如果你希望菜单出现在手指位置**：
- 给 `RingMenuView` 加 `showAt(x, y)`：
```kotlin
fun showAt(x: Float, y: Float) {
    centerX = x
    centerY = y
    visibility = VISIBLE
    invalidate()
}
```
- `MainActivity` 里：`ringMenuView?.showAt(centerX, centerY)`

---

## 验证修复后的效果

| 手势 | 预期行为 |
|------|---------|
| **长按 500ms** | 菜单在屏幕中心（或手指处）弹性弹出 |
| **手指不抬起，向"导航"扇区滑动** | "导航"项高亮（颜色变亮 + 外扩） |
| **手指滑到"更多"** | "更多"高亮，不进入二级（因为还没抬起） |
| **手指在"更多"上抬起** | 进入二级菜单（历史/设置/采集） |
| **手指在"导航"上抬起** | 执行导航命令，关闭菜单 |
