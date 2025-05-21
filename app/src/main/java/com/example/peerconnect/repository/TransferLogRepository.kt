package com.example.peerconnect.repository

import com.example.peerconnect.data.TransferLog
import com.example.peerconnect.data.TransferLogDao
import kotlinx.coroutines.flow.Flow

class TransferLogRepository(private val dao: TransferLogDao) {
    fun getAllLogs(): Flow<List<TransferLog>> = dao.getAllLogs()
    suspend fun insertLog(log: TransferLog) = dao.insert(log)
}