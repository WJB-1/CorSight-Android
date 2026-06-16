package com.example.voicenavigation.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.example.voicenavigation.data.AppDatabase
import com.example.voicenavigation.data.VoiceRecordDao
import com.example.voicenavigation.config.AppConstants
import com.example.voicenavigation.data.tts.TtsAudioCache
import com.example.voicenavigation.data.tts.TtsPlayer
import com.example.voicenavigation.data.tts.TtsPreloader
import com.example.voicenavigation.network.TripPreviewService
import com.example.voicenavigation.stt.BaiduTtsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("corsight_config", Context.MODE_PRIVATE)
    }

    @Provides
    @BaseUrl
    fun provideBaseUrl(prefs: SharedPreferences): String {
        val stored = prefs.getString(
            com.example.voicenavigation.AppConfig.KEY_PREVIEW_SERVER_BASE_URL,
            null
        )
        return stored?.takeIf { it.isNotBlank() } ?: AppConstants.PREVIEW_DEFAULT_BASE_URL
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "voice_navigation.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideVoiceRecordDao(database: AppDatabase): VoiceRecordDao {
        return database.voiceRecordDao()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideTripPreviewService(): TripPreviewService {
        return TripPreviewService()
    }

    // ── TTS 音频数据层 ──

    @Provides
    @Singleton
    fun provideTtsAudioCache(@ApplicationContext context: Context): TtsAudioCache {
        return TtsAudioCache(context)
    }

    @Provides
    @Singleton
    fun provideTtsPreloader(
        @ApplicationContext context: Context,
        cache: TtsAudioCache
    ): TtsPreloader {
        return TtsPreloader(context, cache)
    }

    @Provides
    @Singleton
    fun provideTtsPlayer(
        cache: TtsAudioCache,
        baiduTts: BaiduTtsManager
    ): TtsPlayer {
        return TtsPlayer(cache, baiduTts)
    }
}
