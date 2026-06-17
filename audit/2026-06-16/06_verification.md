# 2026-06-16 问题修复验证

> 验证时间: 2026-06-17
> 验证方式: 逐项 grep 源码确认

---

## CRITICAL 问题（11 个）

| ID | 问题 | 状态 | 验证方式 |
|----|------|------|---------|
| C1 | VisionTestActivity 后台线程生命周期守卫 | ✅ **已修复** | `runOnUiThreadSafe()` 存在，`processFrame()` 有三重检查 |
| C2 | MainActivity `selectedDestLatLng!!` | ✅ **已修复** | 改为 `val dest = selectedDestLatLng ?: return` |
| C3 | NavigationManager `destination!!` | ✅ **已修复** | 改为 `val dest = destination ?: run { return }` |
| C4 | NavigationManager `routePoints!!` | ✅ **已修复** | `routePoints!!` 消除为 0 处 |
| C5 | ObstacleAlertTracker 线程安全 | ⚠️ **未修复** | 线程安全问题仍在，需加 ConcurrentHashMap 或 @Synchronized |
| V1 | MainActivity 直接引用 AppDatabase | ⚠️ **仍存在** | ViewModel 抽取后仍有直接引用 |
| V2 | MainActivity 直接引用 TtsPlayer | ⚠️ **仍存在** | 同上 |
| V3 | MainActivity 直接引用 TtsPreloader | ⚠️ **仍存在** | 同上 |
| V4 | VoiceRecordAdapter 在 data/ 包 | ✅ **已修复** | 已迁移到 `ui/main/adapter/` |
| V5 | MainActivity 直接引用 VoiceRecord | ⚠️ **仍存在** | ViewModel 中仍在使用 |
| S1 | AndroidManifest 死条目 | ✅ **已修复** | DataCollectionActivity 已删除 |

**CRITICAL 修复率: 6/11 = 55%**

---

## HIGH 问题（9 个 Bug 类）

| ID | 问题 | 状态 | 验证方式 |
|----|------|------|---------|
| H1 | mapView lateinit 崩溃 | ✅ **已修复** | 改为 `var mapView: MapView? = null` |
| H2 | BaiduTtsManager MediaPlayer 线程安全 | ✅ **已修复** | `mediaPlayerLock` + `synchronized` |
| H3 | TtsPlayer MediaPlayer 线程安全 | ✅ **已修复** | `lock` + `releaseMediaPlayer()` |
| H4 | VoiceInteractionManager 回调泄漏 | ✅ **已修复** | `release()` 方法 + `onDestroy` 调用 |
| H5 | BroadcastReceiver 缺少 RECEIVER_NOT_EXPORTED | ✅ **已修复** | API 33+ 使用 `RECEIVER_NOT_EXPORTED` |
| H6 | `currentFocus!!` 强制解包 | ❌ **未修复** | `MainActivity.kt:604` 仍有 `currentFocus!!` |
| H7 | NavigationManager routePoints 竞态 | ✅ **已修复** | 本地变量捕获 |
| H8 | ViewModel 直接依赖 domain 具体类 | ⚠️ **部分修复** | MainViewModel 仍有直接依赖 |
| H9 | MenuConfig 反向依赖 RingMenuItem | ❌ **未修复** | MenuConfig 仍 import ui.ringmenu.RingMenuItem |

**HIGH Bug 修复率: 5/9 = 56%**

---

## MEDIUM 问题

| 问题 | 状态 |
|------|------|
| 通配符 import | ✅ 已修复（4 个文件） |
| 未使用的 import | ✅ 已修复 |
| Adapter 在 data/ 包 | ✅ 已迁移到 ui/main/adapter/ |
| ObstacleWarningNotifier 全局单例 | ✅ 已删除 |
| 字符串资源未使用标注 | ✅ 已添加 TODO 注释 |

---

## 未修复清单（需下一轮处理）

| 优先级 | 问题 | 文件 | 修复方案 |
|--------|------|------|---------|
| P0 | `currentFocus!!` 强制解包 | MainActivity:604 | `val focus = currentFocus ?: return` |
| P1 | C5 ObstacleAlertTracker 线程安全 | ObstacleAlertTracker.kt | 内部 Map 改为 ConcurrentHashMap |
| P1 | V1-V3 MainActivity 跨层依赖 | MainActivity.kt | 后续迭代中逐步将 TtsPlayer/TtsPreloader 引用移到 ViewModel |
| P2 | H9 MenuConfig 反向依赖 | MenuConfig.kt | RingMenuItem 移到 domain 层 |
| P2 | 11 个文件在根包 | 多个文件 | 迁入 obstacle/ 子包 |
| P2 | 百度 API Key 明文 | strings.xml | 迁移到 local.properties |
| P3 | BaiduTtsManager 队列并发 | BaiduTtsManager.kt | 队列操作加锁 |
