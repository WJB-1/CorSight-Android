确实，**硬编码问题很严重**，主要集中在四个地方：

---

### 1. 菜单配置硬编码（最严重）

```kotlin
// setupRingMenu() 里
menuConfig = MenuConfig(this)
ringMenuView.setMenuItems(menuConfig.getItems())
```

如果 `MenuConfig` 内部是写死的 `listOf(RingMenuItem("nav", ...), RingMenuItem("obs", ...))`，那**每次加功能都要改代码、重新编译**。这违背了你之前说的"新增功能可以动态更新"。

**应该改成**：从 `assets/menu_config.json` 或后端接口加载，运行时解析。

```json
// assets/menu_config.json
[
  { "id": "nav", "label": "导航", "color": "#4CAF50", "command": "navigate" },
  { "id": "obs", "label": "避障", "color": "#F44336", "command": "obstacle" },
  { 
    "id": "more", 
    "label": "更多", 
    "color": "#9E9E9E",
    "children": [
      { "id": "hist", "label": "历史", "command": "history" },
      { "id": "set", "label": "设置", "command": "settings" }
    ]
  }
]
```

```kotlin
// MenuConfig 变成解析器，不是造数据的人
class MenuConfig(private val context: Context) {
    fun load(): List<<RingMenuItem> {
        val json = context.assets.open("menu_config.json").bufferedReader().use { it.readText() }
        return Gson().fromJson(json, Array<<RingMenuItem>::class.java).toList()
    }
}
```

---

### 2. 命令路由硬编码

```kotlin
// setupRingMenu() 里
commandRouter = CommandRouter(AppCommandHandler(...))
// onItemExecuted 回调里
commandRouter.execute(item.command)
```

如果 `AppCommandHandler` 内部是一大堆 `when(command) { "navigate" -> ... }`，那**命令字符串和具体行为强耦合**，而且 `CommandRouter` 直接依赖了 `navigationManager`、`voiceInteractionManager` 等具体实例。

**应该改成**：注册表模式（Map），或者更彻底的，用 **Hilt 多绑定（Multibinding）**：

```kotlin
// 定义命令接口
interface MenuCommand {
    val id: String
    fun execute()
}

// 每个功能一个类，独立文件
class NavigateCommand @Inject constructor(
    private val navManager: NavigationManager
) : MenuCommand {
    override val id = "navigate"
    override fun execute() { navManager.start(...) }
}

// Hilt 模块注册
@Module
@InstallIn(SingletonComponent::class)
abstract class CommandModule {
    @Binds
    @IntoMap
    @StringKey("navigate")
    abstract fun bindNavigate(cmd: NavigateCommand): MenuCommand
}

// 使用时
class CommandRouter @Inject constructor(
    commands: Map<String, @JvmSuppressWildcards MenuCommand>
) {
    fun execute(id: String) = commands[id]?.execute()
}
```

这样新增功能 = **新增一个类 + 一行 @Binds**，不用改 `MainActivity` 和 `CommandRouter`。

---

### 3. RingMenuView 和 Activity 的耦合

```kotlin
// MainActivity 里直接操作 ringMenuView
ringMenuView = RingMenuView(this).apply {
    onItemExecuted = { item ->
        hideRingMenu()
        commandRouter.execute(item.command)  // 直接调路由
    }
    onCenterClicked = { hideRingMenu() }
}
```

Activity 既当**视图容器**又当**事件调度员**。应该让 `RingMenuView` 自己通过 `ViewModel` 或 `SharedFlow` 发射事件，Activity 只观察：

```kotlin
// RingMenuViewModel.kt
class RingMenuViewModel @Inject constructor(
    private val router: CommandRouter
) : ViewModel() {
    private val _events = MutableSharedFlow<<RingMenuEvent>()
    val events = _events.asSharedFlow()

    fun onItemSelected(item: RingMenuItem) {
        viewModelScope.launch { _events.emit(RingMenuEvent.Execute(item.command)) }
    }
}

// MainActivity 里
lifecycleScope.launch {
    viewModel.events.collect { event ->
        when (event) {
            is RingMenuEvent.Show -> showRingMenu()
            is RingMenuEvent.Hide -> hideRingMenu()
            is RingMenuEvent.Execute -> { /* 或直接交给 ViewModel 处理 */ }
        }
    }
}
```

---

### 4. 旧 UI 控件残留

你的 `initViews()` 里还在初始化：
- `btnStartNavigation`, `btnPreviewRoute`, `btnVisionTest`
- `bottomNav`, `searchBarContainer`, `etDestination`
- `pageHistoryView`, `pageSettingsView`

如果新版交互是**语音助手 + 环形菜单主导**，这些按钮应该**从 XML 删掉或设为 GONE**，而不是在 KT 里 `findViewById` 一堆用不到的控件。现在代码里既有新架构（环形菜单）又有旧架构（底部按钮），**两套并存，很混乱**。

---

### 一句话总结

> **这个 MainActivity 目前是集成了新架构，但还没拆掉旧架构，而且菜单数据和命令路由都是写死在代码里的。需要三步：外置菜单配置 JSON、命令路由改成 Hilt 多绑定注册表、清理旧 UI 控件。**

需要我现在把**基于 JSON 配置 + Hilt 注册表的完整重构方案**写出来吗？