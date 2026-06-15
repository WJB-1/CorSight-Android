顶部状态栏会遮挡UI，通常是因为你的应用还没有完全适配 **“边到边”（Edge-to-Edge）** 的显示模式。好在Android官方已经提供了标准的解决办法，只要跟着现代标准来，这个问题就能很好地解决。

### 🧐 理解问题根源：老方案过时，新标准是“边到边”
在Android 15之前，应用的内容被默认限制在系统栏下方的“安全矩形”内，所以不太会有遮挡问题。但从Android 15开始，Google强制要求应用采用“边到边”的设计，让你的应用界面可以利用整个屏幕，内容自然就绘制到了状态栏、导航栏的后面。

过去用 `android:fitsSystemWindows="true"` 这类简单粗暴的方法，现在已经不够用了，官方推荐的是掌握 `WindowInsets` 这个新标准。

### ✅ 现代标准实践：两步走，根治遮挡
要解决这个问题，核心就是两步：**启用“边到边”** + **动态处理“边衬区”**。

#### 1. 启用“边到边”显示
这是让应用内容铺满屏幕的前提。在 `Activity` 的 `onCreate` 方法中，调用 `enableEdgeToEdge()` 这个函数即可。

```kotlin
// 在 Activity.onCreate() 中
override fun onCreate(savedInstanceState: Bundle?) {
    // 在 setContentView 之前调用
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // setContentView(R.layout.activity_main)
}
```
*   这个函数能让你的内容延伸到系统栏背后，同时把系统栏（状态栏、导航栏）设置为透明。
*   **兼容性**：调用后，在运行Android 15及以上的设备上会自动生效，而对于旧版本设备，它也能确保一致的“边到边”体验。

#### 2. 使用 `WindowInsets` 处理内容重叠
启用“边到边”后，你需要主动处理那些 **“绝对不能被遮挡”** 的关键UI元素（比如按钮、文本输入框、工具栏等），利用 `WindowInsets` 为它们提供合适的内边距，避开状态栏和导航栏。

**在 Jetpack Compose 中**，你可以使用 `WindowInsets` 提供的便捷修饰符，非常优雅：
```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding

// 为整个内容添加上部内边距，避开状态栏
Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
    // 你的界面内容
}
```
或者，你可以单独为某一部分添加内边距：
```kotlin
import androidx.compose.foundation.layout.statusBarsPadding

Box(modifier = Modifier.statusBarsPadding()) {
    // 这个Box的内容会避开状态栏
}
```

**在传统 XML Views 中**，你可以通过设置监听器来动态调整布局的内边距：
```kotlin
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_layout)) { view, insets ->
    val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.updatePadding(
        top = systemBarsInsets.top,
        bottom = systemBarsInsets.bottom,
        left = systemBarsInsets.left,
        right = systemBarsInsets.right
    )
    insets
}
```
这个方法能动态获取并应用系统栏（状态栏、导航栏）所占的边衬区大小，作为你布局的内边距，从而有效避免被遮挡。

### 💡 其他适配技巧
*   **调整图标颜色**：状态栏透明后，如果背景太亮，可以调整图标的颜色以保证可见性。
    ```kotlin
    // Kotlin
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true // 深色图标（适用于浅色背景）
    ```
*   **处理键盘遮挡**：在 `AndroidManifest.xml` 的 `<activity>` 标签中，需要设置软键盘模式，否则系统可能无法正确提供键盘的边衬区信息。
    ```xml
    <activity
        android:name=".YourActivity"
        android:windowSoftInputMode="adjustResize" />
    ```
    这一步设置后，你就可以像处理状态栏一样，用 `WindowInsets` 来动态响应键盘的弹出了。

*   **全屏沉浸模式**：对于游戏或视频播放器等希望完全隐藏系统栏的场景，可以使用 `WindowInsetsControllerCompat.hide()` 等方法来实现。
*   **获取状态栏高度**：有些时候，你可能还是需要知道状态栏的**精确像素高度**。官方推荐的还是通过 `WindowInsets` 获取，它比过去通过系统资源ID获取的方式更准确、更动态。

### 💎 总结与建议
总结一下，为了避免UI被遮挡，你需要从过去“设置全局属性”的旧思维，转变到“为关键元素添加边衬区内边距”的现代标准思路上来。

*   **关键要点**：永远不要在代码里写 `android:fitsSystemWindows="true"`（传统 Views 布局），也不要自己去计算或写死状态栏的像素高度。
*   **分步实施**：
    1.  **第一步**：检查并统一调用 `enableEdgeToEdge()`，让应用做好内容铺满屏幕的准备。
    2.  **第二步**：识别所有不可被遮挡的UI元素（如按钮、标题栏），并用官方文档推荐的方法（如 `Modifier.statusBarsPadding()`）为它们添加动态内边距。

希望这些能帮你解决问题～如果布局上还有遇到具体的遮挡情况，也可以发出来一起看看。