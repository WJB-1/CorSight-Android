package com.example.voicenavigation.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.PoiItem
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
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Data classes for state ──────────────────────────────────────────────

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

// ── One-shot UI effects (Toast, TTS speak, navigation) ─────────────────

sealed class UiEffect {
    data class ShowToast(val message: String) : UiEffect()
    data class Speak(val text: String, val force: Boolean = false) : UiEffect()
    object NavigateToVisionTest : UiEffect()
    data class StartNavigation(val destination: LatLng, val name: String) : UiEffect()
    object StopNavigation : UiEffect()
}

// ── ViewModel ───────────────────────────────────────────────────────────

@HiltViewModel
class MainViewModel @Inject constructor(
    private val voiceRecordRepository: VoiceRecordRepository,
    private val navigationManager: NavigationManager,
    private val tripPreviewService: TripPreviewService,
    private val voiceInteractionManager: VoiceInteractionManager
) : ViewModel() {

    // -- Location --
    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    // -- Navigation --
    private val _navigationState = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    // -- Destination selection --
    private val _selectedDestination = MutableStateFlow<Destination?>(null)
    val selectedDestination: StateFlow<Destination?> = _selectedDestination.asStateFlow()

    // -- POI search --
    private val _poiSearchResults = MutableStateFlow<List<PoiItem>>(emptyList())
    val poiSearchResults: StateFlow<List<PoiItem>> = _poiSearchResults.asStateFlow()

    // -- One-shot UI effects --
    private val _uiEffect = MutableSharedFlow<UiEffect>()
    val uiEffect: SharedFlow<UiEffect> = _uiEffect.asSharedFlow()

    // ── Public actions ──────────────────────────────────────────────────

    fun searchDestination(keyword: String) {
        viewModelScope.launch {
            // TODO: perform POI search via AMap SDK and update _poiSearchResults
        }
    }

    fun selectDestination(latLng: LatLng, name: String) {
        _selectedDestination.value = Destination(latLng, name)
    }

    fun toggleNavigation() {
        when (_navigationState.value) {
            is NavigationState.Idle -> {
                val dest = _selectedDestination.value ?: return
                viewModelScope.launch {
                    _uiEffect.emit(
                        UiEffect.StartNavigation(dest.latLng, dest.name)
                    )
                }
            }
            is NavigationState.Navigating -> {
                viewModelScope.launch {
                    _uiEffect.emit(UiEffect.StopNavigation)
                    _navigationState.value = NavigationState.Idle
                }
            }
            is NavigationState.Arrived -> {
                _navigationState.value = NavigationState.Idle
            }
        }
    }

    fun sendTripPreview() {
        viewModelScope.launch {
            // TODO: call tripPreviewService and emit result as a Toast effect
        }
    }

    /**
     * Called from the Activity/Fragment when the device location changes.
     */
    fun updateLocation(location: LatLng) {
        _currentLocation.value = location
    }
}
