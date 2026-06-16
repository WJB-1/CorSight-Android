package com.example.voicenavigation.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.LinkedList
import java.util.Locale
import java.util.Queue

/**
 * 统一 TTS 管理器：系统离线 TTS 优先，百度在线 TTS 备选。
 *
 * 设计目标：
 * - 菜单滑动等高频场景用系统 TTS（零网络延迟，首字 <100ms）
 * - 导航播报等长文本场景可切到百度 TTS（音质更好）
 * - 对外接口与 BaiduTtsManager 兼容（speak/flushQueue/stopPlayback/destroy）
 *
 * 使用方式（Hilt 注入后直接替换 BaiduTtsManager）：
 * ```kotlin
 * @Inject lateinit var tts: UnifiedTtsManager
 * tts.speak("语音助手已就绪")
 * ```
 */
class UnifiedTtsManager(
    private val context: Context,
    private val baiduApiKey: String = "",
    private val baiduSecretKey: String = ""
) {
    companion object {
        private const val TAG = "UnifiedTtsManager"
    }

    // ── 系统 TTS ──
    private var systemTts: TextToSpeech? = null
    private var systemTtsReady = false

    // ── 百度 TTS（懒加载，只在系统 TTS 不可用或主动切换时初始化）──
    private var baiduTts: BaiduTtsManager? = null
    private var useBaidu = false

    // ── 播报队列 ──
    private val speechQueue: Queue<String> = LinkedList()
    private var isSpeaking = false
    @Volatile private var stopped = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 回调 ──
    interface TtsCallback {
        fun onTtsReady()
        fun onTtsError(error: String)
    }

    var callback: TtsCallback? = null

    // ═══════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════

    fun init() {
        Log.d(TAG, "Initializing: trying system TTS first")
        initSystemTts()
    }

    private fun initSystemTts() {
        systemTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = systemTts?.setLanguage(Locale.CHINA)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "System TTS: Chinese not supported, trying default locale")
                    systemTts?.setLanguage(Locale.getDefault())
                }

                // 设置语速和音调（适配盲人用户，稍快一点）
                systemTts?.setSpeechRate(1.1f)
                systemTts?.setPitch(1.0f)

                // 监听播报完成
                systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { onPlaybackFinished() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { onPlaybackFinished() }
                    }
                })

                systemTtsReady = true
                Log.d(TAG, "System TTS ready (Chinese)")
                mainHandler.post { callback?.onTtsReady() }
            } else {
                Log.e(TAG, "System TTS init failed (status=$status), will use Baidu as fallback")
                // 不设 useBaidu=true，processQueue 中会按需懒加载百度 TTS
                mainHandler.post { callback?.onTtsReady() }  // 通知就绪（百度兜底）
            }
        }
    }

    private fun initBaiduTts() {
        if (baiduApiKey.isEmpty() || baiduSecretKey.isEmpty()) {
            Log.e(TAG, "No Baidu TTS credentials, TTS unavailable")
            mainHandler.post { callback?.onTtsError("TTS 不可用：系统 TTS 不支持且百度 Key 未配置") }
            return
        }
        useBaidu = true
        baiduTts = BaiduTtsManager(context, baiduApiKey, baiduSecretKey).apply {
            callback = object : BaiduTtsManager.TtsCallback {
                override fun onTtsReady() {
                    Log.d(TAG, "Baidu TTS ready (fallback)")
                    mainHandler.post { this@UnifiedTtsManager.callback?.onTtsReady() }
                }
                override fun onTtsError(error: String) {
                    Log.e(TAG, "Baidu TTS error: $error")
                    mainHandler.post { this@UnifiedTtsManager.callback?.onTtsError(error) }
                }
            }
            init()
        }
    }

    // ═══════════════════════════════════════════
    // 对外 API（与 BaiduTtsManager 接口兼容）
    // ═══════════════════════════════════════════

    /**
     * 加入播报队列。系统 TTS 离线播报，延迟极低。
     * 如果系统 TTS 不可用，自动回退到百度 TTS。
     */
    fun speak(text: String?) {
        if (text.isNullOrEmpty()) return
        stopped = false

        if (useBaidu) {
            baiduTts?.speak(text)
            return
        }

        synchronized(speechQueue) {
            speechQueue.offer(text)
            Log.d(TAG, "Speak queued: [$text] queue=${speechQueue.size}")
        }
        processQueue()
    }

    /**
     * 停止播报并清空队列。
     */
    fun stopPlayback() {
        stopped = true
        if (useBaidu) {
            baiduTts?.stopPlayback()
            return
        }
        synchronized(speechQueue) { speechQueue.clear() }
        systemTts?.stop()
        isSpeaking = false
    }

    /**
     * 清空队列（不打断当前正在播放的那条）。
     * 用于菜单滑动时打断排队的旧播报。
     */
    fun flushQueue() {
        if (useBaidu) {
            baiduTts?.flushQueue()
            return
        }
        synchronized(speechQueue) { speechQueue.clear() }
        // 系统 TTS stop 会打断当前播放，这正是 flush 的语义
        systemTts?.stop()
        isSpeaking = false
    }

    fun isSpeaking(): Boolean {
        return if (useBaidu) baiduTts?.isSpeaking() == true else isSpeaking
    }

    /**
     * 强制使用百度 TTS（音质更好，但有网络延迟）。
     * 可用于导航播报等对音质要求高的场景。
     */
    fun switchToBaidu() {
        if (!useBaidu && baiduTts == null) {
            initBaiduTts()
        } else if (baiduTts != null) {
            useBaidu = true
        }
    }

    /**
     * 切回系统 TTS（低延迟）。
     */
    fun switchToSystem() {
        useBaidu = false
    }

    fun destroy() {
        stopPlayback()
        systemTts?.stop()
        systemTts?.shutdown()
        systemTts = null
        systemTtsReady = false
        baiduTts?.destroy()
        baiduTts = null
    }

    // ═══════════════════════════════════════════
    // 内部：系统 TTS 队列处理
    // ═══════════════════════════════════════════

    private fun processQueue() {
        if (isSpeaking || stopped) return
        val next: String
        synchronized(speechQueue) {
            next = speechQueue.poll() ?: return
            isSpeaking = true
        }
        Log.d(TAG, "System TTS speaking: [$next]")

        val tts = systemTts
        if (tts == null) {
            // 系统 TTS 完全不可用（可能还在初始化），单次回退百度
            Log.w(TAG, "System TTS null, falling back to Baidu for this utterance")
            isSpeaking = false
            if (baiduTts == null) {
                // 懒加载百度 TTS，但不设 useBaidu=true（下次仍尝试系统 TTS）
                baiduTts = BaiduTtsManager(context, baiduApiKey, baiduSecretKey).apply { init() }
            }
            baiduTts?.speak(next)
            return
        }

        if (!systemTtsReady) {
            // 系统 TTS 正在初始化（异步回调还没到），等 200ms 重试
            Log.d(TAG, "System TTS initializing, retrying in 200ms")
            isSpeaking = false
            synchronized(speechQueue) { speechQueue.offer(next) }  // 放回队列
            mainHandler.postDelayed({ processQueue() }, 200)
            return
        }

        // 使用 QUEUE_FLUSH 模式：新文本会打断旧文本
        tts.speak(next, TextToSpeech.QUEUE_FLUSH, null, next)
    }

    private fun onPlaybackFinished() {
        isSpeaking = false
        if (!stopped) {
            processQueue()
        }
    }
}
