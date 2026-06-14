package com.example.voicenavigation.di

import javax.inject.Qualifier

/** 注入后端服务的 base URL。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseUrl
