package com.example.peerconnect.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import android.provider.DocumentsContract
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSyncScreen(
    connectionInfo: WifiP2pInfo? = null,
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel,
    viewModel: FolderSyncViewModel = viewModel(
        factory = FolderSyncViewModel.provideFactory(
            LocalContext.current,
            manager,
            channel
        )
    )
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var wasConnected by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showPermissionError by remember { mutableStateOf(false) }

    // Effect to handle connection info changes
    LaunchedEffect(connectionInfo) {
        connectionInfo?.let { info ->
            if (info.groupFormed) {
                viewModel.setRemotePeer(info.groupOwnerAddress.hostAddress ?: return@let)
            }
        }
    }

    // Effect to show disconnection message
    LaunchedEffect(viewModel.isConnected) {
        if (wasConnected && !viewModel.isConnected) {
            snackbarHostState.showSnackbar(
                message = "Disconnected from peer",
                duration = SnackbarDuration.Long,
                withDismissAction = true
            )
        }
        wasConnected = viewModel.isConnected
    }

    // Effect to show permission error
    LaunchedEffect(showPermissionError) {
        if (showPermissionError) {
            snackbarHostState.showSnackbar("Failed to get folder access. Please try again.")
            showPermissionError = false
        }
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect") },
            text = { Text("Are you sure you want to disconnect from this device?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectDialog = false
                        viewModel.disconnect()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                Button(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    // Take persistable URI permission for the tree URI only
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                                  Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    
                    // Take permission for the tree URI
                    context.contentResolver.takePersistableUriPermission(it, takeFlags)
                    
                    // Get the root document ID and build the proper URI
                    val rootId = DocumentsContract.getTreeDocumentId(it)
                    val rootUri = DocumentsContract.buildDocumentUriUsingTree(it, rootId)
                    
                    // Save both URIs to SharedPreferences
                    context.getSharedPreferences("folder_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .apply {
                            putString("tree_uri", it.toString())
                            putString("root_uri", rootUri.toString())
                            apply()
                        }
                    
                    // Set the folder in the ViewModel
                    viewModel.setLocalFolder(rootUri)
                    
                    Log.d("FolderSyncScreen", "Successfully persisted URI permissions for: $it")
                } catch (e: SecurityException) {
                    Log.e("FolderSyncScreen", "Failed to persist URI permissions: ${e.message}")
                    showPermissionError = true
                }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folder Sync") },
                actions = {
                    if (viewModel.isConnected) {
                        IconButton(
                            onClick = { showDisconnectDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Disconnect",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Status Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connection Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (viewModel.isConnected) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.error,
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = if (viewModel.isConnected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (viewModel.isConnected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

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
                            onClick = { 
                                folderPickerLauncher.launch(null) 
                            },
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
