# Daily Audit Skill

> 每日自动项目维护检查流程

## 触发时机
每天晚上自动执行，或用户输入 `/daily-audit` 手动触发。

## 流程

### Phase 1: 检查（4 个并行 Agent）

1. **跨层调用检查** — UI→Domain→Data→Config/Util 单向依赖，反向即违规
2. **耦合度 + 可复用性扫描** — 重复代码、内联逻辑、散落常量
3. **隐形 Bug 扫描** — NPE、生命周期、线程安全、资源泄漏、Hilt 注入
4. **结构一致性** — 包结构、命名规范、依赖版本、资源引用

### Phase 2: 报告输出

```
audit/<YYYY-MM-DD>/
├── 00_summary.md              # 综合摘要
├── 01_cross_layer_violations.md
├── 02_coupling_reusability.md
├── 03_crash_bugs.md
├── 04_structure_consistency.md
└── 05_fix_report.md           # 修复记录
```

### Phase 3: 修复

按严重度修复：CRITICAL > HIGH > MEDIUM > LOW。
每次修复后编译验证（`./gradlew :app:assembleDebug`）。

### Phase 4: 总结

输出 `05_fix_report.md`，记录修复了什么、改了哪些文件、剩余问题。
Git commit + push。

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
