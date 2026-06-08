package com.example.voicenavigation.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.util.Date;

@Entity(tableName = "voice_records")
public class VoiceRecord {

    @PrimaryKey(autoGenerate = true)
    private int id;

    private String content;
    private String filePath;
    private long timestamp;
    private String destination;

    public VoiceRecord() {
    }

    @Ignore
    public VoiceRecord(String content, String filePath, String destination) {
        this.content = content;
        this.filePath = filePath;
        this.timestamp = new Date().getTime();
        this.destination = destination;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }
}