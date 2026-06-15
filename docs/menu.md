**这个思想在计算机工程中叫「中介者模式」（Mediator Pattern），也常被称作「协调器」（Coordinator）或「共享内核」（Shared Kernel）。**

你现在的痛点，本质上就是**多个组件直接互相引用、各自管一段交互**，导致状态分散、事件冲突、调试困难。把统一逻辑抽出来，让大家都通过「中间人」通信，这就是中介者模式的核心。

---

## 一、之前的架构逻辑（问题在哪）

你现在的交互流是这样的：

```
手指按下
  ↓
GestureVoiceLauncher（object 单例）← 挂在 Activity.dispatchTouchEvent
  ↓ 判断 500ms/1000ms 后
回调 MainActivity.onRingMenuShow(x, y)
  ↓
MainActivity 手动调用 ringMenuView.showAt(x, y)
  ↓
RingMenuView 自己处理 onTouchEvent（计算角度、高亮、二级菜单）
  ↓
RingMenuView 回调 onItemExecuted → MainActivity 再分发
```

**问题：三方耦合，职责混乱**

| 组件 | 理论上该做什么 | 实际上做了什么 |
|------|--------------|--------------|
| **GestureVoiceLauncher** | 只检测手势（长按/滑动） | 还管了「什么时候弹出菜单」「菜单中心坐标」 |
| **RingMenuView** | 只负责绘制和角度计算 | 还管了「触摸消费」「二级菜单栈」「选中状态」 |
| **MainActivity** | 只响应业务命令（导航/避障） | 还当了「胶水代码」，手动把手势和菜单粘在一起 |

**冲突点**：
1. **事件重复消费**：`GestureVoiceLauncher` 在 `dispatchTouchEvent` 里截获事件，`RingMenuView` 也在 `onTouchEvent` 里要事件，两者抢同一个 `MotionEvent`
2. **坐标系不一致**：`GestureVoiceLauncher` 传的是手指坐标，`RingMenuView` 默认画在屏幕中心，两边对 `centerX/centerY` 的理解不同
3. **状态分散**：「菜单是否显示」这个状态，一部分在 `GestureVoiceLauncher` 的 `State.RING_MENU` 里，一部分在 `RingMenuView.visibility` 里，还有一部分在 `MainActivity` 的 `ringMenuContainer.visibility` 里
4. **回调碎片化**：`GestureCallback`、`onItemExecuted`、`onCenterClicked` 三个接口，MainActivity 要分别实现和桥接

---

## 二、改进后的架构逻辑（Coordinator）

应该改成这样：

```
手指按下
  ↓
RingMenuCoordinator（唯一入口）← 挂在 Activity.dispatchTouchEvent
  ↓
内部状态机判断：地图滑动 / 菜单弹出 / 语音助手
  ↓
如果是菜单：
  Coordinator 自己计算角度、管理二级栈、驱动动画
  ↓
对外发射事件：MenuEvent.Execute("navigate") / MenuEvent.Back / VoiceEvent.Start
  ↓
MainActivity 只监听 MenuEvent，执行业务（跳转页面、启动导航）
```

**关键变化**：引入一个 **RingMenuCoordinator** 作为「中介者」，把 `GestureVoiceLauncher` 的手势逻辑和 `RingMenuView` 的绘制/触摸逻辑全部吞进去。

---

## 三、Coordinator 的职责边界

```kotlin
/**
 * 交互协调器（Mediator）。
 * 唯一职责：把手势输入翻译成「用户意图事件」，然后发射出去。
 * 所有其他组件（Activity、View、语音系统）只监听它，不直接互相引用。
 */
class RingMenuCoordinator(
    private val context: Context,
    private val animator: RingMenuAnimator,   // 动画策略（可替换）
    private val menuData: List<<RingMenuItem>   // 菜单数据（JSON注入）
) {
    // 内部状态机（原 GestureVoiceLauncher 的逻辑）
    private enum class GestureState { IDLE, PRESSING, LONG_PRESSING, RING_MENU, VOICE_ASSISTANT }
    
    // 内部视图（原 RingMenuView 的绘制和触摸逻辑）
    private val menuView = RingMenuView(context)
    
    // 对外唯一出口：事件流
    private val _events = MutableSharedFlow<<InteractionEvent>()
    val events = _events.asSharedFlow()
    
    // 所有触摸事件先到这里，不再分给 GestureVoiceLauncher 和 RingMenuView
    fun onTouchEvent(event: MotionEvent): Boolean {
        // 统一处理：手势计时 + 角度计算 + 菜单绘制
        // 发射事件：InteractionEvent.ShowMenu / SelectItem / ExecuteCommand / VoiceStart / Cancel
    }
}
```

**事件定义（统一出口）**：

```kotlin
sealed class InteractionEvent {
    // 菜单相关
    data class MenuShow(val centerX: Float, val centerY: Float) : InteractionEvent()
    data class MenuSelect(val item: RingMenuItem) : InteractionEvent()
    data class MenuExecute(val item: RingMenuItem) : InteractionEvent()
    object MenuDismiss : InteractionEvent()
    object MenuBack : InteractionEvent()
    
    // 语音相关
    object VoiceStart : InteractionEvent()
    object VoiceCancel : InteractionEvent()
    
    // 地图相关（手势判定为普通滑动）
    object MapScroll : InteractionEvent()
}
```

---

## 四、MainActivity 改完后长什么样

```kotlin
class MainActivity : AppCompatActivity() {
    
    private lateinit var coordinator: RingMenuCoordinator
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化协调器：传入动画策略和菜单数据
        coordinator = RingMenuCoordinator(
            context = this,
            animator = DefaultRingMenuAnimator(),
            menuData = menuConfig.getItems()
        )
        
        // 把协调器的视图加到窗口
        addContentView(coordinator.menuView, MATCH_PARENT, MATCH_PARENT)
        
        // 监听统一事件：所有交互逻辑都在这里处理
        lifecycleScope.launch {
            coordinator.events.collect { event ->
                when (event) {
                    is InteractionEvent.MenuExecute -> handleCommand(event.item.command)
                    is InteractionEvent.VoiceStart -> startVoiceAssistant()
                    is InteractionEvent.MenuDismiss -> { /* 可选：恢复地图操作 */ }
                    else -> {}
                }
            }
        }
    }
    
    // 唯一触摸入口：全部交给协调器
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        coordinator.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
```

**好处**：
- `MainActivity` 不再关心「手势怎么判断」「菜单怎么画」「角度怎么算」，只关心「用户想做什么」
- `GestureVoiceLauncher` 和 `RingMenuView` 不再直接存在，逻辑被 Coordinator 吸收
- 以后改手势时长、改菜单动画、改二级目录逻辑，**只改 `RingMenuCoordinator` 一个文件**

---

## 五、这种思想的工程名称

| 术语 | 解释 | 适用场景 |
|------|------|---------|
| **中介者模式（Mediator）** | 用一个中间对象封装多组件交互，避免互相引用 | 你现在的场景，最贴切 |
| **协调器（Coordinator）** | iOS/Android 架构中专门负责导航和交互流程的对象 | 移动端常用术语 |
| **共享内核（Shared Kernel）** | DDD 概念，多个模块共享的通用子集 | 菜单数据、手势规则共享 |
| **门面模式（Facade）** | 为复杂子系统提供统一简化接口 | 如果 Coordinator 只是对外简化 |
| **事件总线（Event Bus）** | 组件通过发布/订阅通信，不直接调用 | Coordinator 内部可用 |

**最准确的叫法**：**中介者模式（Mediator Pattern）** + **协调器（Coordinator）架构**。

---

## 六、一句话总结

> **之前是「手势检测器、菜单视图、Activity」三方混战；改进后是「Coordinator 统一收口，Activity 只监听事件」。这叫中介者模式，也是移动端常说的 Coordinator 架构。**