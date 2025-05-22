package com.example.peerconnect.ui.screens

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.peerconnect.util.FileTransferClient
import com.example.peerconnect.util.FileTransferServer
import com.example.peerconnect.util.SharedFolder
import kotlinx.coroutines.launch

class FolderSyncViewModel(application: Application) : AndroidViewModel(application) {
    var localFolderUri by mutableStateOf<Uri?>(null)
        private set

    var remoteFiles by mutableStateOf<List<String>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var isConnected by mutableStateOf(false)
        private set

    private var sharedFolder: SharedFolder? = null
    private var fileTransferClient: FileTransferClient? = null
    private var fileTransferServer: FileTransferServer? = null
    private var isServerRunning = false

    private val context: Context
        get() = getApplication<Application>().applicationContext

    fun setLocalFolder(uri: Uri) {
        localFolderUri = uri
        // Stop any existing server first
        fileTransferServer?.stopServer()
        isServerRunning = false
        
        // Create new shared folder and server
        sharedFolder = SharedFolder(context, uri).also { folder ->
            fileTransferServer = FileTransferServer(
                context = context,
                sharedFolder = folder,
                scope = viewModelScope
            ).apply {
                startServer { fileName ->
                    // Refresh remote files when a new file is received
                    refreshRemoteFiles()
                }
                isServerRunning = true
                Log.d("FolderSyncViewModel", "File transfer server started")
            }
        }
    }

    fun setRemotePeer(ipAddress: String) {
        Log.d("FolderSyncViewModel", "Connecting to peer at $ipAddress")
        fileTransferClient = FileTransferClient(context, ipAddress)
        viewModelScope.launch {
            try {
                // Test connection by requesting file list
                val files = fileTransferClient?.requestFileList()
                if (files != null) {
                    remoteFiles = files
                    isConnected = true
                    Log.d("FolderSyncViewModel", "Successfully connected to peer")
                } else {
                    throw IllegalStateException("Failed to get file list from peer")
                }
            } catch (e: Exception) {
                Log.e("FolderSyncViewModel", "Failed to connect to peer: ${e.message}")
                errorMessage = "Failed to connect to peer: ${e.message}"
                isConnected = false
            }
        }
    }

    fun syncFolders() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val localFolder = sharedFolder ?: throw IllegalStateException("No local folder selected")
                val client = fileTransferClient ?: throw IllegalStateException("No remote peer connected")

                // Get local and remote files
                val localFiles = localFolder.listFiles(includeNested = true)
                val remoteFileList = client.requestFileList()

                // Find files to download (files in remote but not in local)
                val localFileNames = localFiles.map { it.name }.toSet()
                val filesToDownload = remoteFileList.filter { it !in localFileNames }

                // Find files to upload (files in local but not in remote)
                val remoteFileNames = remoteFileList.toSet()
                val filesToUpload = localFiles.filter { it.name !in remoteFileNames }

                // Download missing files
                filesToDownload.forEach { fileName ->
                    try {
                        downloadFile(fileName)
                        Log.d("FolderSyncViewModel", "Downloaded file: $fileName")
                    } catch (e: Exception) {
                        Log.e("FolderSyncViewModel", "Failed to download $fileName: ${e.message}")
                    }
                }

                // Upload missing files
                filesToUpload.forEach { file ->
                    try {
                        uploadFile(file.uri)
                        Log.d("FolderSyncViewModel", "Uploaded file: ${file.name}")
                    } catch (e: Exception) {
                        Log.e("FolderSyncViewModel", "Failed to upload ${file.name}: ${e.message}")
                    }
                }

                Log.d("FolderSyncViewModel", "Folder sync completed. Downloaded: ${filesToDownload.size}, Uploaded: ${filesToUpload.size}")
            } catch (e: Exception) {
                Log.e("FolderSyncViewModel", "Folder sync failed: ${e.message}")
                errorMessage = "Failed to sync folders: ${e.message}"
            } finally {
                isLoading = false
                refreshRemoteFiles()
            }
        }
    }

    fun refreshRemoteFiles() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                fileTransferClient?.let { client ->
                    remoteFiles = client.requestFileList()
                }
            } catch (e: Exception) {
                errorMessage = "Failed to fetch remote files: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun downloadFile(fileName: String) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val folder = sharedFolder ?: throw IllegalStateException("No local folder selected")
                val client = fileTransferClient ?: throw IllegalStateException("No remote peer connected")

                // Create a new file in the selected folder
                val docId = DocumentsContract.getTreeDocumentId(folder.folderUri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(folder.folderUri, docId)
                val newFileUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    docUri,
                    "application/octet-stream",
                    fileName
                ) ?: throw IllegalStateException("Failed to create destination file")

                val success = client.requestFile(fileName, newFileUri)
                if (!success) {
                    throw IllegalStateException("Failed to download file")
                }
            } catch (e: Exception) {
                errorMessage = "Failed to download file: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun uploadFile(uri: Uri) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                if (!isServerRunning) {
                    throw IllegalStateException("Local file sharing server is not running")
                }
                if (!isConnected) {
                    throw IllegalStateException("Not connected to remote peer")
                }

                val client = fileTransferClient ?: throw IllegalStateException("No remote peer connected")
                Log.d("FolderSyncViewModel", "Starting file upload to peer")
                val success = client.sendFile(uri)
                if (!success) {
                    errorMessage = "Failed to upload file"
                    Log.e("FolderSyncViewModel", "File upload failed")
                } else {
                    Log.d("FolderSyncViewModel", "File upload completed successfully")
                }
            } catch (e: Exception) {
                Log.e("FolderSyncViewModel", "Upload error: ${e.message}", e)
                errorMessage = "Failed to upload file: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        fileTransferServer?.stopServer()
        fileTransferClient?.cleanup()
        isServerRunning = false
        isConnected = false
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application)
    }
} 