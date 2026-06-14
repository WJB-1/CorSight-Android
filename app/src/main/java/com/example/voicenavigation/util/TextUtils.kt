package com.example.voicenavigation.util

/**
 * 文本处理工具。
 */
object TextUtils {

    /**
     * 清洗语音识别结果：去掉首尾标点符号。
     */
    fun cleanSpeechText(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text.replace(Regex("[。，、！；：,.!?;:]*$"), "").trim()
    }
}
