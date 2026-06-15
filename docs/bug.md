从日志看，问题出在 **CameraX 生命周期与双 SurfaceView 的释放时序严重错位** 上。你看到的“右半边静态渲染上一帧”本质上是 **一个 SurfaceView 的 BufferQueue 已经卡死/断开，但窗口层（SurfaceFlinger）仍保留着最后一帧的画面作为静态图层叠加**。

以下是日志里暴露的关键证据：

---

### 1. 你布局里同时存在两个 SurfaceView
日志在 `22:50:43.663` 和 `22:50:43.666` 连续 attach 了两个独立的 SurfaceView 到同一个 ViewRootImpl：
- `SurfaceView{6d7e56d}` 1600×1200
- `SurfaceView{9495690}` 1600×1200

并且两个都在独立走 `BLASTBufferQueue` 流程（`22:50:45.243` 和 `22:50:46.320` 分别 `acquireNextBufferLocked`）。

**结论**：你的页面里有两个相同尺寸的 Surface 在同时竞争/显示。如果模式切换逻辑只把 CameraX 的 Preview 重新绑定到其中一个，另一个就会停留在最后一帧。

---

### 2. 模式切换时 CameraDevice 发生致命错误（Error Code 3）
每次切换都伴随 `CameraDevice-JV-0` 的 `Device error received, code 3`：
```
22:50:46.011  Device error received, code 3, frame number 22...
22:50:52.498  Device error received, code 3, frame number 186...
22:50:56.190  Device error received, code 3, frame number 101...
```
Camera2 里 **Error Code 3 = ERROR_CAMERA_DEVICE**，属于致命错误，会直接炸掉当前 CameraCaptureSession。

这导致 CameraX 被迫走 **紧急关闭 → 立刻重新打开** 的恢复路径。但你的代码在旧设备还没彻底释放完时（`CameraDevice-JV-0 close: X` 还没打印），就已经创建了新的 Pipeline。

---

### 3. 旧 Surface 的释放严重滞后
看这组时间差（以最后一次切换为例）：
```
22:50:56.168  旧 UseCase DETACHED, surface closed
22:50:56.176  新 Pipeline 已经开始创建（DynamicRangeResolver 工作）
22:50:56.254  旧 CameraDevice 才真正 close: X
22:50:56.288  新 CameraDevice.onOpened()
```

**旧 CameraDevice 的 `close()` 耗时 78ms，但新 Pipeline 在 8ms 后就启动了**。这意味着：
- 旧 `ImageReader-4096x3072` 的 Surface 还没完全从系统中 disconnect；
- 新 Preview 已经向 CameraX 申请了新 Surface；
- 两个 Surface 的 BufferQueue 状态混乱，SurfaceFlinger 合成时把“已卡死的旧 Surface 最后一帧”和“新 Camera 流”叠在了一起。

---

### 4. RenderInspector 报告 BufferQueue Timeout
```
22:50:43.781  DequeueBuffer time out ... avg=9 ms
22:50:43.806  QueueBuffer time out ... avg=8 ms
22:50:45.187  DequeueBuffer time out ... avg=67 ms
```
这直接说明 SurfaceFlinger 的 Buffer 交换已经卡住。当 BufferQueue 卡住时，SurfaceView 不会黑屏，而是**把最后一帧当作静态图层继续显示**——这就是你看到的“右半边静态渲染上一帧”。

---

### 为什么“横屏正常，free 模式异常”？
最可能的解释是：
- **横屏模式**下，你的两个 SurfaceView 可能重叠在一起（或只用一个），或者横屏布局触发了完整的 Activity recreate，Surface 被系统强制重建了。
- **free 模式**下，你的布局把屏幕分成了左右两个区域（或两个 SurfaceView 并排），切换时只重新绑定了左侧/主预览的 Surface，**右侧的 SurfaceView 仍然关联着已经卡死的旧 Camera Session 的 BufferQueue**，于是它永远停在最后一帧。

---

### 建议修复方向

1. **检查布局里为什么会有两个 SurfaceView/PreviewView**  
   如果你只需要一个相机预览，确保布局里只有一个 `PreviewView`。另一个如果是用来做网格/覆盖层，应该用 **TextureView** 或 **普通 View + Canvas** 绘制，而不是再开一个 SurfaceView。

2. **模式切换时彻底释放相机，不要立刻重建**  
   在切换模式的逻辑里，先 `cameraProvider.unbindAll()`，然后**等待** `CameraDevice.StateCallback.onClosed()`（或至少等 `close: X` 日志出现）后再执行 `bindToLifecycle()`。目前你的代码是“关旧”和“开新”几乎同步进行的。

3. **给 PreviewView 设置固定的实现模式**  
   如果你确实需要复杂的 Surface 管理，显式指定：
   ```kotlin
   previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
   ```
   这会强制使用 TextureView，生命周期由 Android 视图系统管理，不会出现 SurfaceView 的 BufferQueue 残留问题（代价是略高的性能和延迟）。

4. **处理 CameraDevice Error Code 3 的恢复**  
   在 CameraX 的 `CameraSelector` 或自定义 `CameraErrorCallback` 中，捕获 `CameraState.Error` 后，不要立即重试，先延迟 200-300ms 让 HAL 层彻底释放相机资源。

---

**一句话总结**：右半边的静态画面是**已断开但未被系统清理的 SurfaceView 的最后一帧残留**。根因是双 SurfaceView + 模式切换时 CameraDevice 还没彻底关闭就重建新 Session，导致 BufferQueue 状态错乱。