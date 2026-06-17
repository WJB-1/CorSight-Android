package com.example.voicenavigation.data.tts

import android.media.AudioAttributes
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
 * MediaPlayer 全局复用：类初始化时创建一个实例，播放完 reset() 而非 release()，
 * 避免每次播放的 native 资源分配开销（首次 ~200ms，后续 <5ms）。
 * 只有 destroy() 时才真正 release。
 */
class TtsPlayer(
    private val cache: TtsAudioCache,
    private val baiduTts: BaiduTtsManager
) {

    companion object {
        private const val TAG = "TtsPlayer"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** 全局复用的 MediaPlayer 实例，播放完 reset() 不 release() */
    private val mediaPlayer: MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        setOnCompletionListener { mp ->
            synchronized(lock) { mp.reset() }
            this@TtsPlayer.isPlaying = false
            if (!stopped) processQueue()
        }
        setOnErrorListener { mp, what, extra ->
            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
            synchronized(lock) { mp.reset() }
            this@TtsPlayer.isPlaying = false
            if (!stopped) processQueue()
            true
        }
    }

    private val speechQueue: Queue<String> = LinkedList()
    private var isPlaying = false
    @Volatile private var stopped = false

    fun speak(text: String?) {
        if (text.isNullOrEmpty()) return
        stopped = false
        synchronized(speechQueue) {
            speechQueue.offer(text)
            Log.d(TAG, "Speak queued: [$text] cache=${cache.has(text)} queue=${speechQueue.size}")
        }
        processQueue()
    }

    fun flushQueue() {
        synchronized(speechQueue) { speechQueue.clear() }
        stopCurrentPlayback()
        isPlaying = false
    }

    fun stopPlayback() {
        stopped = true
        synchronized(speechQueue) { speechQueue.clear() }
        stopCurrentPlayback()
        isPlaying = false
    }

    fun isSpeaking(): Boolean = isPlaying

    fun destroy() {
        stopPlayback()
        synchronized(lock) {
            try { mediaPlayer.release() } catch (_: Exception) {}
        }
    }

    // ── 内部 ──

    private fun stopCurrentPlayback() {
        synchronized(lock) {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.reset()
            } catch (_: Exception) {}
        }
    }

    private fun processQueue() {
        if (isPlaying || stopped) return
        val next: String
        synchronized(speechQueue) {
            next = speechQueue.poll() ?: return
            isPlaying = true
        }

        val cachedFile = cache.get(next)
        if (cachedFile != null) {
            Log.d(TAG, "Cache hit: playing [$next] from disk")
            playFile(cachedFile)
        } else {
            Log.d(TAG, "Cache miss: synthesizing [$next] via Baidu")
            baiduTts.speak(next)
            mainHandler.postDelayed({
                isPlaying = false
                if (!stopped) processQueue()
            }, 100)
        }
    }

    private fun playFile(file: File) {
        try {
            synchronized(lock) {
                mediaPlayer.reset()
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.prepare()
                mediaPlayer.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play cached file", e)
            synchronized(lock) {
                try { mediaPlayer.reset() } catch (_: Exception) {}
            }
            isPlaying = false
            if (!stopped) processQueue()
        }
    }
}
