package com.example.voicenavigation.data.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * TTS 音频本地缓存。
 *
 * 将文本→音频文件的映射持久化到磁盘。
 * 相同文本的音频只需合成一次，后续直接播放缓存文件（<50ms）。
 *
 * 目录结构：
 * ```
 * filesDir/tts_cache/
 *   ├── a1b2c3d4.mp3   "语音助手"
 *   ├── e5f6a7b8.mp3   "避障"
 *   └── ...
 * ```
 */
class TtsAudioCache(private val context: Context) {

    companion object {
        private const val TAG = "TtsAudioCache"
        private const val CACHE_DIR = "tts_cache"
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    }

    /**
     * 检查缓存中是否有该文本的音频。
     */
    fun has(text: String): Boolean {
        return getCacheFile(text).exists()
    }

    /**
     * 获取缓存文件路径，不存在则返回 null。
     */
    fun get(text: String): File? {
        val file = getCacheFile(text)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * 将音频数据写入缓存。
     */
    fun put(text: String, audioData: ByteArray) {
        try {
            val file = getCacheFile(text)
            file.writeBytes(audioData)
            Log.d(TAG, "Cached ${audioData.size} bytes for [${text.take(20)}]")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache audio for [${text.take(20)}]", e)
        }
    }

    /**
     * 获取所有已缓存的文本列表。
     */
    fun cachedTexts(): List<String> {
        return cacheDir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
    }

    /**
     * 缓存文件数量。
     */
    fun size(): Int = cacheDir.listFiles()?.size ?: 0

    /**
     * 清空缓存。
     */
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cache cleared")
    }

    /**
     * 获取缓存文件。文件名是文本的 MD5 哈希（避免文件名特殊字符问题）。
     */
    private fun getCacheFile(text: String): File {
        val hash = md5(text)
        return File(cacheDir, "$hash.mp3")
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
