# 2026-06-16 修复报告

> 基于 `00_summary.md` 中 P0 优先级修复

---

## 修复清单

### C2/C3/C4: 消除 `!!` 强制解包（4 处）

| 文件 | 行号 | 修复前 | 修复后 |
|------|------|--------|--------|
| `MainActivity.kt` | 774 | `selectedDestLatLng!!` | `val dest = selectedDestLatLng ?: return` |
| `MainActivity.kt` | 838-839 | `currentLocation!!` + `selectedDestLatLng!!` | 本地变量捕获 + 判空 |
| `MainActivity.kt` | 864-868 | `currentLocation!!` + `selectedDestLatLng!!` | 本地变量捕获 + 判空 |
| `NavigationManager.kt` | 208 | `destination!!` | `val dest = destination ?: run { return }` |

**效果**：消除了快速到达、偏航重规划等场景下的 NPE 闪退风险。

### C1: VisionTestActivity 后台线程生命周期守卫

| 修改 | 说明 |
|------|------|
| `processFrame()` 增加 `isFinishing \|\| isDestroyed` 检查 | 比原来的 `destroyed` flag 更可靠 |
| 新增 `runOnUiThreadSafe()` 方法 | 双重检查（调度前 + 执行时），防止后台回调访问已销毁 UI |

**效果**：按返回键退出避障页面时不再闪退。

### S1: 清理 AndroidManifest 死条目

| 修改 | 说明 |
|------|------|
| 删除 `.collection.DataCollectionActivity` 条目 | 源文件已不存在（被重构为 CaptureHubActivity），避免 Manifest 合并或安装时异常 |

---

## 未修复的 HIGH 问题（放入下次审计）

| ID | 问题 | 原因 |
|----|------|------|
| H5 | BroadcastReceiver 缺少 RECEIVER_NOT_EXPORTED | 需要测试兼容性 |
| H2/H3 | MediaPlayer 多线程同步 | 需要重构播放器，改动大 |
| H4 | VoiceInteractionManager 持有 Activity 回调 | 需要将回调改为 WeakReference 或 ViewModel |
| V1-V5 | MainActivity 跨层依赖 | 需要完成 Fragment 拆分后才能彻底清理 |

---

## 编译验证

```
./gradlew :app:assembleDebug
BUILD SUCCESSFUL (7s)
```

## 提交

```
fix: resolve CRITICAL crash bugs from daily audit
- Remove 4 !! force unwraps in MainActivity + NavigationManager
- Add lifecycle guards in VisionTestActivity background threads
- Remove dead DataCollectionActivity from AndroidManifest
