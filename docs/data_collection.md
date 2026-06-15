我看了你提供的街景采样模块代码，发现三个问题都有明确的改进空间。下面逐一分析原因并给出可直接落地的修复方案。

---

## 1. GPS 接收不到信号

### 问题定位
`FreeCaptureFragment`、`GridCaptureFragment` 和 `RetakeFragment` 都使用了 `LocationProvider`，但存在以下隐患：

- **未检查 GPS 是否开启**：即使有权限，用户关闭了位置开关，`locationProvider.observe()` 也会永远收不到有效位置。
- **超时策略不完善**：`observe(3000L)` 只是每隔 3 秒回调一次，如果 GPS 冷启动需要 10–30 秒，界面会一直显示无效数据。
- **精度阈值未反馈**：`GridCaptureFragment` 和 `FreeCaptureFragment` 没有像 `RetakeFragment` 那样显示定位精度和距离校验，用户不知道是信号差还是无定位。

### 修复方案

#### 1) 在 Fragment 中添加 GPS 状态监听和提示
```kotlin
// 在 onCreateView 或 onViewCreated 中添加
val locationRequest = LocationRequest.Builder(3000L).setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY).build()
locationProvider.observe(locationRequest).collectLatest { loc ->
    if (loc.accuracy > 20f) {
        tvDebugOverlay.setTextColor(Color.RED)
        tvDebugOverlay.text = "⚠ GPS 精度不足 (${loc.accuracy.toInt()}m)"
        // 禁止拍照
        shutterBtn.isEnabled = false
    } else {
        tvDebugOverlay.setTextColor(Color.WHITE)
        tvDebugOverlay.text = "✅ GPS 已锁定 (${loc.accuracy.toInt()}m)"
        shutterBtn.isEnabled = true
    }
}
```

#### 2) 增加主动检查 GPS 是否打开的入口
在 `LocationProvider` 中增加方法（假设你已有该 provider 源码）：
```kotlin
fun isLocationEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
           locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}
```
在 Fragment 中提示用户开启：
```kotlin
if (!locationProvider.isLocationEnabled(requireContext())) {
    AlertDialog.Builder(requireContext())
        .setTitle("位置服务未开启")
        .setMessage("请开启 GPS 以采集准确的经纬度")
        .setPositiveButton("去设置") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        .setNegativeButton("取消", null)
        .show()
}
```

#### 3) 延长首次定位超时
`observe()` 的超时参数应改为更大的值（如 15 秒），并在首次获取到有效位置前显示“正在定位...”。推荐使用 `getLastLocation(timeoutMs)` 并配合重试。

---

## 2. 横屏模式必须解除旋转锁

### 问题根源
`CaptureHubActivity` 在 Manifest 中未设置 `screenOrientation`，默认会跟随系统方向锁定策略。即使用户开启了自动旋转，Activity 会重建并重新绑定相机，但预览画面仍可能出现拉伸；而一旦用户锁定了竖屏，整个 Activity 就不会旋转，导致 UI 被截断。

### 为什么相机 App 可以？
多数相机 App 在 Manifest 中声明了：
```xml
android:screenOrientation="fullSensor"
android:configChanges="orientation|screenSize"
```
这样即便系统方向锁定为竖屏，App 仍然可以感应物理旋转并调整界面方向（相机预览画面通过 `setTargetRotation` 自动适配）。

### 修复方案

#### 步骤 1：修改 AndroidManifest.xml
```xml
<activity
    android:name=".collection.ui.hub.CaptureHubActivity"
    android:screenOrientation="fullSensor"
    android:configChanges="orientation|screenSize|keyboardHidden" />
```

#### 步骤 2：在 CameraX 绑定中传递当前屏幕旋转角度
CameraX 会根据 `PreviewView` 的大小自动拉伸，但为了获得正确的方向元数据，需要在绑定时设置 `UseCaseGroup` 的旋转：
```kotlin
val camera = provider.bindToLifecycle(
    viewLifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview,
    imageCapture
)
// 设置图像捕获的旋转参考系
imageCapture?.targetRotation = requireActivity().windowManager.defaultDisplay.rotation
```
如果使用 `PreviewView`，它内部会自动适配预览方向，但最好在 `onConfigurationChanged` 中重新绑定相机（或更新旋转）。

#### 步骤 3：适配横屏布局
创建 `res/layout-land/fragment_free_capture.xml` 和 `fragment_grid_capture.xml`，将快门按钮、提示文字等重新排布，避免竖屏布局在横屏时过于拥挤。例如：横屏时将预览画面放到左侧，控制和信息放到右侧。

#### 步骤 4：处理 CompassProvider 的屏幕旋转
你已经注入了 `CompassProvider` 并且在 `onConfigurationChanged` 中调用了 `setScreenRotation`，这很好，但要确认 `CompassProvider` 内部能正确将传感器坐标转换成屏幕显示方向（即方向角用于 UI 指示器时减去屏幕旋转偏移）。

---

## 3. 相机快门键太丑，美化方案

### 当前使用
布局中的 `shutterBtn` 很可能是一个普通的 `Button` 或 `ImageView`，没有样式。

### 美化建议

#### 方案 A：使用 Material Design 的 FloatingActionButton（推荐）
在布局文件中：
```xml
<com.google.android.material.floatingactionbutton.FloatingActionButton
    android:id="@+id/shutterBtn"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="bottom|center_horizontal"
    android:layout_marginBottom="32dp"
    android:src="@drawable/ic_camera"
    app:fabSize="normal"
    app:elevation="4dp"
    app:rippleColor="@android:color/white"
    app:backgroundTint="@color/white" />
```
效果：白色圆形按钮，中心有相机图标，点击有涟漪效果。

#### 方案 B：自定义圆形按钮 + 动画
如果不想引入 Material 库，可以自己画：
```xml
<FrameLayout
    android:id="@+id/shutterBtn"
    android:layout_width="72dp"
    android:layout_height="72dp"
    android:background="@drawable/bg_shutter"
    android:clickable="true"
    android:focusable="true">
    <View
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:layout_gravity="center"
        android:background="@drawable/bg_shutter_inner" />
</FrameLayout>
```
`bg_shutter.xml`（圆形白色渐变 + 阴影）：
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@android:color/white"/>
    <stroke android:width="2dp" android:color="#DDDDDD"/>
    <size android:width="72dp" android:height="72dp"/>
</shape>
```
配合按压缩放动画：
```kotlin
shutterBtn.setOnTouchListener { v, event ->
    when (event.action) {
        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
    }
    false
}
```

#### 方案 C：在八方向模式下增加对齐振动反馈
你已经在 `checkAlignment()` 中实现了首次对准振动，这很好，能让用户感知到“可以拍了”。还可以添加相机快门模拟音效（可选）。

---

## 总结与行动清单

| 问题 | 关键修改点 | 代码位置 |
|------|-----------|----------|
| GPS 无信号 | 增加位置开关检测、精度提示、延长超时 | `FreeCaptureFragment`, `GridCaptureFragment`, `LocationProvider` |
| 横屏旋转锁 | Manifest 加 `fullSensor` + `configChanges`，更新相机旋转 | `AndroidManifest.xml`, `onCreateCamera` |
| 快门键丑 | 改用 FAB 或自定义圆形，添加按压动画 | 对应 fragment 的布局文件及 kotlin 触摸事件 |

你可以优先处理横屏和快门美化，这两者改动小、见效快；GPS 问题需要结合你的 `LocationProvider` 实现来调整。如果需要具体某个文件的完整修改代码，可以告诉我。