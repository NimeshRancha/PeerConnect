package com.example.peerconnect.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.p2p.WifiP2pInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun FolderSyncScreen(
    connectionInfo: WifiP2pInfo? = null,
    viewModel: FolderSyncViewModel = viewModel(
        factory = FolderSyncViewModel.provideFactory(LocalContext.current)
    )
) {
    val context = LocalContext.current

    // Effect to handle connection info changes
    LaunchedEffect(connectionInfo) {
        connectionInfo?.let { info ->
            if (info.groupFormed) {
                viewModel.setRemotePeer(info.groupOwnerAddress.hostAddress ?: return@let)
            }
        }
    }

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                // Persist permission
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setLocalFolder(it)
            }
        }
    )

    // File picker launcher for uploads
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                // Persist permission
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.uploadFile(it)
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Local folder section
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "Local Folder",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Select Folder to Sync")
                    }
                    if (viewModel.localFolderUri != null) {
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Upload File")
                        }
                    }
                }
                viewModel.localFolderUri?.let { uri ->
                    Text(
                        text = "Selected: ${uri.lastPathSegment}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Remote files section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Remote Files",
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = { viewModel.refreshRemoteFiles() }) {
                        Text("🔄")
                    }
                }

                if (viewModel.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (viewModel.errorMessage != null) {
                    Text(
                        text = viewModel.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else if (viewModel.remoteFiles.isEmpty()) {
                    Text(
                        text = "No remote files available",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(viewModel.remoteFiles) { fileName ->
                            RemoteFileItem(
                                fileName = fileName,
                                onDownload = { viewModel.downloadFile(fileName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteFileItem(
    fileName: String,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDownload) {
            Text("⬇️")
        }
    }
}
