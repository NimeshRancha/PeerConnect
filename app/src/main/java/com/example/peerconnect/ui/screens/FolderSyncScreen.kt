package com.example.peerconnect.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun FolderSyncScreen() {
    val context = LocalContext.current
    var folderUri by remember { mutableStateOf<Uri?>(null) }

    // Launcher to open document tree
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                folderUri = it

                // Persist permission
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = {
            folderPickerLauncher.launch(null)
        }) {
            Text("Select Folder to Sync")
        }

        folderUri?.let {
            Text(text = "Selected Folder:\n${it.path}", modifier = Modifier.padding(top = 8.dp))
        }
    }
}
