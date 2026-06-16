package com.example.voicenavigation.data.tts

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.voicenavigation.stt.BaiduTtsManager
import java.io.File
import java.util.LinkedList
import java.util.Queue

/**
 * TTS 播放器：优先播放本地缓存音频，未缓存的回退到 BaiduTtsManager 在线合成。
 *
 * 这是 UnifiedTtsManager 的简化替代方案：
 * - 不依赖系统 TTS（小米等设备可能没有）
 * - 不做系统 TTS 初始化（避免 5 秒超时等待）
 * - 直接查缓存 → 播放，或在线合成 → 缓存 → 播放
 *
 * 延迟对比：
 * | 场景 | 延迟 |
 * |------|------|
 * | 缓存命中 | <50ms（纯本地文件播放） |
 * | 缓存未命中（首次） | 200-500ms（百度在线合成） |
 * | 缓存未命中 + token 获取 | 300-800ms（仅首次启动） |
 */
class TtsPlayer(
    private val cache: TtsAudioCache,
    private val baiduTts: BaiduTtsManager
) {

    companion object {
        private const val TAG = "TtsPlayer"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private val speechQueue: Queue<String> = LinkedList()
    private var isPlaying = false
    @Volatile private var stopped = false

    /**
     * 播报文本。缓存命中直接播放，否则回退百度在线合成。
     */
    fun speak(text: String?) {
        if (text.isNullOrEmpty()) return
        stopped = false

        synchronized(speechQueue) {
            speechQueue.offer(text)
            Log.d(TAG, "Speak queued: [$text] cache=${cache.has(text)} queue=${speechQueue.size}")
        }
        processQueue()
    }

    /**
     * 清空队列（打断当前播放的旧文本，新文本继续播放）。
     */
    fun flushQueue() {
        synchronized(speechQueue) { speechQueue.clear() }
        mediaPlayer?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        mediaPlayer = null
        isPlaying = false
    }

    /**
     * 停止播放并清空队列。
     */
    fun stopPlayback() {
        stopped = true
        synchronized(speechQueue) { speechQueue.clear() }
        mediaPlayer?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        mediaPlayer = null
        isPlaying = false
    }

    fun isSpeaking(): Boolean = isPlaying

    fun destroy() {
        stopPlayback()
    }

    // ── 内部 ──

    private fun processQueue() {
        if (isPlaying || stopped) return
        val next: String
        synchronized(speechQueue) {
            next = speechQueue.poll() ?: return
            isPlaying = true
        }

        val cachedFile = cache.get(next)
        if (cachedFile != null) {
            // 缓存命中 → 直接播放本地文件
            Log.d(TAG, "Cache hit: playing [$next] from disk")
            playFile(cachedFile)
        } else {
            // 缓存未命中 → 百度在线合成（合成完成后自动缓存）
            Log.d(TAG, "Cache miss: synthesizing [$next] via Baidu")
            baiduTts.speak(next)
            // BaiduTtsManager 内部处理播放和队列，
            // 我们只需要标记状态让 processQueue 能继续
            mainHandler.postDelayed({
                isPlaying = false
                if (!stopped) processQueue()
            }, 100)  // 给 BaiduTtsManager 时间接管
        }
    }

    private fun playFile(file: File) {
        try {
            mediaPlayer?.release()
            val mp = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    this@TtsPlayer.isPlaying = false
                    if (!stopped) processQueue()
                }
                setOnErrorListener { p, _, _ ->
                    p.release()
                    mediaPlayer = null
                    this@TtsPlayer.isPlaying = false
                    if (!stopped) processQueue()
                    true
                }
                // 同步准备：文件已在本地磁盘，I/O 延迟可忽略
                // 比 prepareAsync() 少一次线程切换 + 回调等待
                prepare()
                start()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play cached file", e)
            isPlaying = false
            if (!stopped) processQueue()
        }
    }
}
