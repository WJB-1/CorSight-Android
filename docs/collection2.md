你拍照后图片缓存到了 **应用专属的私有文件目录**，具体路径为：

```kotlin
File(requireContext().filesDir, "free_${System.currentTimeMillis()}.jpg")
// 实际对应：/data/data/com.example.voicenavigation/files/free_xxxxxxxxx.jpg
```

**为什么后台页面（DashboardFragment）一直显示没有拍照？**  
后台页面显示的是已经从 `TaskStorage` 中持久化保存的 **任务（CaptureTask）**，而不是零散的单张照片。你的代码中：

- **八方向模式（GridCaptureFragment）**：拍满 8 张后会弹出保存对话框，用户确认保存时调用 `viewModel.saveTask()` → 将 `CaptureTask`（包含 8 张照片）存入 `TaskStorage` → 后台可见。
- **自由模式（FreeCaptureFragment）**：拍照后只是把 `PhotoRecord` 添加到了 ViewModel 的内存列表（`_photos`）中，**从未调用 `saveTask`**，因此这些照片永远不会出现在后台页面。用户拍完一张后，没有任何地方可以把当前批次打包成任务。

### 解决方案

你需要为自由模式增加一个 **“完成采集 / 保存任务”** 的按钮，点击后调用 `saveTask`，将当前已拍的所有照片作为一个采样点保存。参考八方向模式的 `showSaveDialog` 逻辑。

#### 修改 `FreeCaptureFragment` 的布局（添加保存按钮）

在 `fragment_free_capture.xml` 中增加一个按钮，比如 `btnSaveTask`。

#### 在 `FreeCaptureFragment.kt` 中处理保存

```kotlin
// 在 onViewCreated 中添加
val btnSaveTask = view.findViewById<View>(R.id.btnSaveTask)
btnSaveTask.setOnClickListener {
    if (viewModel.photos.value.isEmpty()) {
        Toast.makeText(requireContext(), "还未拍摄任何照片", Toast.LENGTH_SHORT).show()
        return@setOnClickListener
    }
    // 弹出对话框，让用户输入场景描述（类似八方向）
    showSaveDialog()
}
```

实现 `showSaveDialog()`：

```kotlin
private fun showSaveDialog() {
    val ctx = requireContext()
    val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_save_task, null)
    val tvCount = dialogView.findViewById<TextView>(R.id.tvPhotoCount)
    val tvCoords = dialogView.findViewById<TextView>(R.id.tvCoords)
    val etDesc = dialogView.findViewById<EditText>(R.id.etSceneDesc)

    tvCount.text = "已采集 ${viewModel.photos.value.size} 张照片"
    tvCoords.text = "坐标: ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLng)}"

    AlertDialog.Builder(ctx)
        .setTitle("自由采集完成")
        .setView(dialogView)
        .setPositiveButton("确认保存") { _, _ ->
            val desc = etDesc.text.toString().trim().ifEmpty { "未描述" }
            viewModel.saveTask(currentLat, currentLng, desc)
            Toast.makeText(ctx, "已保存采样点", Toast.LENGTH_SHORT).show()
            // 清空当前照片列表，准备下一次采集（可选）
            viewModel.clearPhotos()
        }
        .setNegativeButton("放弃") { _, _ ->
            // 删除已拍的所有照片文件
            viewModel.photos.value.forEach { File(it.filePath).delete() }
            viewModel.clearPhotos()
            Toast.makeText(ctx, "已放弃", Toast.LENGTH_SHORT).show()
        }
        .setCancelable(false)
        .show()
}
```

并在 `FreeCaptureViewModel` 中添加 `clearPhotos()`：

```kotlin
fun clearPhotos() {
    _photos.value = emptyList()
}
```

### 额外提醒：权限与 GPS 问题

你之前提到 GPS 信号问题，在最新的代码中已经加了 `LocationResult` 和 GPS 就绪检查，这是正确的。但还需在 `CaptureHubActivity` 中验证 `LocationProvider.isLocationEnabled()` 的实现是否有效。确保用户开启位置服务后，定位才能正常工作。

### 总结

- **缓存路径**：`filesDir` 下以 `free_` 或 `grid_` 开头的文件。
- **后台看不到**：因为自由模式没有调用 `saveTask` 将照片打包成任务。添加上保存按钮即可解决。
- **补拍功能**：`RetakeFragment` 替换照片后会调用 `viewModel.replacePhoto`，更新 `TaskStorage` 中对应任务的照片记录，后台会自动刷新。