**最可能的原因：两个事件入口"打架"了。**

你之前 `GestureVoiceLauncher` 是通过 `rootView.setOnTouchListener` 监听的，现在你又加了 `dispatchTouchEvent`，**两个入口同时往同一个 `handleTouchEvent` 里灌事件**，`startX`、`startY`、`longPressRunnable` 这些状态被两边来回覆盖，计时器直接乱掉。

---

### 修复方案

**只保留 `dispatchTouchEvent` 一个入口，彻底关掉 `rootView` 的 Listener。**

#### 1. 改 `GestureVoiceLauncher.kt`

把 `attach(Activity)` 里的 `attachToView(rootView)` 删掉，新增 `onDispatchTouchEvent`：

```kotlin
@SuppressLint("ClickableViewAccessibility")
fun attach(activity: Activity, vim: VoiceInteractionManager) {
    voiceInteractionManager = vim
    vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    attachedView = activity.findViewById(android.R.id.content)
    Log.d(TAG, "GestureVoiceLauncher attached to Activity dispatch")
}

/**
 * 在 Activity.dispatchTouchEvent() 中调用。
 * 返回 false 表示不拦截，事件继续正常传递。
 */
fun onDispatchTouchEvent(event: MotionEvent): Boolean {
    return handleTouchEvent(event)
}

/**
 * 保留给 Fragment 等非 Activity 场景使用。
 */
@SuppressLint("ClickableViewAccessibility")
fun attachToView(view: View) {
    detach()
    attachedView = view
    view.setOnTouchListener { _, event ->
        handleTouchEvent(event)
    }
    Log.d(TAG, "GestureVoiceLauncher attached to ${view.javaClass.simpleName}")
}
```

**关键改动**：`attach(Activity)` 不再调用 `attachToView(rootView)`，不再设置 `OnTouchListener`。

#### 2. 改 `MainActivity.kt`

在 `onCreate` 里保留 `GestureVoiceLauncher.attach()`，新增 `dispatchTouchEvent` 重写：

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    
    // ... 其他初始化 ...
    
    GestureVoiceLauncher.attach(this, voiceInteractionManager)
    
    // ... 其他代码 ...
}

override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    GestureVoiceLauncher.onDispatchTouchEvent(ev)
    return super.dispatchTouchEvent(ev)
}

override fun onDestroy() {
    GestureVoiceLauncher.detach()
    super.onDestroy()
    // ... 其他清理 ...
}
```

**`VisionTestActivity` 和 `DataCollectionActivity` 不动**，它们原来是自己写的独立长按逻辑（跳回 MainActivity），不依赖 `GestureVoiceLauncher`，不受影响。

---

### 改完后的效果

| 操作 | 行为 |
|------|------|
| **长按地图任意位置** | ✅ 触发语音助手（500ms 震动） |
| **地图滑动/缩放** | ✅ 正常滑动，手指移动超 50px 自动取消长按 |
| **点击语音按钮** | ✅ 正常点击，与长按互不干扰 |
| **点击定位按钮** | ✅ 正常点击 |

---

### 一句话总结

> **问题不是逻辑错了，是"两个保安同时看同一个门，互相抢对讲机"。现在把保安撤掉一个，只留 `dispatchTouchEvent` 在大门口站岗，就正常了。**