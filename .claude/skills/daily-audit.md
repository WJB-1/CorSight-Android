# Daily Audit Skill

> 每日自动项目维护检查流程

## 触发时机
每天晚上自动执行，或用户输入 `/daily-audit` 手动触发。

## 核心规则

> **任务未完成条件：所有已发现的问题必须修复完毕才算结束。**
>
> - 检查发现的问题不能只记录不修
> - 不能以"改动量太大"或"影响范围广"为由跳过修复
> - 修复优先级：CRITICAL > HIGH > MEDIUM > LOW
> - 每个修复必须通过编译验证（`./gradlew :app:assembleDebug`）
> - 如果某个问题确实需要更大的重构才能解决（如 MainActivity 拆分），在修复报告中标注为"已规划具体方案，下一步执行"，并创建对应的 Task
> - 本轮能修的绝不留到下一轮

## 流程

### Phase 1: 检查（4 个并行 Agent）

1. **跨层调用检查** — UI→Domain→Data→Config/Util 单向依赖，反向即违规
2. **耦合度 + 可复用性扫描** — 重复代码、内联逻辑、散落常量
3. **隐形 Bug 扫描** — NPE、生命周期、线程安全、资源泄漏、Hilt 注入
4. **结构一致性** — 包结构、命名规范、依赖版本、资源引用

### Phase 2: 报告输出

```
audit/<YYYY-MM-DD>/
├── 00_summary.md              # 综合摘要（必须包含修复计划）
├── 01_cross_layer_violations.md
├── 02_coupling_reusability.md
├── 03_crash_bugs.md
├── 04_structure_consistency.md
└── 05_fix_report.md           # 修复记录（必须覆盖所有发现）
```

### Phase 3: 修复（必须全部完成）

修复规则：
1. 按严重度顺序修复：CRITICAL → HIGH → MEDIUM → LOW
2. 每修一批问题后执行 `./gradlew :app:assembleDebug` 验证编译
3. 如果修复引入新问题，立即修复新问题再继续
4. 某些问题如果需要大重构（如 MainActivity 拆分为 Fragment），在 `05_fix_report.md` 中记录具体方案并创建 Task，但**本轮能做的前置工作必须做完**

修复轮次（直到清零）：
```
while (未修复问题数 > 0):
    选择最高优先级的一批问题
    修复 → 编译验证
    如果编译失败 → 修复编译错误 → 重新验证
    更新 05_fix_report.md
```

### Phase 4: 总结 + 推送

1. 输出 `05_fix_report.md`，记录：
   - 本轮修复了什么（改了哪些文件、修复了哪些问题）
   - 遗留问题及具体方案（不接受"待处理"这种模糊描述）
   - 下次审计需关注的点
2. 更新 `ARCHITECTURE.md`（如果架构有变化）
3. Git commit + push
4. 向用户汇报：发现数、修复数、遗留数、编译状态

### 不可接受的结束状态

以下情况不能结束任务：
- ❌ "这个问题比较复杂，先记录下来" — 必须给出具体方案
- ❌ "改动量太大，建议下一步再做" — 必须本轮能做多少做多少
- ❌ 编译不通过就提交 — 必须编译通过
- ❌ 05_fix_report.md 中遗留项数量 > 发现项数量的 50% — 修复率必须过半

## 检查清单

### 跨层调用
- [ ] UI 层是否直接调用 DAO / Database？
- [ ] Domain 层是否 import Activity / View？
- [ ] Data 层是否 import VoiceInteractionManager / NavigationManager？
- [ ] Config/Util 是否反向依赖上层？

### 耦合度
- [ ] 重复的长按手势逻辑？
- [ ] 重复的网络错误处理？
- [ ] 重复的权限请求代码？
- [ ] 散落的 SharedPreferences key 常量？
- [ ] 散落的 URL / 阈值？

### 隐形 Bug
- [ ] lateinit var 未初始化就使用？
- [ ] !! 强制解包？
- [ ] Activity.onDestroy 后仍有回调？
- [ ] MediaPlayer / BroadcastReceiver 未释放？
- [ ] UI 更新不在主线程？
- [ ] Hilt 缺少 @AndroidEntryPoint？

### 结构一致性
- [ ] 空包 / 单文件包？
- [ ] 未使用的资源（strings/colors/drawables）？
- [ ] build.gradle 依赖版本一致？
- [ ] AndroidManifest 所有组件已注册？
- [ ] .gitignore 覆盖 build/？
