package com.example.voicenavigation.stt

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedList
import java.util.Queue

/**
 * 百度语音合成管理器。
 *
 * 支持顺序播报队列：多次调用 [speak] 会按顺序排队，
 * 当前一条播完后再播下一条，不会互相抢占。
 */
class BaiduTtsManager(
    private val context: Context,
    private val apiKey: String,
    private val secretKey: String
) {

    companion object {
        private const val TAG = "BaiduTtsManager"
        private const val TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"
        private const val TTS_URL = "https://tsn.baidu.com/text2audio"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    var callback: TtsCallback? = null

    /** 播报队列：FIFO 顺序播放 */
    private val speechQueue: Queue<String> = LinkedList()
    /** 当前是否正在合成/播放（防止并发） */
    private var synthesizing = false
    /** 手动停止标记 */
    @Volatile
    private var stopped = false

    private var accessToken: String? = null

    /**
     * TTS callback interface. Compatible with Java callers.
     */
    interface TtsCallback {
        fun onTtsReady()
        fun onTtsError(error: String)
    }

    fun init() {
        Log.d(TAG, "Initializing BaiduTtsManager...")
        Thread {
            try {
                fetchToken()
                mainHandler.post {
                    Log.d(TAG, "BaiduTtsManager initialized, token ready")
                    callback?.onTtsReady()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init BaiduTtsManager", e)
                mainHandler.post {
                    callback?.onTtsError("TTS初始化失败: ${e.message}")
                }
            }
        }.start()
    }

    @Throws(Exception::class)
    private fun fetchToken() {
        Log.d(TAG, "Fetching Baidu TTS token...")
        val params = "grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
        val url = URL("$TOKEN_URL?$params")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val code = conn.responseCode
        Log.d(TAG, "Token response code: $code")

        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()
        conn.disconnect()

        val json = JSONObject(response.toString())
        if (json.has("access_token")) {
            accessToken = json.getString("access_token")
            Log.d(TAG, "Token fetched successfully, expires_in: ${json.optInt("expires_in")}")
        } else {
            val error = json.optString("error_description", json.toString())
            throw Exception("Token fetch failed: $error")
        }
    }

    /**
     * 加入播报队列。如果当前空闲则立即开始，否则排队等待前一条播完。
     */
    fun speak(text: String?) {
        if (text.isNullOrEmpty()) return
        stopped = false
        synchronized(speechQueue) {
            speechQueue.offer(text)
            Log.d(TAG, "Speak queued: [$text] queue size=${speechQueue.size}")
        }
        processQueue()
    }

    /**
     * 处理队列：如果空闲且队列非空，取出队首文本进行合成和播放。
     */
    private fun processQueue() {
        val next: String
        synchronized(speechQueue) {
            if (synthesizing || stopped) return
            next = speechQueue.poll() ?: return
            synthesizing = true
        }
        Log.d(TAG, "Processing: [$next], remaining=${speechQueue.size}")
        Thread { synthesizeAndPlay(next) }.start()
    }

    private fun synthesizeAndPlay(text: String) {
        try {
            // ── 本地缓存：相同文本直接播放缓存文件，零网络延迟 ──
            val cacheFile = getCacheFile(text)
            if (cacheFile.exists() && cacheFile.length() > 0) {
                Log.d(TAG, "Cache hit for [$text], playing from disk")
                playAudioFile(cacheFile)
                return
            }

            if (accessToken == null) {
                Log.e(TAG, "No access token")
                notifyError("TTS token not ready")
                onPlaybackFinished()
                return
            }

            var cuid = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
            if (cuid == null) cuid = "voice_navigation_app"

            val encodedText = URLEncoder.encode(text, "UTF-8")
            val params = "tex=$encodedText&lan=zh&cuid=$cuid" +
                    "&ctp=1&tok=$accessToken&per=0&spd=5&pit=5&vol=15"

            val url = URL(TTS_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val os = conn.outputStream
            os.write(params.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()

            val code = conn.responseCode
            val contentType = conn.contentType

            Log.d(TAG, "TTS response code: $code, contentType: $contentType")

            if (contentType != null && contentType.contains("audio")) {
                val audioData = readAllBytes(conn.inputStream)
                Log.d(TAG, "TTS audio: ${audioData.size} bytes for [$text]")
                // 保存到本地缓存，后续相同文本直接播放
                saveToCache(text, audioData)
                playAudioData(audioData)
            } else {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                Log.e(TAG, "TTS API returned text: $response")
                notifyError("语音合成失败: $response")
                onPlaybackFinished()
            }

            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "TTS synthesize failed for [$text]", e)
            notifyError("语音合成失败: ${e.message}")
            onPlaybackFinished()
        }
    }

    private fun playAudioData(audioData: ByteArray) {
        try {
            if (mediaPlayer != null) {
                try { mediaPlayer?.release() } catch (_: Exception) {}
                mediaPlayer = null
            }

            val cacheFile = File(context.cacheDir, "baidu_tts_temp.mp3")
            val fos = FileOutputStream(cacheFile)
            fos.write(audioData)
            fos.flush()
            fos.close()

            mediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(cacheFile.absolutePath)
                setOnPreparedListener { mp ->
                    Log.d(TAG, "MediaPlayer prepared, starting playback")
                    mp.start()
                }
                setOnCompletionListener { mp ->
                    Log.d(TAG, "MediaPlayer completed")
                    mp.release()
                    mediaPlayer = null
                    onPlaybackFinished()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    mp.release()
                    mediaPlayer = null
                    onPlaybackFinished()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            notifyError("播放语音失败")
            onPlaybackFinished()
        }
    }

    /**
     * 当前一条播报完成后调用：标记空闲并处理下一条。
     */
    private fun onPlaybackFinished() {
        synthesizing = false
        if (!stopped) {
            processQueue()
        }
    }

    /**
     * 停止播放并清空队列。
     */
    fun stopPlayback() {
        stopped = true
        synchronized(speechQueue) {
            speechQueue.clear()
        }
        if (mediaPlayer != null) {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaPlayer", e)
            }
            mediaPlayer = null
        }
        synthesizing = false
    }

    /**
     * 立即停止当前播报并清空队列。用于用户发起新语音命令时打断旧播报。
     */
    fun flushQueue() {
        Log.d(TAG, "Flushing TTS queue: size=${speechQueue.size} synthesizing=$synthesizing")
        synchronized(speechQueue) {
            speechQueue.clear()
        }
        if (mediaPlayer != null) {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaPlayer", e)
            }
            mediaPlayer = null
        }
        synthesizing = false
    }

    fun isSpeaking(): Boolean = synthesizing || mediaPlayer != null

    fun destroy() {
        stopPlayback()
        accessToken = null
    }

    private fun notifyError(error: String) {
        mainHandler.post {
            callback?.onTtsError(error)
        }
    }

    // ── 本地缓存 ──

    private val cacheDir: java.io.File by lazy {
        java.io.File(context.filesDir, "tts_cache").apply { mkdirs() }
    }

    private fun getCacheFile(text: String): java.io.File {
        val md = java.security.MessageDigest.getInstance("MD5")
        val hash = md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return java.io.File(cacheDir, "$hash.mp3")
    }

    private fun saveToCache(text: String, audioData: ByteArray) {
        try {
            getCacheFile(text).writeBytes(audioData)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache audio for [$text]", e)
        }
    }

    private fun playAudioFile(file: java.io.File) {
        try {
            if (mediaPlayer != null) {
                try { mediaPlayer?.release() } catch (_: Exception) {}
                mediaPlayer = null
            }
            mediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp -> mp.start() }
                setOnCompletionListener { mp ->
                    mp.release(); mediaPlayer = null; onPlaybackFinished()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release(); mediaPlayer = null; onPlaybackFinished(); true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play cached file", e)
            onPlaybackFinished()
        }
    }

    @Throws(Exception::class)
    private fun readAllBytes(inputStream: java.io.InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(4096)
        var n: Int
        while (inputStream.read(data, 0, data.size).also { n = it } != -1) {
            buffer.write(data, 0, n)
        }
        inputStream.close()
        return buffer.toByteArray()
    }
}
