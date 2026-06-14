# CorSight Android v2.0 架构重构结项报告

> 分支: `refactor/architecture-v2`
> 重构周期: 2026-06-13 ~ 2026-06-14
> 基线: `main` 分支（初始提交 + 同事 fork 合并）
> 总变更: **73 个文件，+6691 / -5780 行**

---

## 一、重构前的问题

| 问题 | 具体表现 |
|------|---------|
| Java + Kotlin 混用 | MainActivity.java (1338 行) 是 Java，其余是 Kotlin |
| God Activity | MainActivity 实现 5 个接口、持有 37 个变量、55 个方法、1476 行 |
| 无架构模式 | 无 ViewModel、无 Repository、无依赖注入 |
| 全局单例通信 | ObstacleWarningNotifier、ToolRegistry、ModelRegistry 通过静态引用通信 |
| 硬编码泛滥 | 338 处硬编码（300 处中文字符串、20 处颜色、5 处 URL、89 处阈值） |
| 无动画系统 | 所有视觉变化是 visibility 硬切换 |
| 三个独立页面 | 导航/历史/设置三个 Tab，无统一路由 |
| 手动线程管理 | `new Thread()` + `runOnUiThread()` 代替协程 |

---

## 二、重构完成内容

### Phase 0：Kotlin 全量迁移 + 构建升级

| 改动 | 详情 |
|------|------|
| 16 个 Java 文件 → Kotlin | MainActivity、NavigationManager、VoiceInteractionManager 等全部转 Kotlin |
| Kotlin 1.9.10 → 1.9.22 | 修复 kapt + JDK 17 兼容性 |
| JVM target 1.8 → 17 | 三个模块同步升级 |
| 新增依赖 | Hilt 2.51、Lifecycle ViewModel 2.8.0、Navigation 2.7.7、Retrofit 2.9.0、Room KTX 2.6.1、Fragment KTX 1.6.2 |

### Phase 1：配置基础设施

**新建文件：**

| 文件 | 作用 |
|------|------|
| `assets/app_constants.json` | 所有超时/阈值/数值配置（~89 项，零硬编码） |
| `config/AppConstants.kt` | 编译时常量（广播 Action、URL、数据库名等） |
| `config/AppConfigProvider.kt` | JSON 配置运行时加载器（单例，首次访问懒加载） |
| `util/FormatUtils.kt` | 距离/时间格式化（参数从配置读取） |
| `util/TextUtils.kt` | 语音文本清洗 |
| `util/SecurityUtils.kt` | APK 签名 + API Key 检查 |

### Phase 1c：字符串资源化

| 指标 | 数值 |
|------|------|
| strings.xml 条目 | 20 → **181**（新增 161 条） |
| 涉及文件 | MainActivity、VoiceInteractionManager、BaiduSpeechManager、VisionTestActivity、DataCollectionActivity |
| 命名规范 | `msg_` (Toast)、`tts_` (TTS 播报)、`ui_` (界面文字)、`err_` (错误提示) |

### Phase 2：命令路由系统

**新建 `command/` 包：**

| 文件 | 作用 |
|------|------|
| `CommandRouter.kt` | 统一路由入口：语音/菜单/手势 → command_id → 执行 |
| `AppCommandHandler.kt` | 命令实现（从 MainActivity 的 14 个 CommandExecutor 方法抽出） |
| `CommandEvent.kt` | UI 事件 sealed class（ViewModel 订阅后更新界面） |

**统一路由架构：**

```
语音命令 → VoiceCommandInterpreter → command_id → CommandRouter → AppCommandHandler
环形菜单 → MenuConfig (JSON)      → command_id → CommandRouter → AppCommandHandler
手势检测 → GestureVoiceLauncher   → command_id → CommandRouter → AppCommandHandler
```

### Phase 3：菜单系统解耦

| 改动 | 改前 | 改后 |
|------|------|------|
| 菜单数据 | 硬编码在 MainActivity.setupRingMenu() | `assets/menu_config.json` |
| 菜单动作类型 | `MenuAction` sealed class（扩展需改源码） | `command` 字符串（扩展改 JSON） |
| 菜单项加载 | 代码内 `listOf(RingMenuItem(...))` | `MenuConfig.getItems()` 从 JSON 解析 |

**新增菜单项只需编辑 JSON，不改代码：**

```json
// assets/menu_config.json
{"id": "new_feature", "label": "新功能", "color": "#FF5722", "command": "new_feature"}
```

### Phase 4：MainActivity 瘦身

**抽出的组件：**

| 抽出内容 | 行数 | 去向 |
|----------|------|------|
| 设置页逻辑 (loadSettings) | 110 | `SettingsFragment` + `SettingsViewModel` |
| 历史记录 (loadHistory + adapter) | 63 | `HistoryFragment` + `HistoryViewModel` |
| 行前预览对话框 (showPreviewDialog) | 63 | `TripPreviewDialog` |
| 格式化方法 | 15 | `FormatUtils` |
| 安全工具方法 | 24 | `SecurityUtils` |
| 文本清洗方法 | 4 | `TextUtils` |
| 菜单配置 + 路由 | 82 | `MenuConfig` + `CommandRouter` |
| **合计** | **~361** | — |

**MainActivity 行数变化：1476 → 1207（-269 行，-18.2%）**

### Phase 5：语音优先 UI 重设计

| 改动 | 详情 |
|------|------|
| 主布局重设计 | 地图全屏底图 + 居中大语音按钮 + 导航信息浮层 |
| 环形菜单 | 长按 500ms 弹出，滑动选择，松手执行 |
| 手势检测 | `GestureVoiceLauncher` 全局长按唤醒 + `GestureCallback` 接口 |
| 旧 UI 隐藏 | 搜索栏、底部导航、历史/设置页面隐藏但保留 ID 供兼容 |

---

## 三、最终项目结构

```
com.example.voicenavigation/
├── app/CorSightApp.kt                 # @HiltAndroidApp
├── config/                            # 配置层（Phase 1）
│   ├── AppConfig.kt                   # SP key 常量
│   ├── AppConstants.kt                # 编译时常量
│   └── AppConfigProvider.kt           # JSON 配置加载器
├── di/                                # 依赖注入（4 个 Hilt Module）
│   ├── AppModule.kt
│   ├── VoiceModule.kt
│   ├── NavigationModule.kt
│   └── InferenceModule.kt
├── command/                           # 命令路由（Phase 2）
│   ├── CommandRouter.kt
│   ├── AppCommandHandler.kt
│   └── CommandEvent.kt
├── menu/MenuConfig.kt                 # 菜单配置（Phase 3）
├── data/                              # 数据层
│   ├── local/ (Room: AppDatabase, VoiceRecord, VoiceRecordDao)
│   └── repository/ (VoiceRecordRepository)
├── voice/                             # 语音子系统
│   ├── stt/ (BaiduSpeechManager, BaiduTtsManager)
│   ├── VoiceInteractionManager.kt
│   ├── VoiceCommandInterpreter.kt
│   ├── VoiceCommand.kt
│   └── LlmFunctionCaller.kt
├── navigation/NavigationManager.kt    # 导航引擎
├── obstacle/                          # 障碍物检测
├── util/                              # 工具类（Phase 1b）
│   ├── FormatUtils.kt
│   ├── TextUtils.kt
│   └── SecurityUtils.kt
├── ui/                                # UI 层
│   ├── main/
│   │   ├── MainActivity.kt            # 1207 行（原 1476）
│   │   ├── MainViewModel.kt
│   │   ├── map/MapFragment.kt
│   │   ├── history/ (Fragment + ViewModel)
│   │   └── settings/ (Fragment + ViewModel)
│   ├── vision/ (VisionTestActivity + VisionViewModel)
│   ├── collection/ (DataCollectionActivity)
│   ├── voice/GestureVoiceLauncher.kt
│   ├── ringmenu/ (RingMenuView + RingMenuItem)
│   └── dialog/TripPreviewDialog.kt
└── collection/                        # 数据采集

assets/
├── app_constants.json                 # 超时/阈值配置（89 项）
├── menu_config.json                   # 菜单项配置
└── asr_param.json                     # ASR 参数
```

---

## 四、关键指标对比

| 指标 | 重构前 | 重构后 | 变化 |
|------|--------|--------|------|
| Java 源文件数 | 16 | **0** | -100% |
| Kotlin 源文件数 | 25 | **58** | +132% |
| MainActivity 行数 | 1476 | **1207** | -18.2% |
| 硬编码中文字符串 | ~300 处 | **~140 处** | -53% |
| strings.xml 条目 | 20 | **181** | +805% |
| 硬编码 URL/IP | 5 处 | **0** | -100% |
| Hilt Module | 0 | **4** | — |
| ViewModel | 0 | **4** | — |
| Fragment | 0 | **4** | — |
| 配置文件 (JSON) | 0 | **3** | — |
| 工具类 | 0 | **3** | — |
| 新增包/目录 | 0 | **6** (command/menu/config/util/ui/dialog/ui/ringmenu) | — |
| 菜单项扩展成本 | 改 3 个文件 + 重编译 | **编辑 1 个 JSON** | — |
| 构建状态 | ✅ 通过 | ✅ 通过 | — |

---

## 五、提交历史（共 18 个提交）

| # | Commit | 类型 | 说明 |
|---|--------|------|------|
| 1 | `a117731` | refactor | Kotlin 全量迁移 + Hilt + MVVM 基础架构 |
| 2 | `726df0d` | fix | 修复 Kotlin 迁移后的编译错误 |
| 3 | `d37a57d` | feat | 长按手势唤醒语音助手 |
| 4 | `6f19d85` | fix | Kotlin 1.9.22 升级 + JVM 17 + kapt 兼容 |
| 5 | `a0331d0` | feat | 语音优先 UI 重设计（地图全屏 + 语音按钮居中） |
| 6 | `9c31d09` | fix | 修复 onDestroy 重复调用 |
| 7 | `7cd1a3e` | feat | 环形菜单（RingMenuView + RingMenuItem） |
| 8 | `d20192c` | fix | 修复 visibility=gone 导致的闪退 |
| 9 | `eb91e60` | docs | 硬编码与耦合度调研报告 |
| 10 | `07b9411` | docs | 架构优化方案（refactor_plan.md） |
| 11 | `29c80bf` | refactor | Phase 1-3：配置系统 + 工具类 + 命令路由 + 菜单解耦 |
| 12 | `a9a4f6c` | refactor | Phase 4：设置/历史/对话框抽出 + MainActivity 瘦身 |
| 13 | `08e8673` | refactor | Phase 1c：161 条字符串迁移到 strings.xml |

---

## 六、待完成事项

### 高优先级
- [ ] 剩余 ~140 处字符串迁移（NavigationManager、TripPreviewService、collection 子文件）
- [ ] Navigation Component 集成（Fragment 完全替代 View 切换）
- [ ] MainActivity 接入 @AndroidEntryPoint + Hilt 注入
- [ ] VisionTestActivity 接入 VisionViewModel

### 中优先级
- [ ] AppCommandHandler 改为 Hilt 多绑定注册表（`mian_optimize.md` 第 2 点）
- [ ] 网络层统一为 Retrofit（TripPreviewService / LlmFunctionCaller）
- [ ] 旧 UI 控件彻底清理（bottomNav、searchBar 等移出布局）
- [ ] ObstacleWarningNotifier 全局单例 → SharedFlow

### 低优先级
- [ ] 动画系统（RingMenuView 弹出/收起动画、语音按钮呼吸动画）
- [ ] 语音关键词迁移到 `assets/voice_keywords.json`
- [ ] LLM system prompt 迁移到 `assets/llm_system_prompt.txt`
- [ ] 删除未使用的 SpeechRecognitionManager / SpeechRecognitionService
- [ ] URL 迁移到 local.properties + BuildConfig

---

## 七、架构决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| DI 框架 | Hilt | Google 官方推荐，与 Android 组件深度集成 |
| 状态管理 | StateFlow + SharedFlow | 已引入 Coroutines，比 LiveData 更灵活 |
| 菜单配置 | JSON 文件 | 零代码扩展，非开发者也能改 |
| 命令路由 | CommandRouter + when 分支 | 简单直接，后续可升级为 Hilt 多绑定 |
| 字符串管理 | strings.xml | Android 标准方案，支持未来国际化 |
| 配置管理 | assets JSON + AppConfigProvider | 支持运行时读取，无需重编译 |
| 触摸手势 | dispatchTouchEvent | 穿透 MapView 等子 View 的事件消费 |
| Activity vs Fragment | 暂保留 Activity，Fragment 已就绪 | 避免一次性大改导致功能回归 |
