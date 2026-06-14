

## 1. 弹出动画（Entrance）

| 效果 | 实现方式 | 代码核心 |
|------|---------|---------|
| **整体弹性弹出** | `ValueAnimator.ofFloat(0f, 1f)` + `OvershootInterpolator` | 菜单 scale 从 0→1，带弹性回弹 |
| **扇形级联展开** | `AnimatorSet` + `startDelay` | 每个扇形延迟 50ms 依次展开 |
| **旋转展开** | `ValueAnimator.ofFloat(0f, 360f)` | 整个环从 0° 旋转到完整圆 |
| **淡入** | `ValueAnimator.ofInt(0, 255)` | alpha 通道 0→255 |

```kotlin
// 弹性弹出（最常用）
val showAnim = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = 350
    interpolator = OvershootInterpolator(1.8f) // 超过1.0再回弹，手感Q弹
    addUpdateListener { animator ->
        val scale = animator.animatedValue as Float
        menuScale = scale
        menuAlpha = (255 * scale).toInt()
        invalidate()
    }
}

// 级联展开：每个扇形依次弹出
val itemAnims = items.mapIndexed { index, _ ->
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300
        startDelay = index * 50L // 50ms 级联
        interpolator = OvershootInterpolator(1.5f)
        addUpdateListener { /* 单个扇形 scale */ }
    }
}
AnimatorSet().apply { playTogether(itemAnims) }.start()
```

---

## 2. 选中/高亮动画（Selection）

| 效果 | 实现方式 |
|------|---------|
| **扇形外扩** | `ValueAnimator.ofFloat(innerR, innerR + 12f)` |
| **颜色渐变** | `ValueAnimator.ofArgb(normalColor, highlightColor)` |
| **文字放大** | `ValueAnimator.ofFloat(textSize, textSize * 1.2f)` |
| **呼吸发光** | `ValueAnimator.ofInt(0x00, 0x44)` + `INFINITE/REVERSE` |
| **描边显现** | `ValueAnimator.ofFloat(0f, 4f)` 控制 strokeWidth |

```kotlin
// 颜色过渡（选中时）
val colorAnim = ValueAnimator.ofArgb(normalColor, brightenColor).apply {
    duration = 150
    addUpdateListener { paintSector.color = it.animatedValue as Int }
}

// 呼吸发光（持续）
val glowAnim = ValueAnimator.ofInt(0x00, 0x44).apply {
    duration = 800
    repeatCount = ValueAnimator.INFINITE
    repeatMode = ValueAnimator.REVERSE
    addUpdateListener { glowAlpha = it.animatedValue as Int }
}
```

---

## 3. 滑动跟随（Finger Tracking）

| 效果 | 实现方式 |
|------|---------|
| **高亮平滑迁移** | 旧选中项收缩 + 新选中项展开，同时播放 |
| **角度指示器** | 一个小圆点 `ObjectAnimator.ofFloat(indicator, "rotation", fromAngle, toAngle)` |
| **中心磁吸** | 手指靠近某扇区中心时，该扇形轻微向手指方向"探头" |

```kotlin
// 高亮切换：旧项收缩，新项展开
val oldShrink = ValueAnimator.ofFloat(oldExpansion, 0f).apply { duration = 100 }
val newExpand = ValueAnimator.ofFloat(0f, 12f).apply { duration = 150 }
AnimatorSet().apply { play(oldShrink).before(newExpand) }.start()
```

---

## 4. 二级目录过渡（Transition）

| 效果 | 实现方式 |
|------|---------|
| **主菜单缩小退后** | `ValueAnimator.ofFloat(1f, 0.6f)` + `alpha 255→100` |
| **子菜单旋转进入** | 子菜单从父扇形角度为起点，scale 0→1 |
| **中心按钮变形** | "关闭"文字缩小消失，"返回"文字放大出现 |
| **环间飞入** | 子菜单项从父扇形中心飞入各自位置 |

```kotlin
// 进入二级：主菜单后退，子菜单弹出
val mainShrink = ValueAnimator.ofFloat(1f, 0.7f).apply { duration = 200 }
val subShow = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = 250
    interpolator = OvershootInterpolator(1.5f)
}
AnimatorSet().apply { play(mainShrink).with(subShow) }.start()
```

---

## 5. 关闭动画（Dismiss）

| 效果 | 实现方式 |
|------|---------|
| **整体加速消失** | `ValueAnimator.ofFloat(1f, 0f)` + `AccelerateInterpolator` |
| **扇形依次收回** | 反向级联，最后一个扇形最先收 |
| **旋转闭合** | 360°→0° 同时 scale→0 |
| **中心塌陷** | 所有扇形向中心收缩 |

```kotlin
// 加速关闭
val dismissAnim = ValueAnimator.ofFloat(1f, 0f).apply {
    duration = 200
    interpolator = AccelerateInterpolator() // 越来越快
    addUpdateListener {
        menuScale = it.animatedValue as Float
        if (menuScale < 0.01f) visibility = GONE
        invalidate()
    }
}
```

---

## 6. 执行反馈（Confirm Feedback）

| 效果 | 实现方式 |
|------|---------|
| **心跳确认** | scale 1f→1.3f→0.9f→1f，200ms |
| **颜色闪烁** | 高亮色→白色→高亮色（ofArgb） |
| **涟漪扩散** | 从扇形中心向外画圆，radius 0→max，alpha 255→0 |
| **震动+闪光** | `Vibrator` + `ofArgb` 同时触发 |

```kotlin
// 心跳：确认执行时
val heartbeat = ValueAnimator.ofFloat(1f, 1.3f, 0.9f, 1f).apply {
    duration = 300
    addUpdateListener { selectionScale = it.animatedValue as Float }
}

// 涟漪：执行后向外扩散
val ripple = ValueAnimator.ofFloat(0f, 200f).apply {
    duration = 400
    addUpdateListener {
        rippleRadius = it.animatedValue as Float
        rippleAlpha = (255 * (1f - it.animatedFraction)).toInt()
    }
}
```

---

## 7. 环境/背景动画（Ambient）

| 效果 | 实现方式 |
|------|---------|
| **背景遮罩淡入** | `ValueAnimator.ofInt(0, 128)` 控制黑色遮罩 alpha |
| **地图变暗** | 在地图上层盖一个 View，alpha 0→0.3 |
| **中心按钮呼吸** | 持续轻微 scale 1f↔1.05f，提示用户"点这里关闭" |
| **未选中项微动** | 所有扇形轻微随时间偏移角度，像浮动 |

```kotlin
// 背景遮罩
val dimAnim = ValueAnimator.ofInt(0, 80).apply {
    duration = 300
    addUpdateListener { backgroundDimPaint.alpha = it.animatedValue as Int }
}

// 中心按钮呼吸（持续）
val breathe = ValueAnimator.ofFloat(1f, 1.08f).apply {
    duration = 1200
    repeatCount = INFINITE
    repeatMode = REVERSE
    addUpdateListener { centerButtonScale = it.animatedValue as Float }
}
```

---

## 组合示例：完整的"弹出→选中→执行→关闭"动画链

```kotlin
fun playFullSequence(selectedIndex: Int) {
    // 1. 弹出
    val show = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 350
        interpolator = OvershootInterpolator(1.8f)
    }
    
    // 2. 选中高亮
    val highlight = ValueAnimator.ofArgb(colorNormal, colorSelected).apply {
        duration = 150
        startDelay = 350 // 等弹出完
    }
    
    // 3. 执行心跳
    val heartbeat = ValueAnimator.ofFloat(1f, 1.3f, 1f).apply {
        duration = 250
        startDelay = 500
    }
    
    // 4. 关闭
    val dismiss = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 200
        interpolator = AccelerateInterpolator()
        startDelay = 750
    }
    
    AnimatorSet().apply {
        playSequentially(show, highlight, heartbeat, dismiss)
        start()
    }
}
```