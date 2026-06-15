根据你提供的代码，项目当前使用的是 **百度在线 TTS（文本转语音）服务**，具体由 `BaiduTtsManager` 负责实现，而不是 Android 系统自带的 TextToSpeech。

## 证据

1. **依赖注入**  
   `MainActivity.kt` 中通过 Hilt 注入：
   ```kotlin
   @Inject lateinit var baiduTts: BaiduTtsManager
   ```

2. **初始化与回调**  
   在 `initServices()` 中调用了 `baiduTts.init()` 并设置了 `TtsCallback`，所有语音播报均通过 `baiduTts.speak()` 触发。

3. **语音交互管理器**  
   `VoiceInteractionManager` 构造函数接收的 `ttsManager` 类型也是 `BaiduTtsManager?`，内部 `speakFeedback()`、`speakForce()` 等最终都调用该实例的方法。

4. **没有系统 TTS 的痕迹**  
   整个项目中没有任何 `android.speech.tts.TextToSpeech` 的导入或使用，也没有切换 TTS 引擎的逻辑。

## 为什么你会感觉延迟很高？

百度 TTS 是一个纯在线服务，其流程为：

```
文本 → 网络请求 → 云端合成 → 返回音频 → 播放
```

每一个步骤都会产生耗时：
- **网络往返**：通常 200~500ms
- **云端合成**：视文本长度，100~300ms
- **音频加载与播放**：额外数十毫秒

相比系统离线 TTS（如 Google 语音引擎），在线合成的首字延迟明显更高，尤其在弱网环境下。

## 可能的优化方向

如果你希望降低延迟，可以考虑：

1. **混合方案**  
   - 优先使用系统 TTS（离线、低延迟）  
   - 当系统引擎不可用或音质不佳时，fallback 到百度 TTS

2. **缓存常用短语**  
   将导航指令（如“前方右转”“到达目的地”）预先合成并保存为音频文件，需要时直接播放。

3. **保持 TTS 引擎常驻**  
   目前 `BaiduTtsManager` 已经是单例，但可以预连接或维持长连接来减少初始化开销。

4. **尝试流式合成**  
   如果百度 SDK 支持，开启 chunked 传输，边接收边播放。

如果你需要我帮忙设计一个“系统 TTS + 百度 TTS”双引擎降级方案，可以告诉我。