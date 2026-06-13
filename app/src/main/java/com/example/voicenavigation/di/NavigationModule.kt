package com.example.voicenavigation.di

import android.content.Context
import com.example.voicenavigation.navigation.NavigationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NavigationModule {

    @Provides
    @Singleton
    fun provideNavigationManager(@ApplicationContext context: Context): NavigationManager {
        return NavigationManager(context)
    }
}
