**目前还没有采用完整的“数据平滑+动画插值”方案。**

你们现有的代码已经具备了**“视觉动画层”**（`Animations.kt`、`AnimatorUtils.kt`），但**缺失了最关键的“传感器数据平滑层”**。这就是为什么小米指南针很稳，而你们的会抖。

### 🧐 现状分析：你目前只做了“数学计算”，没做“数据滤波”

根据你提供的 `RadialGeometry.kt` 和 `GridCaptureFragment.kt`：

1. **`RadialGeometry.kt`** 只负责坐标映射和角度归一化（把触摸点或方向角换算成0~360°）。它**没有**任何滤波或平滑逻辑。
2. **`GridCaptureFragment.kt`** 中的 `currentBearing` 直接来自 `CompassProvider`，而 `CompassProvider` 目前看起来只是将原始传感器数据原样转发（`filteredHeading` 并不存在）。

> **现状**：你的逻辑是 `原始传感器数据 → 角度归一化 → 对齐判定 / UI 渲染`。  
> 这就像把收音机的白噪音直接放大播放，没有经过滤波处理，当然会剧烈抖动。

---

### ✅ 你们有的“一半”：UI 动画插值层

好消息是，你们的基础设施很完善。在 `Animations.kt` 和 `AnimatorUtils.kt` 中，已经封装好了 `ValueAnimator` 和 `AnimatorSet`，可以实现“指针平滑转动到目标角度”。

**缺失的环节**：把“不断抖动的原始角度”变成“平滑变化的中间值”，再喂给 UI 动画。

---

### 🛠️ 如何补齐：在 `CompassProvider` 中加一层低通滤波

你们不需要重写指南针，只需要在 `CompassProvider` 里加一个简单的“低通滤波器”（Low-Pass Filter）。

**示例实现（在 `CompassProvider.kt` 中修改）**：

```kotlin
class CompassProvider {
    // 平滑后的角度（初始值）
    private var filteredHeading: Float = 0f
    // 平滑系数：越小越平滑（0.1~0.3），延迟也越大
    private val alpha = 0.15f 

    // 传感器数据回调（假设由 SensorManager 触发）
    fun onSensorChanged(event: SensorEvent) {
        val rawHeading = event.values[0] // 原始角度

        // --- 核心：一阶低通滤波 ---
        // 公式：新值 = 旧值 * (1 - alpha) + 原始值 * alpha
        if (filteredHeading == 0f) {
            filteredHeading = rawHeading // 首次赋值
        } else {
            // 处理 0° / 360° 跳变（避免从359°回0°时突变）
            var diff = rawHeading - filteredHeading
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            filteredHeading += diff * alpha
        }
        
        // 保证结果在 0~360°
        filteredHeading = (filteredHeading + 360) % 360

        // 将 filteredHeading 分发给观察者（而不是 rawHeading）
        _headingFlow.value = filteredHeading
    }
}
```

这样改完后，`GridCaptureFragment` 拿到的 `currentBearing` 就已经是“迟钝”且平滑的值了。

---

### 🎯 总结

| 方案环节 | 是否具备 |
| :--- | :--- |
| **传感器数据源**（CompassProvider） | ✅ 有 |
| **原始数据处理层**（低通/卡尔曼滤波） | ❌ **缺失**（这是抖动根源） |
| **角度归一化工具**（RadialGeometry） | ✅ 有 |
| **UI 动画插值层**（Animations） | ✅ 有（你们已经有了！） |

所以，你只需要**在 `CompassProvider` 里把 `onSensorChanged` 的数据做一次低通滤波**，再下发给 Fragment，就能获得接近小米指南针的顺滑体验了。