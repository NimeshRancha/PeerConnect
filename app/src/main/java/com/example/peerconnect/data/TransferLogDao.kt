package com.example.peerconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferLogDao {
    @Insert
    suspend fun insert(log: TransferLog)
    @Query("SELECT * FROM transfer_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<TransferLog>>
}