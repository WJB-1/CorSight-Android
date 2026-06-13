package com.example.voicenavigation.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceRecordDao {

    @Insert
    fun insert(record: VoiceRecord)

    @Query("SELECT * FROM voice_records ORDER BY timestamp DESC")
    fun getAllRecords(): List<VoiceRecord>

    @Query("SELECT * FROM voice_records ORDER BY timestamp DESC")
    fun getAllRecordsFlow(): Flow<List<VoiceRecord>>

    @Query("SELECT * FROM voice_records WHERE id = :id")
    fun getRecordById(id: Int): VoiceRecord?

    @Query("DELETE FROM voice_records WHERE id = :id")
    fun deleteById(id: Int)

    @Query("DELETE FROM voice_records")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM voice_records")
    fun getCount(): Int

    @Query("SELECT COUNT(*) FROM voice_records")
    fun getCountFlow(): Flow<Int>
}
