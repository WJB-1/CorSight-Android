package com.example.voicenavigation.ui.main.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenavigation.data.VoiceRecord
import com.example.voicenavigation.data.VoiceRecordRepository
import com.example.voicenavigation.stt.UnifiedTtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: VoiceRecordRepository
) : ViewModel() {

    private val _records = MutableStateFlow<List<VoiceRecord>>(emptyList())
    val records: StateFlow<List<VoiceRecord>> = _records.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _destCount = MutableStateFlow(0)
    val destCount: StateFlow<Int> = _destCount.asStateFlow()

    private val _isEmpty = MutableStateFlow(true)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            val records = repository.getAllRecordsSync()
            val totalCount = repository.getCountSync()
            val destCount = records.count { !it.destination.isNullOrEmpty() }

            _records.value = records
            _totalCount.value = totalCount
            _destCount.value = destCount
            _isEmpty.value = records.isEmpty()
        }
    }

    fun deleteRecord(record: VoiceRecord, position: Int) {
        viewModelScope.launch {
            repository.deleteById(record.id)
            loadHistory()
            _toastMessage.tryEmit("已删除")
        }
    }

    fun playRecord(record: VoiceRecord, tts: UnifiedTtsManager?) {
        tts?.speak(record.content ?: return)
    }
}
