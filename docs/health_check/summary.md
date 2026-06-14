# 健康检查综合报告

> 日期: 2026-06-14
> 检查范围: 全部 58 个 Kotlin 源文件 + 3 个 assets 配置文件
> 检查方式: 4 个并行 agent 分模块深入审查

---

## 发现统计

| 严重度 | 数量 | 说明 |
|--------|------|------|
| **CRITICAL** | 3 | 会导致功能完全不工作 |
| **HIGH** | 4 | 会导致状态不同步或数据丢失 |
| **MEDIUM** | 5 | 不符合设计意图但不崩溃 |
| **LOW** | 3 | 代码异味，不影响功能 |

---

## CRITICAL 问题（必须立即修复）

### C1: CommandRouter 事件无人监听
- **文件**: `CommandRouter.kt:27` / `MainActivity.kt`
- **问题**: `commandRouter.execute()` 发出 `CommandEvent`，但 MainActivity 和 MainViewModel 都没有 `collect` 这个 Flow。12 个命令中只有 `StopNavigationCommand` 有直接副作用，其余全部是空操作。
- **影响**: 环形菜单点击"语音助手"、"避障"、"预览路线"、"历史"、"设置"、"采集"均无反应。

### C2: VoiceInteractionManager 中 VoiceCommandInterpreter 缺少 Context
- **文件**: `VoiceInteractionManager.kt:77`
- **问题**: `VoiceCommandInterpreter()` 无参构造，context 为 null，`loadKeywords()` 返回 `emptyMap()`。所有 113 个语音关键词全部失效。
- **影响**: 语音命令匹配永远失败，所有命令走 LLM 云端兜底或 UNKNOWN。

### C3: 三个独立 NavigationManager 实例
- **文件**: `MainActivity.kt` / `NavigationModule.kt` / `MainViewModel.kt`
- **问题**: MainActivity 手动创建一个实例，Hilt 提供一个单例，MainViewModel 注入一个。三者状态独立。
- **影响**: StopNavigationCommand 停的是 Hilt 实例（未启动过），MainActivity 的导航不受影响。

---

## HIGH 问题

### H1: 双数据库实例
- **文件**: `MainActivity.kt` + `AppModule.kt`
- **问题**: MainActivity 手动 `Room.databaseBuilder()`，Hilt 也提供一个。两个实例操作同一个文件。
- **影响**: WAL 冲突，潜在数据损坏。

### H2: VoiceModule 和 MainActivity 双份语音实例
- **文件**: `VoiceModule.kt` / `MainActivity.initServices()`
- **问题**: MainActivity 手动 `new BaiduSpeechManager()` / `new BaiduTtsManager()`，VoiceModule 也通过 Hilt 提供。
- **影响**: 两套独立 TTS 队列，token 重复获取。

### H3: 数据库破坏性迁移
- **文件**: `AppDatabase.kt`
- **问题**: version 2 + `fallbackToDestructiveMigration()`，无 Migration 对象。
- **影响**: 升级时所有语音记录丢失。

### H4: RingMenuView.brighten() 动画失效
- **文件**: `RingMenuView.kt:392`
- **问题**: `glowAlpha` 参数被 `coerceAtMost(0xFF)` 永远钳位到 255。
- **影响**: 选中扇形的呼吸光效不可见。

---

## MEDIUM 问题

| # | 问题 | 文件 |
|---|------|------|
| M1 | NavigationManager 不使用 AppConfigProvider，JSON 配置无效 | NavigationManager.kt |
| M2 | MenuConfig 手动创建绕过 Hilt | MainActivity.kt |
| M3 | CommandExecutor 接口与 CommandRouter 功能重复 | VoiceInteractionManager.kt |
| M4 | AppConfig 与 AppConstants 有 6 个重复常量定义 | AppConfig.kt / AppConstants.kt |
| M5 | TripPreviewService 创建自己的 OkHttpClient 而非使用 Hilt 单例 | TripPreviewService.kt |

---

## 修复计划

| 优先级 | 修复 | 涉及文件 |
|--------|------|---------|
| P0 | MainActivity 接入 Hilt 注入，消除双实例 | MainActivity.kt |
| P0 | CommandRouter 事件监听 | MainActivity.kt |
| P0 | VoiceInteractionManager 接收带 Context 的 Interpreter | VoiceInteractionManager.kt / VoiceModule.kt |
| P1 | 添加 Room Migration(1,2) | AppDatabase.kt |
| P1 | 修复 brighten() 动画 | RingMenuView.kt |
| P2 | 清理重复常量 | AppConfig.kt / AppConstants.kt |
