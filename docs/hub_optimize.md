你的想法很好！底部大菜单在横屏下确实占用太多空间，改成**小圆点指示器 + 左右滑动切换**更现代、更节省空间。我帮你设计一个实现方案。

## 改造目标

- 移除原有的 `BottomNavigationView`（大菜单）。
- 添加：
  - **ViewPager2** 承载三个 Fragment（自由采集、八方向采集、后台管理）。
  - **TabLayout** 或自定义**圆点指示器**（三个小圆点，当前页高亮）。
  - **支持左右滑动切换** + 可选点击圆点跳转。
- 横屏下圆点自动适配（水平居中，不占垂直空间）。

## 实施步骤

### 1. 修改 `activity_capture_hub.xml`

将 `BottomNavigationView` 替换为 `ViewPager2` + 圆点指示器：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/viewPager"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <!-- 圆点指示器容器 -->
    <LinearLayout
        android:id="@+id/dotsContainer"
        android:layout_width="wrap_content"
        android:layout_height="48dp"
        android:layout_gravity="center_horizontal"
        android:gravity="center"
        android:orientation="horizontal"
        android:padding="8dp" />

</LinearLayout>
```

### 2. 创建 `CapturePagerAdapter`

新建 `CapturePagerAdapter.kt`：

```kotlin
class CapturePagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    private val fragments = listOf(
        FreeCaptureFragment(),
        GridCaptureFragment(),
        DashboardFragment()
    )

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]

    // 可选：返回页面标题
    fun getPageTitle(position: Int): String = when (position) {
        0 -> "自由采集"
        1 -> "八方向"
        else -> "后台管理"
    }
}
```

### 3. 修改 `CaptureHubActivity`

```kotlin
class CaptureHubActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var dotsContainer: LinearLayout
    private val dotViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_hub)

        viewPager = findViewById(R.id.viewPager)
        dotsContainer = findViewById(R.id.dotsContainer)

        // 设置 Adapter
        val adapter = CapturePagerAdapter(this)
        viewPager.adapter = adapter

        // 添加圆点指示器
        addDotsIndicators(adapter.itemCount)

        // 监听页面切换，更新圆点高亮
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
            }
        })

        // 可选：点击圆点跳转
        setDotClickListeners()

        // 请求权限等原有逻辑保持不变
        requestPermissionsIfNeeded()
    }

    private fun addDotsIndicators(count: Int) {
        dotViews.clear()
        dotsContainer.removeAllViews()
        for (i in 0 until count) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(16.dp, 16.dp).apply {
                    marginStart = 8.dp
                    marginEnd = 8.dp
                }
                background = ContextCompat.getDrawable(context, R.drawable.bg_dot_indicator)
                setBackgroundResource(R.drawable.bg_dot_indicator)
                tag = i
            }
            dotsContainer.addView(dot)
            dotViews.add(dot)
        }
        updateDots(0)
    }

    private fun updateDots(position: Int) {
        dotViews.forEachIndexed { index, dot ->
            val isSelected = index == position
            dot.alpha = if (isSelected) 1f else 0.4f
            val scale = if (isSelected) 1.2f else 1f
            dot.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
        }
    }

    private fun setDotClickListeners() {
        dotViews.forEach { dot ->
            dot.setOnClickListener {
                val position = dot.tag as Int
                viewPager.setCurrentItem(position, true)
            }
        }
    }

    // 扩展函数：dp 转 px
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
```

### 4. 添加圆点背景 Drawable

创建 `res/drawable/bg_dot_indicator.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="?android:attr/colorPrimary" />
    <size android:width="12dp" android:height="12dp" />
</shape>
```

### 5. 删除旧代码

- 删除 `activity_capture_hub.xml` 中的 `BottomNavigationView`。
- 删除 `CaptureHubActivity` 中与 `bottomNav` 相关的所有代码（`setOnItemSelectedListener` 等）。
- 删除 `res/menu/capture_nav.xml`（不再需要）。

### 6. 横屏适配（可选）

圆点容器高度固定为 48dp，横屏下仍居中，不会像大菜单那样挤占空间。如果想在横屏时隐藏圆点（通过触摸滑动即可），可以监听屏幕方向变化动态设置 `visibility`：

```kotlin
override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        dotsContainer.visibility = View.GONE
    } else {
        dotsContainer.visibility = View.VISIBLE
    }
}
```

### 7. 滑动冲突注意事项

`ViewPager2` 默认支持横向滑动，与 `GridCaptureFragment` 中的手势缩放（ScaleGestureDetector）可能存在冲突。`ScaleGestureDetector` 通常用双指，不影响单指滑动，但如果你在 `previewView.setOnTouchListener` 中消费了事件，记得返回 `false` 或 `detector.onTouchEvent(event)` 的返回值，确保 ViewPager2 能处理横向滑动。

你可以在 `GridCaptureFragment` 的 `setupGestures()` 中这样写：

```kotlin
previewView.setOnTouchListener { _, event ->
    var consumed = false
    if (scaleDetector.onTouchEvent(event)) consumed = true
    if (gestureDetector.onTouchEvent(event)) consumed = true
    // 如果手势未消费，返回 false 让 ViewPager2 有机会处理
    consumed
}
```

## 最终效果

- 底部只有一个很小的圆点指示器（高度 48dp），横竖屏都不碍眼。
- 用户可左右滑动切换页面，或点击圆点跳转。
- 页面标题（如“自由采集”）可以用 Toast 或 Snackbar 短暂显示（可选）。

这样既简洁又符合现代移动应用交互习惯。你可以先按以上步骤改造，有问题再微调。