package com.example.peerconnect.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.peerconnect.data.TransferLog
import com.example.peerconnect.repository.TransferLogRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransferLogViewModel(private val repository: TransferLogRepository) : ViewModel() {
    val logs = repository.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addLog(fileName: String, direction: String) {
        viewModelScope.launch {
            repository.insertLog(TransferLog(fileName = fileName, direction = direction))
        }
    }

    companion object {
        fun provideFactory(repository: TransferLogRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TransferLogViewModel(repository) as T
                }
            }
    }
}