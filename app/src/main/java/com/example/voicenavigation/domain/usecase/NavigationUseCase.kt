package com.example.voicenavigation.domain.usecase

import android.location.Location
import com.amap.api.maps.model.LatLng
import com.example.voicenavigation.navigation.NavigationManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导航业务用例。封装 NavigationManager 的操作，
 * ViewModel 通过此接口与导航交互，不直接依赖 NavigationManager。
 */
@Singleton
class NavigationUseCase @Inject constructor(
    private val navigationManager: NavigationManager
) {

    fun isNavigating(): Boolean = navigationManager.isNavigating()

    fun planRoute(origin: LatLng, dest: LatLng, destName: String?) {
        navigationManager.planRoute(origin, dest, destName)
    }

    fun stopNavigation() {
        navigationManager.stopNavigation()
    }

    fun setCallback(callback: NavigationManager.NavigationCallback) {
        navigationManager.setNavigationCallback(callback)
    }

    fun requestLocation() {
        navigationManager.requestCurrentLocation()
    }

    fun destroyLocationClient() {
        navigationManager.destroyLocationClient()
    }
}
