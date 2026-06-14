package com.example.voicenavigation.ui.main.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.example.voicenavigation.AppConfig
import com.example.voicenavigation.config.AppConstants
import com.example.voicenavigation.network.TripPreviewService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences
) : ViewModel() {

    // State flows for UI
    private val _previewServerUrl = MutableStateFlow("")
    val previewServerUrl: StateFlow<String> = _previewServerUrl.asStateFlow()

    private val _detectionServerUrl = MutableStateFlow("")
    val detectionServerUrl: StateFlow<String> = _detectionServerUrl.asStateFlow()

    private val _llmBaseUrl = MutableStateFlow("")
    val llmBaseUrl: StateFlow<String> = _llmBaseUrl.asStateFlow()

    private val _llmApiKey = MutableStateFlow("")
    val llmApiKey: StateFlow<String> = _llmApiKey.asStateFlow()

    private val _llmModel = MutableStateFlow(AppConstants.LLM_DEFAULT_MODEL)
    val llmModel: StateFlow<String> = _llmModel.asStateFlow()

    private val _llmStatus = MutableStateFlow("")
    val llmStatus: StateFlow<String> = _llmStatus.asStateFlow()

    private val _useExternalDevice = MutableStateFlow(false)
    val useExternalDevice: StateFlow<Boolean> = _useExternalDevice.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        _previewServerUrl.value = AppConfig.normalizeBaseUrl(
            prefs.getString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL))
        _detectionServerUrl.value = prefs.getString(AppConfig.KEY_DETECTION_SERVER_BASE_URL, "") ?: ""
        _llmBaseUrl.value = AppConfig.normalizeBaseUrl(prefs.getString(AppConfig.KEY_LLM_BASE_URL, ""))
        _llmApiKey.value = prefs.getString(AppConfig.KEY_LLM_API_KEY, "") ?: ""
        _llmModel.value = prefs.getString(AppConfig.KEY_LLM_MODEL, AppConstants.LLM_DEFAULT_MODEL) ?: AppConstants.LLM_DEFAULT_MODEL
        _useExternalDevice.value = prefs.getBoolean(AppConstants.SP_KEY_USE_EXTERNAL_DEVICE, false)
        updateLlmStatus()
    }

    fun savePreviewServer(url: String) {
        val normalized = AppConfig.normalizeBaseUrl(url)
        if (normalized.isEmpty()) {
            _toastMessage.tryEmit("请输入地图服务地址")
            return
        }
        prefs.edit().putString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, normalized).apply()
        _previewServerUrl.value = normalized
        _toastMessage.tryEmit("地图服务地址已保存")
    }

    fun resetPreviewServer() {
        prefs.edit().putString(AppConfig.KEY_PREVIEW_SERVER_BASE_URL, TripPreviewService.DEFAULT_BASE_URL).apply()
        _previewServerUrl.value = TripPreviewService.DEFAULT_BASE_URL
        _toastMessage.tryEmit("已恢复默认地址")
    }

    fun saveDetectionServer(url: String) {
        val normalized = AppConfig.normalizeBaseUrl(url)
        if (normalized.isEmpty()) {
            _toastMessage.tryEmit("请输入检测服务地址")
            return
        }
        prefs.edit().putString(AppConfig.KEY_DETECTION_SERVER_BASE_URL, normalized).apply()
        _detectionServerUrl.value = normalized
        _toastMessage.tryEmit("检测服务地址已保存")
    }

    fun saveLlmConfig(baseUrl: String, apiKey: String, model: String) {
        val normalizedUrl = AppConfig.normalizeBaseUrl(baseUrl)
        val finalModel = model.ifEmpty { AppConstants.LLM_DEFAULT_MODEL }
        prefs.edit()
            .putString(AppConfig.KEY_LLM_BASE_URL, normalizedUrl)
            .putString(AppConfig.KEY_LLM_API_KEY, apiKey.trim())
            .putString(AppConfig.KEY_LLM_MODEL, finalModel)
            .apply()
        _llmBaseUrl.value = normalizedUrl
        _llmApiKey.value = apiKey.trim()
        _llmModel.value = finalModel
        updateLlmStatus()
        _toastMessage.tryEmit("LLM 配置已保存")
    }

    fun setUseExternalDevice(enabled: Boolean) {
        prefs.edit().putBoolean(AppConstants.SP_KEY_USE_EXTERNAL_DEVICE, enabled).apply()
        _useExternalDevice.value = enabled
        _toastMessage.tryEmit(if (enabled) "已开启外部设备优先" else "已关闭外部设备优先")
    }

    private fun updateLlmStatus() {
        val url = _llmBaseUrl.value
        val key = _llmApiKey.value
        _llmStatus.value = when {
            url.isNotEmpty() && key.isNotEmpty() -> "状态：已配置（本地不匹配时自动调用云端）"
            url.isNotEmpty() || key.isNotEmpty() -> "状态：配置不完整"
            else -> "状态：未配置（仅用本地关键词匹配）"
        }
    }
}
