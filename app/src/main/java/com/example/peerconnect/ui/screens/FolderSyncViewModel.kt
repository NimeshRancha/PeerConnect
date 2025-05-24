package com.example.peerconnect.ui.screens

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.p2p.WifiP2pManager
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.peerconnect.util.ConnectionManager
import com.example.peerconnect.util.FileTransferClient
import com.example.peerconnect.util.FileTransferServer
import com.example.peerconnect.util.SharedFolder
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import androidx.documentfile.provider.DocumentFile

class FolderSyncViewModel(
    application: Application,
    private val connectionManager: ConnectionManager
) : AndroidViewModel(application) {
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
    private var connectionMonitorJob: Job? = null
    private var serverStartRetryJob: Job? = null

    private val context: Context
        get() = getApplication<Application>().applicationContext

    init {
        // Try to restore the saved folder URI
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("folder_prefs", Context.MODE_PRIVATE)
                val treeUriString = prefs.getString("tree_uri", null)
                val rootUriString = prefs.getString("root_uri", null)
                
                if (treeUriString != null && rootUriString != null) {
                    val treeUri = Uri.parse(treeUriString)
                    val rootUri = Uri.parse(rootUriString)
                    
                    // Check if we still have permissions for both URIs
                    val hasTreePermission = context.contentResolver.persistedUriPermissions.any {
                        it.uri == treeUri && it.isReadPermission && it.isWritePermission
                    }
                    
                    val hasRootPermission = context.contentResolver.persistedUriPermissions.any {
                        it.uri == rootUri && it.isReadPermission && it.isWritePermission
                    }
                    
                    if (hasTreePermission && hasRootPermission) {
                        Log.d(TAG, "Restoring saved folder with valid permissions")
                        setLocalFolder(rootUri)
                    } else {
                        Log.d(TAG, "Lost permissions for saved folder, creating temporary folder")
                        val tempUri = createTempFolder(context)
                        setLocalFolder(tempUri)
                    }
                } else {
                    Log.d(TAG, "No saved folder found, creating temporary folder")
                    val tempUri = createTempFolder(context)
                    setLocalFolder(tempUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore folder: ${e.message}")
                // Create a temporary folder as fallback
                val tempUri = createTempFolder(context)
                setLocalFolder(tempUri)
            }
        }
    }

    private fun startConnectionMonitoring() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = viewModelScope.launch {
            while (true) {
                try {
                    if (isConnected) {
                        Log.d(TAG, "Checking connection status...")
                        val files = fileTransferClient?.requestFileList()
                        if (files == null) {
                            Log.e(TAG, "Connection check failed - no files received")
                            isConnected = false
                            errorMessage = "Connection lost"
                            break
                        } else {
                            Log.d(TAG, "Connection check successful - ${files.size} files available")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection monitor error: ${e.message}")
                    isConnected = false
                    errorMessage = "Connection lost: ${e.message}"
                    break
                }
                delay(5000) // Check every 5 seconds
            }
        }
    }

    private fun createTempFolder(context: Context): Uri {
        val tempDir = context.getExternalFilesDir(null)
        val tempFolder = java.io.File(tempDir, "temp_shared_folder").apply {
            mkdirs()
        }
        return Uri.fromFile(tempFolder)
    }

    fun setLocalFolder(uri: Uri) {
        localFolderUri = uri
        
        // Stop any existing server first
        fileTransferServer?.stopServer()
        isServerRunning = false
        
        // Create new shared folder and server
        sharedFolder = SharedFolder(context, uri).also { folder ->
            startServer(folder)
        }
    }

    private fun startServer(folder: SharedFolder, retryCount: Int = 0) {
        if (retryCount >= MAX_SERVER_RETRIES) {
            Log.e(TAG, "Failed to start server after $MAX_SERVER_RETRIES attempts")
            errorMessage = "Failed to start file sharing server"
            return
        }

        try {
            fileTransferServer?.stopServer()
            
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
                Log.d(TAG, "File transfer server started successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server (attempt ${retryCount + 1}): ${e.message}")
            // Retry after delay
            serverStartRetryJob?.cancel()
            serverStartRetryJob = viewModelScope.launch {
                delay(SERVER_RETRY_DELAY)
                startServer(folder, retryCount + 1)
            }
        }
    }

    fun setRemotePeer(ipAddress: String) {
        Log.d(TAG, "Connecting to peer at $ipAddress")
        
        // Ensure server is running before connecting
        if (!isServerRunning) {
            Log.d(TAG, "Starting server before connecting to peer")
            val tempUri = createTempFolder(context)
            setLocalFolder(tempUri)
        }

        // Cancel any existing client
        fileTransferClient?.cleanup()

        viewModelScope.launch {
            try {
                // Add delay to ensure server is fully started
                delay(2000)
                
                // Validate IP address format
                if (!ipAddress.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                    throw IllegalArgumentException("Invalid IP address format")
                }

                fileTransferClient = FileTransferClient(
                    context = context,
                    targetIp = ipAddress,
                    connectionTimeoutMs = 15000, // 15 seconds timeout
                    maxRetries = 5,
                    retryDelayMs = 2000 // 2 seconds between retries
                )
                
                // Test connection by requesting file list
                Log.d(TAG, "Testing connection to peer...")
                val files = fileTransferClient?.requestFileList()
                if (files != null) {
                    remoteFiles = files
                    isConnected = true
                    errorMessage = null
                    Log.d(TAG, "Successfully connected to peer - ${files.size} files available")
                    startConnectionMonitoring()
                    
                    // Start listening for server-initiated transfers
                    fileTransferClient?.startListening { fileName, uri ->
                        handleServerInitiatedTransfer(fileName, uri)
                    }
                } else {
                    throw IllegalStateException("Failed to get file list from peer")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to peer: ${e.message}")
                errorMessage = "Failed to connect to peer: ${e.message}"
                isConnected = false
                fileTransferClient?.cleanup()
                fileTransferClient = null
            }
        }
    }

    private fun handleServerInitiatedTransfer(fileName: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val folder = sharedFolder ?: throw IllegalStateException("No local folder selected")
                
                // Create a new file in the selected folder
                val docId = DocumentsContract.getTreeDocumentId(folder.folderUri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(folder.folderUri, docId)
                val newFileUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    docUri,
                    "application/octet-stream",
                    fileName
                ) ?: throw IllegalStateException("Failed to create destination file")

                // Copy the received file to the shared folder
                context.contentResolver.openInputStream(uri)?.use { input ->
                    context.contentResolver.openOutputStream(newFileUri)?.use { output ->
                        input.copyTo(output)
                    }
                }

                Log.d(TAG, "Successfully saved server-initiated transfer: $fileName")
                refreshRemoteFiles()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling server-initiated transfer: ${e.message}")
                errorMessage = "Failed to save received file: ${e.message}"
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

                // Use DocumentFile to create the new file
                val treeUri = Uri.parse(context.getSharedPreferences("folder_prefs", Context.MODE_PRIVATE)
                    .getString("tree_uri", null))
                    ?: throw IllegalStateException("No saved tree URI")

                val pickedFolder = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw IllegalStateException("Failed to access folder")

                val newFile = pickedFolder.createFile("application/octet-stream", fileName)
                    ?: throw IllegalStateException("Failed to create destination file")

                val success = client.requestFile(fileName, newFile.uri)
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
                Log.d(TAG, "Starting file upload to peer")
                
                // Use DocumentFile to get the file name
                val documentFile = DocumentFile.fromSingleUri(context, uri)
                    ?: throw IllegalStateException("Failed to access file")
                
                val success = client.sendFile(uri)
                if (!success) {
                    errorMessage = "Failed to upload file"
                    Log.e(TAG, "File upload failed")
                } else {
                    Log.d(TAG, "File upload completed successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload error: ${e.message}", e)
                errorMessage = "Failed to upload file: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun disconnect() {
        connectionMonitorJob?.cancel()
        serverStartRetryJob?.cancel()
        fileTransferServer?.stopServer()
        fileTransferClient?.cleanup()
        isServerRunning = false
        isConnected = false
        remoteFiles = emptyList()
        errorMessage = null
        // Use ConnectionManager for proper Wi-Fi Direct disconnection
        connectionManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        connectionMonitorJob?.cancel()
        serverStartRetryJob?.cancel()
        fileTransferServer?.stopServer()
        fileTransferClient?.cleanup()
        isServerRunning = false
        isConnected = false
    }

    companion object {
        private const val TAG = "FolderSyncViewModel"
        private const val MAX_SERVER_RETRIES = 3
        private const val SERVER_RETRY_DELAY = 1000L // 1 second

        fun provideFactory(
            context: Context,
            manager: WifiP2pManager,
            channel: WifiP2pManager.Channel
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = context.applicationContext as Application
                    val connectionManager = ConnectionManager(context, manager, channel)
                    return FolderSyncViewModel(app, connectionManager) as T
                }
            }
    }
} 