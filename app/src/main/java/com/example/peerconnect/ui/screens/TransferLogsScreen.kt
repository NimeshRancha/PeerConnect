package com.example.peerconnect.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.peerconnect.data.AppDatabase
import com.example.peerconnect.data.TransferLog
import com.example.peerconnect.repository.TransferLogRepository
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TransferLogsScreen(context: Context = LocalContext.current) {
    val dao = remember { AppDatabase.getDatabase(context).transferLogDao() }
    val repository = remember { TransferLogRepository(dao) }
    val viewModel: TransferLogViewModel =
        viewModel(factory = TransferLogViewModel.provideFactory(repository))
    val logs by viewModel.logs.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Transfer Logs", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn {
            items(logs) { log ->
                TransferLogItem(log)
                Divider()
            }
        }
    }
}
@Composable
fun TransferLogItem(log: TransferLog) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val date = remember(log.timestamp) { dateFormat.format(Date(log.timestamp)) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = "${log.direction.uppercase()}: ${log.fileName}")
        Text(text = date, style = MaterialTheme.typography.bodySmall)
    }
}