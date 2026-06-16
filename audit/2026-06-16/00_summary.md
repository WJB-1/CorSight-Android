# 2026-06-16 每日审计综合报告

> 审计时间: 2026-06-16 22:00
> 项目: CorSight Android v2.0
> 分支: refactor/architecture-v2
> 审计范围: 全部 116 个 Kotlin 源文件 + 构建配置 + 资源文件

---

## 一、总体发现

| 维度 | 总发现 | CRITICAL | HIGH | MEDIUM | LOW |
|------|--------|----------|------|--------|-----|
| 跨层调用违规 | 40 | 5 | 24 | 8 | 3 |
| 耦合度 + 可复用性 | 36 | — | — | — | — |
| 隐形 Bug（闪退风险） | 26 | 5 | 9 | 8 | 4 |
| 结构一致性 | 20 | 1 | 3 | 6 | 10 |
| **合计** | **122** | **11** | **36** | **22** | **17** |

可消除代码量估计：~1,800 行（通过提取工具类和消除重复）

---

## 二、必须立即修复的 CRITICAL 问题（11 个）

### Bug 闪退类（5 个）

| ID | 文件 | 问题 | 影响 |
|----|------|------|------|
| C1 | VisionTestActivity | 后台线程访问已销毁 Activity 的 binding | 按返回键闪退 |
| C2 | MainActivity:774,838 | `selectedDestLatLng!!` 强制解包 | 快速到达目的地时 NPE 闪退 |
| C3 | NavigationManager:209 | `destination!!` 强制解包 | 偏航重规划时 NPE 闪退 |
| C4 | MainActivity | `routePoints` 相关强制解包 | 路线为空时闪退 |
| C5 | ObstacleAlertTracker | 推理线程和主线程同时访问无同步 | 切换源时 ConcurrentModificationException |

### 跨层调用类（5 个）

| ID | 文件 | 问题 |
|----|------|------|
| V1 | MainActivity:57 | 直接引用 AppDatabase（绕过 Repository） |
| V2 | MainActivity:67 | 直接引用 TtsPlayer（数据层） |
| V3 | MainActivity:68 | 直接引用 TtsPreloader（数据层） |
| V4 | MainActivity:61 | VoiceRecordAdapter 放在 data/ 包 |
| V5 | MainActivity:60 | 直接引用 VoiceRecord entity |

### 结构类（1 个）

| ID | 文件 | 问题 |
|----|------|------|
| S1 | AndroidManifest.xml | DataCollectionActivity 已不存在但仍在 Manifest 中注册 |

---

## 三、HIGH 优先级问题（36 个）

### Bug 类（9 个）

| 问题 | 影响 |
|------|------|
| VisionTestActivity BroadcastReceiver 缺少 RECEIVER_NOT_EXPORTED flag | Android 13+ 崩溃 |
| BaiduTtsManager/TtsPlayer MediaPlayer 多线程访问无同步 | 双重 release 崩溃 |
| VoiceInteractionManager 持有已销毁 Activity 的回调 | LLM 回调时崩溃 |
| hideKeyboard() 中 `currentFocus!!` 强制解包 | 竞态 NPE |
| NavigationManager `routePoints` 未判空 | 路线为空时崩溃 |

### 跨层类（24 个）

- MainActivity 直接依赖 10+ 个 domain 层具体类
- ViewModel 直接依赖 domain 具体类而非接口
- MenuConfig (domain) 反向依赖 RingMenuItem (ui)
- 动画层反向依赖 View 类

### 结构类（3 个）

- 11 个文件在根包应迁入子包
- 百度 API Key 明文存储在 strings.xml
- 命名空间不一致 (com.example vs com.corsight)

---

## 四、报告文件索引

| 文件 | 内容 | 发现数 |
|------|------|--------|
| `01_cross_layer_violations.md` | 跨层调用违规检查 | 40 |
| `02_coupling_reusability.md` | 耦合度 + 可复用工具识别 | 36 |
| `03_crash_bugs.md` | 隐形 Bug（闪退/崩溃风险） | 26 |
| `04_structure_consistency.md` | 项目结构一致性检查 | 20 |

---

## 五、本次修复计划

**只修 CRITICAL + 高影响 HIGH**，其余放入下次审计跟踪。

| 优先级 | 修复项 | 预计改动 |
|--------|--------|---------|
| P0 | C2/C3/C4: 消除所有 `!!` 强制解包 | ~20 行 |
| P0 | C1: VisionTestActivity 后台线程生命周期守卫 | ~15 行 |
| P0 | C5: ObstacleAlertTracker 线程安全 | ~10 行 |
| P0 | S1: 清理 AndroidManifest 死条目 | 2 行 |
| P1 | V1-V5: MainActivity 中数据层直接引用（标记 TODO，不全改） | 标记 |
| P1 | H5: BroadcastReceiver 注册修复 | 5 行 |
