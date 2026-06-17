package com.example.voicenavigation.domain.usecase

import com.example.voicenavigation.data.tts.TtsPlayer
import com.example.voicenavigation.data.tts.TtsPreloader
import com.example.voicenavigation.voice.VoiceInteractionManager
import com.example.voicenavigation.voice.VoiceInteractionManager.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音交互用例。封装语音助手、TTS 播报、预合成等操作。
 * ViewModel 通过此接口交互，不直接依赖 VoiceInteractionManager/TtsPlayer/TtsPreloader。
 */
@Singleton
class VoiceUseCase @Inject constructor(
    private val voiceInteractionManager: VoiceInteractionManager,
    private val ttsPlayer: TtsPlayer,
    private val ttsPreloader: TtsPreloader
) {

    // ── 语音识别 ──

    fun startListening(mode: Mode) = voiceInteractionManager.startListening(mode)

    fun stopListening(cancel: Boolean = false) = voiceInteractionManager.stopListening(cancel)

    fun setCommandExecutor(executor: VoiceInteractionManager.CommandExecutor) {
        voiceInteractionManager.setCommandExecutor(executor)
    }

    fun setTextInputListener(listener: VoiceInteractionManager.TextInputListener) {
        voiceInteractionManager.setTextInputListener(listener)
    }

    fun setVoiceEventListener(listener: VoiceInteractionManager.VoiceEventListener) {
        voiceInteractionManager.setVoiceEventListener(listener)
    }

    fun releaseCallbacks() = voiceInteractionManager.release()

    // ── TTS 播报 ──

    fun speak(text: String) = ttsPlayer.speak(text)

    fun flushQueue() = ttsPlayer.flushQueue()

    fun stopPlayback() = ttsPlayer.stopPlayback()

    fun isSpeaking(): Boolean = ttsPlayer.isSpeaking()

    // ── TTS 预合成 ──

    fun preloadTts(scope: CoroutineScope, apiKey: String, secretKey: String) {
        scope.launch(Dispatchers.IO) {
            ttsPreloader.preload(apiKey, secretKey)
        }
    }

    fun destroy() {
        ttsPlayer.destroy()
        voiceInteractionManager.release()
    }
}
