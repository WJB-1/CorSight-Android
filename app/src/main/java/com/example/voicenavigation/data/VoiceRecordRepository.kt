package com.example.voicenavigation.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRecordRepository @Inject constructor(
    private val dao: VoiceRecordDao
) {
    fun getAllRecords(): Flow<List<VoiceRecord>> = dao.getAllRecordsFlow()

    fun getRecordCount(): Flow<Int> = dao.getCountFlow()

    suspend fun insert(record: VoiceRecord) = withContext(Dispatchers.IO) {
        dao.insert(record)
    }

    suspend fun deleteById(id: Int) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }

    // 兼容旧代码的同步调用
    fun getAllRecordsSync(): List<VoiceRecord> = dao.getAllRecords()

    fun getCountSync(): Int = dao.getCount()
}
