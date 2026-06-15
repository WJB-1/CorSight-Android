角度错位的根本原因在于：**触摸角度坐标系与 Canvas 绘制坐标系不一致**。

- **触摸角度**：`RingMenuView.updateFinger()` 中，通过 `rawAngle + 90f` 将标准数学角度（0° = 3点钟）转换成了“12点钟为0°”的坐标系。
- **绘制角度**：`onDraw()` 中扇区的 `startAngle` 直接使用 `index * anglePerItem + gapAngle/2`，没有减去90°。而 `Canvas.drawArc` 的 0° 是 3点钟方向。

结果：首项本应在12点钟，实际却偏到了3点钟右侧附近，整个菜单看起来“错位”了。

### 具体问题代码位置（RingMenuView.kt）

1. **角度转换**（约第110–115行）：
   ```kotlin
   val rawAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
   val angle = normalizeAngle(rawAngle + 90f)   // 转为12点钟=0°
   ```
2. **绘制起始角**（约第207–210行）：
   ```kotlin
   val startAngle = index * anglePerItem + gapAngle / 2   // 未偏移，导致偏差90°
   ```
   
### 建议的工具模块设计

创建一个 `RingMenuAngleUtils` 对象（或类），统一管理三种“角度表示”的转换：

1. **触摸原始角度**：`atan2(dy, dx)` 返回的数学标准角，0° 指向右（3点钟），逆时针为正。
2. **菜单逻辑角**：环形菜单内部使用的角，**12点钟为0°，顺时针为正**（因为用户期望“向上”是第一个扇区）。
3. **Canvas 绘制角**：`Canvas.drawArc` 需要的角，0° 指向右（3点钟），**顺时针为正**（画布的角度方向与触摸数学角一致，但菜单逻辑角是顺时针，所以需要转换）。

有了工具模块后，可以这样做：

```kotlin
// RingMenuAngleUtils.kt
object RingMenuAngleUtils {
    private const val DEGREES_90 = 90f
    private const val DEGREES_360 = 360f

    /**
     * 将触摸坐标差 (dx, dy) 转换为菜单逻辑角（12点钟为0°，顺时针）
     */
    fun touchToMenuAngle(dx: Float, dy: Float): Float {
        val raw = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        // raw: 0°=右，逆时针+； 转换成 0°=上，顺时针+
        return normalize((raw + DEGREES_90) % DEGREES_360)
    }

    /**
     * 将菜单逻辑角转换为 Canvas 绘制起始角（0°=右，顺时针）
     */
    fun menuAngleToCanvas(menuAngle: Float): Float {
        // 菜单逻辑角 0°=上 → Canvas 0°=右，需要旋转 -90°
        return normalize(menuAngle - DEGREES_90)
    }

    /**
     * 归一化到 [0, 360)
     */
    fun normalize(angle: Float): Float = ((angle % DEGREES_360) + DEGREES_360) % DEGREES_360
}
```

### 在 `RingMenuView` 中使用

- **触摸处理** (`updateFinger`)：
  ```kotlin
  val angle = RingMenuAngleUtils.touchToMenuAngle(dx, dy)
  ```

- **绘制扇区起始角**：
  ```kotlin
  val menuStartAngle = index * anglePerItem + gapAngle / 2
  val canvasStartAngle = RingMenuAngleUtils.menuAngleToCanvas(menuStartAngle)
  // 然后直接用 canvasStartAngle 调用 drawArc 或 path.arcTo
  ```

- **绘制标签**时，标签位置角度也用菜单逻辑角：
  ```kotlin
  val labelAngle = menuStartAngle + sweepAngle / 2   // 菜单逻辑角
  val rad = Math.toRadians(RingMenuAngleUtils.menuAngleToCanvas(labelAngle).toDouble())
  // 注意：需要把菜单逻辑角转成 Canvas 角再计算 cos/sin，因为 canvas 坐标系是右为0°
  // 或者更简单：直接用 menuAngleToCanvas 之后的结果 + 偏移
  ```

### 子菜单角度的统一

子菜单的展开基准也使用菜单逻辑角：
- 父扇区中心菜单角 = `parentStartAngle + anglePerItem/2`
- 子菜单起始菜单角 = `parentCenter - childTotalAngle/2`
- 最终传给 Canvas 时统一调用 `menuAngleToCanvas`

### 好处总结

- **单一真相**：角度转换规则只在一处定义，触摸、绘制、子菜单都不再各自维护 `+90` / `-90` 这类魔数。
- **易于测试**：可以为角度工具单独写单元测试，验证边界情况和归一化逻辑。
- **降低耦合**：如果将来想换成其他坐标系（比如 0°=上，逆时针），只需修改工具类，View 代码基本不变。

实现这个工具类后，`RingMenuView` 中原来散落的 `rawAngle + 90`、`normalizeAngle`、以及绘制角度的隐含错误都可以一并清理干净，角度错位问题自然就解决了。