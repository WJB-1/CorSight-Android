package com.example.voicenavigation.data

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "voice_records")
class VoiceRecord {

    @PrimaryKey(autoGenerate = true)
    var id: Int = 0

    var content: String? = null
    var filePath: String? = null
    var timestamp: Long = 0
    var destination: String? = null

    constructor()

    @Ignore
    constructor(content: String?, filePath: String?, destination: String?) {
        this.content = content
        this.filePath = filePath
        this.timestamp = Date().time
        this.destination = destination
    }
}
