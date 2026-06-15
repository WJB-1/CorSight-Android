你提的两个需求很合理：**上传需要更精细的控制**（单张、多选、串行上传），**图片预览 UI 需要更实用**。下面我分别给出具体修改方案。

---

## 一、上传逻辑改造（支持多选上传、串行控制）

### 1. Dashboard UI 改动：照片网格支持多选
**文件**：`DashboardFragment.kt` + `item_photo_grid.xml`

#### 1.1 修改 `item_photo_grid.xml`，添加 CheckBox
```xml
<FrameLayout ...>
    <!-- 原有缩略图、标签、状态角标 -->
    <ImageView android:id="@+id/ivThumbnail" ... />
    <TextView android:id="@+id/tvLabel" ... />
    <TextView android:id="@+id/tvStatusBadge" ... />

    <!-- 新增选择框（右上角） -->
    <CheckBox
        android:id="@+id/cbSelect"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:layout_gravity="top|end"
        android:buttonTint="@color/colorPrimary"
        android:visibility="gone" />
</FrameLayout>
```

#### 1.2 在 DashboardFragment 中添加“编辑模式”切换
```kotlin
// 添加成员变量
private var isSelectionMode = false
private val selectedPhotoIds = mutableSetOf<String>()
private lateinit var btnToggleSelect: Button
private lateinit var btnUploadSelected: Button

// 在 onViewCreated 中添加按钮
btnToggleSelect = view.findViewById(R.id.btnToggleSelect)
btnUploadSelected = view.findViewById(R.id.btnUploadSelected)
btnToggleSelect.setOnClickListener { toggleSelectionMode() }
btnUploadSelected.setOnClickListener { uploadSelectedPhotos() }

private fun toggleSelectionMode() {
    isSelectionMode = !isSelectionMode
    selectedPhotoIds.clear()
    btnToggleSelect.text = if (isSelectionMode) "取消选择" else "选择照片"
    btnUploadSelected.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
    // 刷新所有照片 item 显示 CheckBox
    (rvTasks.adapter as? TaskAdapter)?.notifyDataSetChanged()
}

private fun uploadSelectedPhotos() {
    if (selectedPhotoIds.isEmpty()) {
        Toast.makeText(context, "请先选择照片", Toast.LENGTH_SHORT).show()
        return
    }
    // 收集所有选中的 PhotoRecord（需要知道所属 task）
    val photosToUpload = mutableListOf<Pair<CaptureTask, PhotoRecord>>()
    viewModel.tasks.value.forEach { task ->
        task.photos.forEach { photo ->
            if (selectedPhotoIds.contains(photo.id)) {
                photosToUpload.add(task to photo)
            }
        }
    }
    viewModel.uploadSelectedPhotos(photosToUpload)
    toggleSelectionMode()
}
```

#### 1.3 修改 PhotoAdapter 支持选择和显示 CheckBox
```kotlin
inner class PhotoAdapter(...) {
    override fun onBindViewHolder(holder: VH, position: Int) {
        // ...原有逻辑
        holder.cbSelect?.apply {
            visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            isChecked = selectedPhotoIds.contains(photo.id)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedPhotoIds.add(photo.id)
                else selectedPhotoIds.remove(photo.id)
            }
        }
    }
}
```

### 2. ViewModel 添加上传选中照片的方法
**文件**：`DashboardViewModel.kt`

```kotlin
fun uploadSelectedPhotos(photos: List<Pair<CaptureTask, PhotoRecord>>) {
    viewModelScope.launch(Dispatchers.IO) {
        _isUploading.value = true
        val service = UploadService(baseUrl, taskStorage)
        
        // 可选：串行上传（网络差时）或并发上传
        val useSerial = true  // 可从设置中读取
        if (useSerial) {
            photos.forEach { (task, photo) ->
                service.uploadSinglePhoto(task, photo) { pct ->
                    _photoProgress.value = _photoProgress.value.toMutableMap().apply {
                        put(photo.id, pct)
                    }
                }
            }
        } else {
            // 并发控制（保持原逻辑）
            photos.chunked(MAX_CONCURRENT_UPLOADS).forEach { batch ->
                batch.map { (task, photo) ->
                    async {
                        service.uploadSinglePhoto(task, photo) { pct ->
                            _photoProgress.value = _photoProgress.value.toMutableMap().apply {
                                put(photo.id, pct)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        _isUploading.value = false
        refreshTasks()
    }
}
```

### 3. 添加串行上传开关（可选，从设置中读取）
在 `SettingsFragment` 中添加一个 Switch，保存用户偏好（网络差时串行上传）。上述代码中 `useSerial` 可以从 `SharedPreferences` 读取。

---

## 二、图片预览 UI 优化

当前预览对话框 `dialog_photo_preview.xml` 只有一个小 ImageView，且尺寸受限。改为全屏/大图预览，支持横向滑动查看任务内所有照片。

### 1. 创建新布局 `dialog_fullscreen_preview.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@android:color/black">

    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/viewPager"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="48dp"
        android:gravity="center"
        android:orientation="horizontal"
        android:background="#80000000">
        <TextView
            android:id="@+id/tvPageIndicator"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@android:color/white"
            android:textSize="14sp" />
    </LinearLayout>
</LinearLayout>
```

### 2. 创建 `FullscreenPreviewDialog.kt`
```kotlin
class FullscreenPreviewDialog : DialogFragment() {
    companion object {
        fun newInstance(photos: List<PhotoRecord>, startIndex: Int = 0) = FullscreenPreviewDialog().apply {
            arguments = Bundle().apply {
                putParcelableArrayList("photos", ArrayList(photos))
                putInt("startIndex", startIndex)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val photos = arguments?.getParcelableArrayList<PhotoRecord>("photos") ?: return super.onCreateDialog(savedInstanceState)
        val startIndex = arguments?.getInt("startIndex", 0) ?: 0

        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_fullscreen_preview)
        
        val viewPager = dialog.findViewById<ViewPager2>(R.id.viewPager)
        val tvIndicator = dialog.findViewById<TextView>(R.id.tvPageIndicator)
        
        viewPager.adapter = PhotoPagerAdapter(photos)
        viewPager.setCurrentItem(startIndex, false)
        updateIndicator(startIndex + 1, photos.size)
        
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position + 1, photos.size)
            }
        })
        
        return dialog
    }

    private fun updateIndicator(current: Int, total: Int) {
        // 可在顶部显示
    }

    inner class PhotoPagerAdapter(private val photos: List<PhotoRecord>) : RecyclerView.Adapter<PhotoPagerAdapter.VH>() {
        inner class VH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return VH(iv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val photo = photos[position]
            BitmapFactory.decodeFile(photo.filePath)?.let { holder.imageView.setImageBitmap(it) }
        }
        override fun getItemCount() = photos.size
    }
}
```

### 3. 在 `DashboardFragment` 的 `showPhotoPreview` 中调用新对话框
```kotlin
private fun showPhotoPreview(task: CaptureTask, clickedPhoto: PhotoRecord) {
    val index = task.photos.indexOfFirst { it.id == clickedPhoto.id }
    FullscreenPreviewDialog.newInstance(task.photos, index).show(parentFragmentManager, "preview")
}
```

并且修改点击照片时的回调：原来打开的是单张预览对话框，改成打开全屏可滑动预览（包含任务内所有照片）。

### 4. 可选：在八方向/自由采集界面也增加预览入口
可以复用同一个 `FullscreenPreviewDialog`。

---

## 三、网络差时串行上传的配置开关

在 `SettingsFragment` 中添加一个 `SwitchCompat`：
```xml
<SwitchCompat
    android:id="@+id/switch_serial_upload"
    android:text="网络差时串行上传（逐张上传）" />
```

在 `DashboardViewModel` 中读取该偏好：
```kotlin
val useSerial = context.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("serial_upload", false)
```

---

## 总结

- **上传灵活性**：通过选择模式 + 批量上传选中照片实现，同时支持串行/并发切换。
- **预览 UI**：全屏 ViewPager2，支持横滑查看任务所有照片。
- **全部上传**：保留按钮，但作为次要功能（用户很少用）。

按照上述步骤修改后，你的上传和预览体验会接近微信图片管理，适合弱网环境。