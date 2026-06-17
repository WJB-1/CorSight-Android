package com.example.voicenavigation.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.PoiItem
import com.example.voicenavigation.command.CommandEvent
import com.example.voicenavigation.command.CommandRouter
import com.example.voicenavigation.AppConfig
import com.example.voicenavigation.data.VoiceRecord
import com.example.voicenavigation.data.VoiceRecordRepository
import com.example.voicenavigation.domain.usecase.NavigationUseCase
import com.example.voicenavigation.domain.usecase.TripPreviewUseCase
import com.example.voicenavigation.domain.usecase.VoiceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Destination(val latLng: LatLng, val name: String?)

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
    data class ShowTripPreview(val responseJson: String) : UiEffect()
    object StopObstacle : UiEffect()
    data class PlanRoute(val origin: LatLng, val dest: LatLng, val destName: String?) : UiEffect()
    object LocateMe : UiEffect()
    object ClearRoute : UiEffect()
}

/**
 * 核心 ViewModel —— 通过 UseCase 层与 domain 交互，不直接依赖任何具体实现。
 *
 * 依赖关系：
 * ViewModel → UseCase → Domain/Service → Data
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val voiceRecordRepository: VoiceRecordRepository,
    private val navigationUseCase: NavigationUseCase,
    private val tripPreviewUseCase: TripPreviewUseCase,
    val voiceUseCase: VoiceUseCase,
    val commandRouter: CommandRouter
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private const val FIXED_ROUTE_ID = "gzdx_stadium"
    }

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

    // ── UI 效果 ──
    private val _uiEffect = MutableSharedFlow<UiEffect>(extraBufferCapacity = 10)
    val uiEffect: SharedFlow<UiEffect> = _uiEffect.asSharedFlow()

    // ── 语音搜索状态 ──
    var autoStartNavigationAfterSearch = false
    var pendingVoiceDestination: String? = null
    var lastAddress: String? = null
    var isObstacleRunning = false
    var lastSpokenInstruction: String? = null

    // ═══════════════════════════════════════════
    // 位置
    // ═══════════════════════════════════════════

    fun updateLocation(location: LatLng, address: String? = null) {
        _currentLocation.value = location
        if (!address.isNullOrEmpty()) lastAddress = address
    }

    // ═══════════════════════════════════════════
    // 目的地
    // ═══════════════════════════════════════════

    fun selectDestination(latLng: LatLng, name: String?) {
        _selectedDestination.value = Destination(latLng, name)
    }

    fun setPoiResults(results: List<PoiItem>) {
        _poiSearchResults.value = results
    }

    // ═══════════════════════════════════════════
    // 语音播报（通过 VoiceUseCase）
    // ═══════════════════════════════════════════

    fun speak(text: String, force: Boolean = false) {
        if (text == lastSpokenInstruction && !force) return
        lastSpokenInstruction = text
        voiceUseCase.speak(text)
        _uiEffect.tryEmit(UiEffect.Speak(text, force))
    }

    fun speakForce(text: String) {
        lastSpokenInstruction = text
        voiceUseCase.speak(text)
        _uiEffect.tryEmit(UiEffect.Speak(text, force = true))
    }

    fun toast(message: String) {
        _uiEffect.tryEmit(UiEffect.ShowToast(message))
    }

    // ═══════════════════════════════════════════
    // 导航（通过 NavigationUseCase）
    // ═══════════════════════════════════════════

    fun startNavigation(locationPermissionGranted: Boolean, amapKeyValid: Boolean) {
        if (!locationPermissionGranted) {
            _uiEffect.tryEmit(UiEffect.ShowToast("需要定位权限"))
            return
        }
        if (!amapKeyValid) {
            _uiEffect.tryEmit(UiEffect.ShowToast("高德Key未配置，无法使用导航"))
            return
        }
        if (navigationUseCase.isNavigating()) {
            navigationUseCase.stopNavigation()
            _navigationState.value = NavigationState.Idle
            _uiEffect.tryEmit(UiEffect.ClearRoute)
            return
        }
        val dest = _selectedDestination.value ?: run {
            _uiEffect.tryEmit(UiEffect.ShowToast("请先选择目的地"))
            return
        }
        val loc = _currentLocation.value ?: run {
            _uiEffect.tryEmit(UiEffect.LocateMe)
            _uiEffect.tryEmit(UiEffect.ShowToast("正在获取当前位置"))
            return
        }
        saveVoiceRecord(dest.name)
        _uiEffect.tryEmit(UiEffect.PlanRoute(loc, dest.latLng, dest.name))
    }

    fun onRouteReady(distance: Float, duration: Float, instruction: String) {
        _navigationState.value = NavigationState.Navigating(distance, duration, instruction)
    }

    fun onNavigationUpdate(remaining: Float, duration: Float, instruction: String) {
        _navigationState.value = NavigationState.Navigating(remaining, duration, instruction)
    }

    fun onArrived() {
        _navigationState.value = NavigationState.Arrived
        _selectedDestination.value = null
        speakForce("您已到达目的地附近")
    }

    fun onNavigationStopped() {
        _navigationState.value = NavigationState.Idle
        _selectedDestination.value = null
        lastSpokenInstruction = null
    }

    // ═══════════════════════════════════════════
    // 行前预览（通过 TripPreviewUseCase）
    // ═══════════════════════════════════════════

    fun sendTripPreview(amapKeyValid: Boolean) {
        if (!amapKeyValid) {
            _uiEffect.tryEmit(UiEffect.ShowToast("高德Key未配置"))
            return
        }
        val previewUrl = AppConfig.normalizeBaseUrl(tripPreviewUseCase.baseUrl)
        if (previewUrl.isEmpty()) {
            _uiEffect.tryEmit(UiEffect.ShowToast("请先在设置中填写后端服务地址"))
            return
        }
        tripPreviewUseCase.baseUrl = previewUrl

        val curLoc = _currentLocation.value
        val destLoc = _selectedDestination.value?.latLng

        val callback = object : com.example.voicenavigation.network.TripPreviewService.PreviewCallback {
            override fun onSuccess(response: String) {
                _uiEffect.tryEmit(UiEffect.ShowTripPreview(response))
            }
            override fun onError(error: String) {
                _uiEffect.tryEmit(UiEffect.ShowToast("行前预览失败：$error"))
            }
        }

        if (destLoc != null && curLoc != null) {
            tripPreviewUseCase.sendPreview(
                curLoc.latitude, curLoc.longitude,
                destLoc.latitude, destLoc.longitude,
                callback
            )
        } else {
            _uiEffect.tryEmit(UiEffect.ShowToast("使用固定路线预览"))
            tripPreviewUseCase.sendFixedPreview(FIXED_ROUTE_ID, callback)
        }
    }

    // ═══════════════════════════════════════════
    // 数据持久化
    // ═══════════════════════════════════════════

    fun saveVoiceRecord(content: String?) {
        if (content.isNullOrEmpty()) return
        val dest = _selectedDestination.value?.name
        viewModelScope.launch {
            voiceRecordRepository.insert(VoiceRecord(content, "", dest ?: ""))
        }
    }

    fun deleteVoiceRecord(id: Int) {
        viewModelScope.launch {
            voiceRecordRepository.deleteById(id)
        }
    }

    // ═══════════════════════════════════════════
    // TTS 预合成（通过 VoiceUseCase）
    // ═══════════════════════════════════════════

    fun preloadTts(apiKey: String, secretKey: String) {
        voiceUseCase.preloadTts(viewModelScope, apiKey, secretKey)
    }

    // ═══════════════════════════════════════════
    // 命令路由事件处理
    // ═══════════════════════════════════════════

    fun handleCommandEvent(event: CommandEvent) {
        when (event) {
            is CommandEvent.NavigateTo -> {
                autoStartNavigationAfterSearch = true
                pendingVoiceDestination = event.destination
            }
            is CommandEvent.StopNavigation -> {
                if (navigationUseCase.isNavigating()) {
                    navigationUseCase.stopNavigation()
                    _navigationState.value = NavigationState.Idle
                    _uiEffect.tryEmit(UiEffect.ClearRoute)
                }
            }
            is CommandEvent.OpenObstacleAvoidance -> {
                _uiEffect.tryEmit(UiEffect.NavigateToVisionTest)
            }
            is CommandEvent.StopObstacleAvoidance -> {
                isObstacleRunning = false
                _uiEffect.tryEmit(UiEffect.StopObstacle)
            }
            is CommandEvent.PreviewRoute -> {
                sendTripPreview(true)
            }
            is CommandEvent.AnnounceLocation -> {
                val locDesc = lastAddress ?: _currentLocation.value?.let { "${it.latitude}, ${it.longitude}" }
                if (!locDesc.isNullOrEmpty()) speakForce("您当前位于$locDesc")
                else speakForce("正在定位中，请稍后再试")
            }
            is CommandEvent.RepeatLast -> {
                val last = lastSpokenInstruction
                if (!last.isNullOrEmpty()) speakForce(last)
                else speakForce("暂无可重复播报的内容")
            }
            is CommandEvent.AnnounceStatus -> {
                val nav = navigationUseCase.isNavigating()
                val obs = isObstacleRunning
                val status = (if (nav) "当前正在导航中" else "导航未启动") +
                        "，" + (if (obs) "避障模式已开启" else "避障模式未开启")
                speakForce(status)
            }
            is CommandEvent.SearchDestination -> {
                autoStartNavigationAfterSearch = true
                pendingVoiceDestination = event.keyword
            }
            is CommandEvent.OpenDataCollection -> {
                _uiEffect.tryEmit(UiEffect.NavigateToDataCollection)
            }
            is CommandEvent.StartVoiceAssistant -> {
                voiceUseCase.startListening(com.example.voicenavigation.voice.VoiceInteractionManager.Mode.COMMAND)
                _uiEffect.tryEmit(UiEffect.ShowToast("语音助手已就绪"))
            }
            is CommandEvent.UnknownCommand -> {
                speakForce("抱歉，没有听懂")
            }
            is CommandEvent.ShowHistory -> { /* UI handles via effect */ }
            is CommandEvent.ShowSettings -> { /* UI handles via effect */ }
            is CommandEvent.QueryResult -> { /* display */ }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tripPreviewUseCase.cancelAll()
        voiceUseCase.destroy()
    }
}
