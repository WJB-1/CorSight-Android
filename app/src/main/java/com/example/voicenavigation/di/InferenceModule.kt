package com.example.voicenavigation.di

import com.corsight.inference.ModelRegistry
import com.example.voicenavigation.ObstacleAlertTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {

    @Provides
    @Singleton
    fun provideModelRegistry(): ModelRegistry = ModelRegistry

    @Provides
    @Singleton
    fun provideObstacleAlertTracker(): ObstacleAlertTracker = ObstacleAlertTracker()
}
