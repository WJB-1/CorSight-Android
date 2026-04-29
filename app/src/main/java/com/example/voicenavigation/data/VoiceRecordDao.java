package com.example.voicenavigation.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface VoiceRecordDao {

    @Insert
    void insert(VoiceRecord record);

    @Query("SELECT * FROM voice_records ORDER BY timestamp DESC")
    List<VoiceRecord> getAllRecords();

    @Query("SELECT * FROM voice_records WHERE id = :id")
    VoiceRecord getRecordById(int id);

    @Query("DELETE FROM voice_records WHERE id = :id")
    void deleteById(int id);

    @Query("DELETE FROM voice_records")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM voice_records")
    int getCount();
}