package com.example.voicenavigation.di

import com.example.voicenavigation.core.compass.CompassProvider
import com.example.voicenavigation.core.compass.HardwareCompassProvider
import com.example.voicenavigation.core.location.AMapLocationProvider
import com.example.voicenavigation.core.location.LocationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 将 core/ 工具层的实现注册到 Hilt 依赖图。
 * 全项目通过接口注入，实现细节不可见。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: AMapLocationProvider): LocationProvider

    @Binds
    @Singleton
    abstract fun bindCompassProvider(impl: HardwareCompassProvider): CompassProvider
}
