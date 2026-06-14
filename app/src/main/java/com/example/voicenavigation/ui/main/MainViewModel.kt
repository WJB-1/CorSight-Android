package com.example.voicenavigation.ui.main

import androidx.lifecycle.ViewModel
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.PoiItem
import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.CommandRouter
import com.example.voicenavigation.data.VoiceRecordRepository
import com.example.voicenavigation.navigation.NavigationManager
import com.example.voicenavigation.network.TripPreviewService
import com.example.voicenavigation.voice.VoiceInteractionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class Destination(val latLng: LatLng, val name: String)

sealed class NavigationState {
    object Idle : NavigationState()
    data class Navigating(
        val remainingDistance: Float,
        val remainingDuration: Float,
        val instruction: String
    ) : NavigationState()
    object Arrived : NavigationState()
}

sealed class UiEffect {
    data class ShowToast(val message: String) : UiEffect()
    data class Speak(val text: String, val force: Boolean = false) : UiEffect()
    object NavigateToVisionTest : UiEffect()
    object NavigateToDataCollection : UiEffect()
    object StopObstacle : UiEffect()
}

/**
 * 核心 ViewModel。持有所有共享业务状态，是 MainActivity 和各 Fragment 之间的桥梁。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    val voiceRecordRepository: VoiceRecordRepository,
    val navigationManager: NavigationManager,
    val tripPreviewService: TripPreviewService,
    val voiceInteractionManager: VoiceInteractionManager,
    val commandRouter: CommandRouter
) : ViewModel() {

    // ── 位置 ──
    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    // ── 导航状态 ──
    private val _navigationState = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    // ── 目的地 ──
    private val _selectedDestination = MutableStateFlow<Destination?>(null)
    val selectedDestination: StateFlow<Destination?> = _selectedDestination.asStateFlow()

    // ── POI 搜索 ──
    private val _poiSearchResults = MutableStateFlow<List<PoiItem>>(emptyList())
    val poiSearchResults: StateFlow<List<PoiItem>> = _poiSearchResults.asStateFlow()

    // ── UI 效果（一次性事件） ──
    private val _uiEffect = MutableSharedFlow<UiEffect>(extraBufferCapacity = 5)
    val uiEffect: SharedFlow<UiEffect> = _uiEffect.asSharedFlow()

    // ── 语音命令状态 ──
    var autoStartNavigationAfterSearch = false
    var pendingVoiceDestination: String? = null
    var lastAddress: String? = null

    fun updateLocation(location: LatLng) {
        _currentLocation.value = location
    }

    fun selectDestination(latLng: LatLng, name: String) {
        _selectedDestination.value = Destination(latLng, name)
    }

    fun setPoiResults(results: List<PoiItem>) {
        _poiSearchResults.value = results
    }

    fun speak(text: String, force: Boolean = false) {
        voiceInteractionManager.speakFeedback(text)
        _uiEffect.tryEmit(UiEffect.Speak(text, force))
    }

    fun speakForce(text: String) {
        voiceInteractionManager.speakForce(text)
        _uiEffect.tryEmit(UiEffect.Speak(text, force = true))
    }

    fun toast(message: String) {
        _uiEffect.tryEmit(UiEffect.ShowToast(message))
    }
}
