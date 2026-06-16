package com.example.voicenavigation.di

import android.content.Context
import com.example.voicenavigation.R
import com.example.voicenavigation.stt.BaiduSpeechManager
import com.example.voicenavigation.stt.BaiduTtsManager
import com.example.voicenavigation.stt.UnifiedTtsManager
import com.example.voicenavigation.voice.LlmFunctionCaller
import com.example.voicenavigation.voice.VoiceCommandInterpreter
import com.example.voicenavigation.voice.VoiceInteractionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {

    @Provides
    @Singleton
    fun provideBaiduSpeechManager(@ApplicationContext context: Context): BaiduSpeechManager {
        return BaiduSpeechManager(context)
    }

    @Provides
    @Singleton
    fun provideBaiduTtsManager(@ApplicationContext context: Context): BaiduTtsManager {
        return BaiduTtsManager(
            context,
            context.getString(R.string.baidu_speech_api_key),
            context.getString(R.string.baidu_speech_secret_key)
        )
    }

    /**
     * 统一 TTS：系统离线 TTS 优先（低延迟），百度在线 TTS 备选（高音质）。
     */
    @Provides
    @Singleton
    fun provideUnifiedTtsManager(
        @ApplicationContext context: Context,
        baiduTtsManager: BaiduTtsManager
    ): UnifiedTtsManager {
        return UnifiedTtsManager(context, baiduTtsManager)
    }

    @Provides
    @Singleton
    fun provideVoiceCommandInterpreter(@ApplicationContext context: Context): VoiceCommandInterpreter {
        return VoiceCommandInterpreter(context)
    }

    @Provides
    @Singleton
    fun provideLlmFunctionCaller(@ApplicationContext context: Context): LlmFunctionCaller {
        return LlmFunctionCaller(context)
    }

    @Provides
    @Singleton
    fun provideVoiceInteractionManager(
        @ApplicationContext context: Context,
        speechManager: BaiduSpeechManager,
        unifiedTtsManager: UnifiedTtsManager,
        llmCaller: LlmFunctionCaller
    ): VoiceInteractionManager {
        return VoiceInteractionManager(context, speechManager, unifiedTtsManager, llmCaller)
    }
}
