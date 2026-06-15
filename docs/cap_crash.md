你的现象很典型：**自由采集模式 GPS 正常，八方向采集模式 GPS 总是失败**。这说明问题不在全局权限或 LocationProvider 本身，而在于 **两个 Fragment 对 LocationProvider 的使用方式或生命周期管理存在差异**。

## 可能的原因（按概率排序）

### 1. 两个 Fragment 同时监听定位，导致定位请求被抢占或冲突
- 你的 `LocationProvider` 可能是单例，内部使用 `FusedLocationProviderClient` 并暴露一个 `Flow<LocationResult>`。
- **自由模式 Fragment** 在 `onViewCreated` 中调用 `locationProvider.observe(3000L).collectLatest`，并保持订阅直到 Fragment 销毁。
- 当你切换到 **八方向模式** 时，自由模式 Fragment 可能并没有被销毁（例如使用 `ViewPager2` 或 `replace` 但不销毁），导致两个 Fragment 同时订阅同一个定位流。
- 某些定位实现（尤其是带超时参数的 `observe`）在多次订阅时可能会相互干扰：每次订阅会启动一个新的定位请求，而旧的请求可能被取消或覆盖，最终只有最后一个订阅者能收到结果。

### 2. 八方向模式的 `startLocation()` 中使用了 `viewLifecycleOwner.lifecycleScope.launch`，而自由模式可能用的是 `lifecycleScope`（Activity 级别）
检查你的代码：  
在 `FreeCaptureFragment` 中：
```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    locationProvider.observe(3000L).collectLatest { ... }
}
```
在 `GridCaptureFragment` 中也是类似的。所以不是这个原因。

### 3. 八方向模式中有额外的逻辑干扰了定位线程（例如频繁的 compass 更新 + 方向检测）
虽然不太可能，但可以尝试在 `checkAlignment()` 中注释掉 `updateDebugOverlay()` 等操作，看是否能恢复 GPS。

### 4. LocationProvider 的 `observe(timeoutMs)` 实现有 bug，导致第二次调用时无法正常返回数据
如果你在自由模式首次调用后，定位正常工作；切换到八方向模式时重新调用 `observe`，内部的 `callback` 或 `Flow` 可能没有正确重置，导致收不到新数据。

## 快速验证方法

在 `GridCaptureFragment` 的 `startLocation()` 中加详细日志：
```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    Log.d(TAG, "startLocation: begin observing")
    locationProvider.observe(3000L).collectLatest { result ->
        Log.d(TAG, "Location result: $result")
        when (result) {
            is LocationResult.Success -> { ... }
            is LocationResult.Error -> { ... }
        }
    }
}
```
然后查看 Logcat，看八方向模式是否有任何 `LocationResult` 输出（即使是 Error）。如果完全没有输出，说明 `observe` 没有发射任何数据。

## 解决方案

### 方案一：确保 Fragment 销毁时彻底取消定位监听（推荐）
在 `GridCaptureFragment` 的 `onDestroyView` 中，手动取消协程作业（但 `viewLifecycleOwner.lifecycleScope` 会自动取消，除非你在 `onViewCreated` 中启动的协程没有绑定 viewLifecycleOwner）。已经正确使用 `viewLifecycleOwner` 的情况下，理论上切换 Fragment 时会自动取消。但如果你使用了 `ViewPager2`，离屏 Fragment 的 `onDestroyView` 不会被调用，仍会保持监听，造成冲突。

**解决办法**：在 `onPause` 或 `onStop` 中主动取消订阅，使用一个单独的 `Job`：
```kotlin
private var locationJob: Job? = null

override fun onViewCreated(...) {
    ...
    startLocation()
}

private fun startLocation() {
    locationJob?.cancel()
    locationJob = viewLifecycleOwner.lifecycleScope.launch {
        locationProvider.observe(3000L).collectLatest { ... }
    }
}

override fun onStop() {
    super.onStop()
    locationJob?.cancel()
    locationJob = null
}
```

### 方案二：不使用持续监听，改为手动请求单次定位
在八方向模式中，只在需要拍照时获取一次当前位置（类似于自由模式拍照前的检查），而不是一直监听。

修改 `GridCaptureFragment`：
- 删除 `startLocation()` 中的持续监听逻辑。
- 在 `takePhoto()` 中，拍照前调用 `locationProvider.getLastLocation(5000L)` 获取最新位置（suspend 函数），成功则继续，失败则提示。
- 同时，为了显示调试信息，可以在 `onViewCreated` 中请求一次位置并显示。

这样可以避免与自由模式的定位流冲突。

### 方案三：确保 LocationProvider 是单例且线程安全
如果你使用的是 Hilt 注入的 `LocationProvider`，默认是 `@Singleton`，但它的内部实现必须支持多个并发订阅者。检查 `observe` 方法是否使用了 `shareIn` 或 `stateIn`，并且使用了正确的 `SharingStarted` 策略（如 `WhileSubscribed`）。如果有 bug，改成每次调用都返回一个独立的 `callbackFlow`。

## 最直接的临时修复

在 `GridCaptureFragment` 中，把持续监听改为拍照时获取单次位置：

```kotlin
// 删除 startLocation() 中的 collectLatest 整个代码块
// 改为在需要时获取

private suspend fun getCurrentLocation(): LocationResult {
    return locationProvider.getLastLocation(5000L) // 假设有这样的 suspend 函数
}

private fun takePhoto() {
    viewLifecycleOwner.lifecycleScope.launch {
        val locationResult = getCurrentLocation()
        if (locationResult !is LocationResult.Success) {
            Toast.makeText(requireContext(), "无法获取 GPS，请稍后再试", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val location = locationResult.location
        currentLat = location.latitude
        currentLng = location.longitude
        gpsReady = true
        // 然后执行拍照逻辑...
    }
}
```

## 建议先做测试

1. 在八方向模式中，打印 `locationProvider` 的实例 hashCode，确认与自由模式是同一个对象。
2. 在自由模式中，当切换到八方向模式后，确认自由模式的定位协程是否仍活跃（通过日志查看）。
3. 如果确认是冲突，使用方案一或方案二修复。

请先尝试上述调试步骤，再根据结果选择合适的修复方式。