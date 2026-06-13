package com.example.voicenavigation.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [VoiceRecord::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voiceRecordDao(): VoiceRecordDao
}
