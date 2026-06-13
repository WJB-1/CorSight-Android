package com.example.voicenavigation.di

import android.content.Context
import com.example.voicenavigation.R
import com.example.voicenavigation.stt.BaiduSpeechManager
import com.example.voicenavigation.stt.BaiduTtsManager
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

    @Provides
    @Singleton
    fun provideVoiceCommandInterpreter(): VoiceCommandInterpreter {
        return VoiceCommandInterpreter()
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
        ttsManager: BaiduTtsManager,
        llmCaller: LlmFunctionCaller
    ): VoiceInteractionManager {
        return VoiceInteractionManager(context, speechManager, ttsManager, llmCaller)
    }
}
