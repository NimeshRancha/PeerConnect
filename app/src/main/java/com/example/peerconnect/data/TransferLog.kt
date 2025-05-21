package com.example.peerconnect.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

@Entity(tableName = "transfer_logs")
data class TransferLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val direction: String // "sent" or "received"
)