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
 * 初始化策略：
 * 1. 尝试系统 TTS，5 秒内没就绪则自动切百度
 * 2. 系统 TTS 初始化失败或不支持中文 → 直接用百度
 * 3. 首次 speak() 时如果系统 TTS 还没就绪 → 单次用百度，不永久切换
 */
class UnifiedTtsManager(
    private val context: Context,
    private val baiduApiKey: String = "",
    private val baiduSecretKey: String = ""
) {
    companion object {
        private const val TAG = "UnifiedTtsManager"
        private const val SYSTEM_TTS_TIMEOUT_MS = 5000L
    }

    // ── 系统 TTS ──
    private var systemTts: TextToSpeech? = null
    @Volatile private var systemTtsReady = false
    @Volatile private var systemTtsFailed = false

    // ── 百度 TTS（懒加载）──
    private var baiduTts: BaiduTtsManager? = null

    // ── 播报队列 ──
    private val speechQueue: Queue<String> = LinkedList()
    private var isSpeaking = false
    @Volatile private var stopped = false

    private val mainHandler = Handler(Looper.getMainLooper())

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

        // 超时保护：5 秒内系统 TTS 没就绪 → 标记失败
        mainHandler.postDelayed({
            if (!systemTtsReady && !systemTtsFailed) {
                Log.w(TAG, "System TTS timeout (${SYSTEM_TTS_TIMEOUT_MS}ms), marking as failed")
                systemTtsFailed = true
                // 排空等待中的文本到百度
                drainQueueToBaidu()
            }
        }, SYSTEM_TTS_TIMEOUT_MS)

        systemTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = systemTts?.setLanguage(Locale.CHINA)
                val chineseOk = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED

                if (!chineseOk) {
                    Log.w(TAG, "System TTS: Chinese not supported (result=$result)")
                    val defaultResult = systemTts?.setLanguage(Locale.getDefault())
                    if (defaultResult == TextToSpeech.LANG_MISSING_DATA
                        || defaultResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "System TTS: no usable language, failing")
                        systemTtsFailed = true
                        mainHandler.post { callback?.onTtsReady() }  // 百度兜底
                        return@TextToSpeech
                    }
                }

                systemTts?.setSpeechRate(1.1f)
                systemTts?.setPitch(1.0f)

                systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { onPlaybackFinished() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "System TTS utterance error: $utteranceId")
                        mainHandler.post { onPlaybackFinished() }
                    }
                })

                systemTtsReady = true
                Log.d(TAG, "System TTS ready (Chinese=${chineseOk})")
                mainHandler.post {
                    callback?.onTtsReady()
                    // 处理在初始化期间排队的文本
                    processQueue()
                }
            } else {
                Log.e(TAG, "System TTS init failed (status=$status)")
                systemTtsFailed = true
                mainHandler.post { callback?.onTtsReady() }
            }
        }
    }

    // ═══════════════════════════════════════════
    // 对外 API
    // ═══════════════════════════════════════════

    fun speak(text: String?) {
        if (text.isNullOrEmpty()) return
        stopped = false

        synchronized(speechQueue) {
            speechQueue.offer(text)
            Log.d(TAG, "Speak queued: [$text] ready=$systemTtsReady failed=$systemTtsFailed queue=${speechQueue.size}")
        }
        processQueue()
    }

    fun stopPlayback() {
        stopped = true
        synchronized(speechQueue) { speechQueue.clear() }
        systemTts?.stop()
        baiduTts?.stopPlayback()
        isSpeaking = false
    }

    fun flushQueue() {
        synchronized(speechQueue) { speechQueue.clear() }
        systemTts?.stop()
        baiduTts?.flushQueue()
        isSpeaking = false
    }

    fun isSpeaking(): Boolean = isSpeaking

    fun destroy() {
        stopPlayback()
        systemTts?.stop()
        systemTts?.shutdown()
        systemTts = null
        systemTtsReady = false
        systemTtsFailed = false
        baiduTts?.destroy()
        baiduTts = null
    }

    // ═══════════════════════════════════════════
    // 内部：队列处理
    // ═══════════════════════════════════════════

    private fun processQueue() {
        if (isSpeaking || stopped) return

        val next: String
        synchronized(speechQueue) {
            next = speechQueue.poll() ?: return
            isSpeaking = true
        }

        // ── 系统 TTS 就绪 → 用系统 TTS（低延迟）──
        if (systemTtsReady) {
            val tts = systemTts
            if (tts != null) {
                Log.d(TAG, "System TTS speaking: [$next]")
                tts.speak(next, TextToSpeech.QUEUE_FLUSH, null, next)
                return
            }
        }

        // ── 系统 TTS 还在初始化 → 等 200ms 重试 ──
        if (!systemTtsReady && !systemTtsFailed) {
            Log.d(TAG, "System TTS initializing, re-queuing: [$next]")
            synchronized(speechQueue) { speechQueue.offer(next) }
            isSpeaking = false
            mainHandler.postDelayed({ processQueue() }, 200)
            return
        }

        // ── 系统 TTS 不可用 → 百度 TTS ──
        Log.d(TAG, "Baidu TTS speaking (fallback): [$next]")
        ensureBaiduTts()
        baiduTts?.speak(next)
    }

    /**
     * 系统 TTS 超时后，把队列中所有待播报文本转给百度。
     */
    private fun drainQueueToBaidu() {
        Log.d(TAG, "Draining queue to Baidu TTS")
        ensureBaiduTts()
        synchronized(speechQueue) {
            while (speechQueue.isNotEmpty()) {
                val text = speechQueue.poll() ?: break
                baiduTts?.speak(text)
            }
        }
    }

    private fun ensureBaiduTts() {
        if (baiduTts == null) {
            Log.d(TAG, "Lazy-init Baidu TTS")
            baiduTts = BaiduTtsManager(context, baiduApiKey, baiduSecretKey).apply { init() }
        }
    }

    private fun onPlaybackFinished() {
        isSpeaking = false
        if (!stopped) {
            processQueue()
        }
    }
}
