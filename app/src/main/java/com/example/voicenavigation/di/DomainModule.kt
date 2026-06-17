package com.example.voicenavigation.di

import com.example.voicenavigation.domain.usecase.NavigationUseCase
import com.example.voicenavigation.domain.usecase.TripPreviewUseCase
import com.example.voicenavigation.domain.usecase.VoiceUseCase
import com.example.voicenavigation.navigation.NavigationManager
import com.example.voicenavigation.network.TripPreviewService
import com.example.voicenavigation.voice.VoiceInteractionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Domain 层 Hilt Module。提供 UseCase 实例。
 *
 * UseCase 是 ViewModel 和 domain/service 之间的桥梁：
 * - ViewModel 只依赖 UseCase 接口
 * - UseCase 内部依赖具体 service（NavigationManager、TtsPlayer 等）
 * - 解耦后 ViewModel 不直接 import 任何 domain 具体类
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    @Provides
    @Singleton
    fun provideNavigationUseCase(
        navigationManager: NavigationManager
    ): NavigationUseCase {
        return NavigationUseCase(navigationManager)
    }

    @Provides
    @Singleton
    fun provideTripPreviewUseCase(
        tripPreviewService: TripPreviewService
    ): TripPreviewUseCase {
        return TripPreviewUseCase(tripPreviewService)
    }
}
