//FolderSyncViewModel

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
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class FolderSyncViewModel(
    application: Application,
    private val connectionManager: ConnectionManager
) : AndroidViewModel(application) {
    var localFolderUri by mutableStateOf<Uri?>(null)
        private set

    var remoteFiles by mutableStateOf<List<String>>(emptyList())
        private set

    var clientSharedFiles by mutableStateOf<List<String>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isRefreshingFileLists by mutableStateOf(false)
        private set

    var localFileCount by mutableStateOf(0)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var isConnected by mutableStateOf(false)
        private set

    var isGroupOwner by mutableStateOf(false)
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
                    val treeUri = treeUriString.toUri()
                    val rootUri = rootUriString.toUri()

                    // Check if we still have permissions for both URIs
                    val hasTreePermission = context.contentResolver.persistedUriPermissions.any {
                        it.uri == treeUri && it.isReadPermission && it.isWritePermission
                    }

                    val hasRootPermission = context.contentResolver.persistedUriPermissions.any {
                        it.uri == rootUri && it.isReadPermission && it.isWritePermission
                    }

                    if (hasTreePermission && hasRootPermission) {
                        Log.d(TAG, "Restoring saved folder with valid permissions")
                        setLocalFolderInternal(rootUri, false) // Don't start server during init
                    } else {
                        Log.d(TAG, "Lost permissions for saved folder, creating temporary folder")
                        val tempUri = createTempFolder(context)
                        setLocalFolderInternal(tempUri, false) // Don't start server during init
                    }
                } else {
                    Log.d(TAG, "No saved folder found, creating temporary folder")
                    val tempUri = createTempFolder(context)
                    setLocalFolderInternal(tempUri, false) // Don't start server during init
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore folder: ${e.message}")
                // Create a temporary folder as fallback
                val tempUri = createTempFolder(context)
                setLocalFolderInternal(tempUri, false) // Don't start server during init
            }
        }
    }

    private fun startConnectionMonitoring() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = viewModelScope.launch {
            // Wait a bit before starting monitoring to allow initial connection setup
            delay(5000)

            while (true) {
                try {
                    if (isConnected) {
                        Log.d(TAG, "Checking connection status...")
                        // Use a timeout for the connection check
                        val connectionCheckResult = try {
                            withTimeout(5000) {
                                if (isGroupOwner) {
                                    // For Group Owner, check if client is still connected
                                    fileTransferServer?.hasConnectedClient() ?: false
                                } else {
                                    // For Client, try to get file list from server
                                    val files = fileTransferClient?.requestFileList()
                                    files != null
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.w(TAG, "Connection check timed out, but connection may still be alive")
                            true // Assume connection is still alive if timeout
                        }

                        if (!connectionCheckResult) {
                            Log.w(TAG, "Connection lost, updating status")
                            isConnected = false
                            errorMessage = "Connection lost"
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection monitor error: ${e.message}")
                    isConnected = false
                    errorMessage = "Connection lost: ${e.message}"
                    break
                }
                delay(15000) // Check every 15 seconds instead of 10
            }
        }
    }

    private fun createTempFolder(context: Context): Uri {
        val tempDir = context.getExternalFilesDir(null)
        val tempFolder = File(tempDir, "temp_shared_folder").apply {
            mkdirs()
        }
        return Uri.fromFile(tempFolder)
    }

    fun setLocalFolder(uri: Uri) {
        setLocalFolderInternal(uri, true) // Start server if appropriate
    }

    private fun setLocalFolderInternal(uri: Uri, shouldStartServer: Boolean) {
        Log.d(TAG, "Setting local folder: $uri")
        localFolderUri = uri

        // Stop any existing server first
        fileTransferServer?.stopServer()
        isServerRunning = false

        // Create new shared folder
        sharedFolder = SharedFolder(context, uri)
        Log.d(TAG, "Created SharedFolder with URI: $uri")

        // Update local file count
        updateLocalFileCount()

        // Only start server if this device is a Group Owner and should start server
        if (isGroupOwner && shouldStartServer) {
            Log.d(TAG, "Starting server as Group Owner")
            startServer(sharedFolder!!)
            isConnected = true
        } else if (!isGroupOwner) {
            Log.d(TAG, "Client device - not starting server, only setting local folder")
        } else {
            Log.d(TAG, "Group Owner device - server will be started when connection is established")
        }

        // Exchange file lists after folder is set (only if connected)
        if (isConnected) {
            Log.d(TAG, "Folder changed while connected, exchanging updated file lists")
            exchangeFileLists()
        }
    }

    private fun isClientReady(): Boolean {
        return fileTransferClient?.isConnected() == true && isConnected
    }

    private fun exchangeFileLists() {
        viewModelScope.launch {
            try {
                isRefreshingFileLists = true

                // Wait a bit for connection to be established
                delay(2000)

                if (isGroupOwner) {
                    // Group Owner: Use server's requestClientFileList method
                    Log.d(TAG, "Group Owner exchanging file lists with client")

                    // Get fresh local files from current folder
                    val localFiles = sharedFolder?.listFiles()?.map { it.name } ?: emptyList()
                    Log.d(TAG, "Group Owner has ${localFiles.size} files in current folder: $localFiles")

                    // Use server's method to request client's file list
                    fileTransferServer?.requestClientFileList { clientFiles ->
                        Log.d(TAG, "Received client file list: $clientFiles")
                        clientSharedFiles = clientFiles
                    }

                } else {
                    // Client: Use client's requestFileList method
                    Log.d(TAG, "Client exchanging file lists with Group Owner")

                    // Get fresh local files from current folder
                    val localFiles = sharedFolder?.listFiles()?.map { it.name } ?: emptyList()
                    Log.d(TAG, "Client has ${localFiles.size} files in current folder: $localFiles")

                    // Use client's method to get server's file list
                    fileTransferClient?.let { client ->
                        try {
                            Log.d(TAG, "Requesting file list from server...")
                            val serverFiles = client.requestFileList()
                            Log.d(TAG, "Received server file list: $serverFiles")
                            remoteFiles = serverFiles
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to get server file list: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging file lists: ${e.message}")
            } finally {
                isRefreshingFileLists = false
            }
        }
    }

    private fun startServerIfGroupOwner() {
        if (isGroupOwner && sharedFolder != null && !isServerRunning) {
            Log.d(TAG, "Starting server for Group Owner role")
            startServer(sharedFolder!!)
        }
    }

    private fun startServer(folder: SharedFolder, retryCount: Int = 0) {
        if (retryCount >= MAX_SERVER_RETRIES) {
            Log.e(TAG, "Failed to start server after $MAX_SERVER_RETRIES attempts")
            errorMessage = "Failed to start file sharing server"
            return
        }

        try {
//            fileTransferServer?.stopServer()

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

    fun setRemotePeer(deviceAddress: String, isGroupOwner: Boolean) {
        this.isGroupOwner = isGroupOwner
        Log.d(TAG, "Setting remote peer: $deviceAddress, isGroupOwner: $isGroupOwner")

        // Stop any existing connections
        fileTransferClient?.disconnect()
//        fileTransferServer?.stopServer()
        isServerRunning = false
        isConnected = false

        if (isGroupOwner) {
            // This device is the Group Owner - start server
            Log.d(TAG, "Starting server as Group Owner")
            sharedFolder?.let { startServer(it) }
        } else {
            // This device is the client - connect to Group Owner
            Log.d(TAG, "Connecting to Group Owner as client")
            startClient(deviceAddress)
        }

        // Start connection monitoring
        startConnectionMonitoring()

        // Exchange file lists after connection is established
        viewModelScope.launch {
            delay(3000) // Wait for connection to be established
            exchangeFileLists()
        }
    }

    private fun startClient(deviceAddress: String) {
        viewModelScope.launch {
            try {
                // Validate IP address format
                if (!deviceAddress.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                    throw IllegalArgumentException("Invalid IP address format")
                }

                // Ensure we have a shared folder
                if (sharedFolder == null) {
                    val tempUri = createTempFolder(context)
                    setLocalFolder(tempUri)
                }

                fileTransferClient = FileTransferClient(
                    context = context,
                    sharedFolder = sharedFolder,
                    targetIp = deviceAddress
                )

                // Connect to the group owner's server
                Log.d(TAG, "Establishing connection to Group Owner's server...")
                var connected = false
                var retryCount = 0
                val maxRetries = 3

                while (!connected && retryCount < maxRetries) {
                    try {
                        connected = fileTransferClient?.connect() ?: false
                        if (!connected) {
                            retryCount++
                            if (retryCount < maxRetries) {
                                Log.d(TAG, "Connection attempt $retryCount failed, retrying in 1 second...")
                                delay(1000)
                            }
                        }
                    } catch (e: Exception) {
                        retryCount++
                        Log.e(TAG, "Connection attempt $retryCount failed: ${e.message}")
                        if (retryCount < maxRetries) {
                            delay(1000)
                        }
                    }
                }

                if (!connected) {
                    throw IllegalStateException("Failed to establish connection to Group Owner after $maxRetries attempts")
                }

                // Test connection by requesting file list
                Log.d(TAG, "Testing connection to Group Owner's server...")
                val serverFiles = fileTransferClient?.requestFileList()
                if (serverFiles != null) {
                    remoteFiles = serverFiles
                    isConnected = true
                    errorMessage = null
                    Log.d(TAG, "Successfully connected to Group Owner - ${serverFiles.size} files available")

                    // Start listening for server-initiated transfers
                    Log.d(TAG, "Starting message listener for server communications")
                    fileTransferClient?.listenForMessages(
                        onMessageReceived = { message ->
                            Log.d(TAG, "Received message from server: $message")
                        },
                        onFileReceived = { fileName, uri ->
                            Log.d(TAG, "Received file from server: $fileName")
                            handleServerInitiatedTransfer(fileName, uri)
                        }
                    )

                    // Wait a bit for message listener to be ready
                    delay(500)
                    Log.d(TAG, "Client is now ready for file list exchange")
                } else {
                    throw IllegalStateException("Failed to get file list from Group Owner")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Group Owner: ${e.message}")
                errorMessage = "Failed to connect to Group Owner: ${e.message}"
                isConnected = false
                fileTransferClient?.disconnect()
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

                if (isGroupOwner) {
                    // Group Owner: Can initiate transfers to connected client
                    Log.d(TAG, "Group Owner initiating folder sync")
                    val localFiles = localFolder.listFiles()

                    // Request client's file list
                    fileTransferServer?.requestClientFileList { clientFiles ->
                        Log.d(TAG, "Client has ${clientFiles.size} files")

                        // Find files to send to client (files in local but not in client)
                        val clientFileNames = clientFiles.toSet()
                        val filesToSend = localFiles.filter { it.name !in clientFileNames }

                        // Send missing files to client
                        filesToSend.forEach { file ->
                            try {
//                                fileTransferServer?.initiateFileTransfer(file.name)
                                Log.d(TAG, "Sent file to client: ${file.name}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send ${file.name} to client: ${e.message}")
                            }
                        }

                        Log.d(TAG, "Group Owner sync completed. Sent: ${filesToSend.size} files")
                    }
                } else {
                    // Client: Can only download from group owner
                    Log.d(TAG, "Client performing folder sync")
                    val client = fileTransferClient ?: throw IllegalStateException("No connection to Group Owner")

                    // Get local and remote files
                    val localFiles = sharedFolder?.listFiles() ?: emptyList()
                    val remoteFileList = client.requestFileList()

                    // Find files to download (files in remote but not in local)
                    val localFileNames = localFiles.map { it.name }.toSet()
                    val filesToDownload = remoteFileList.filter { it !in localFileNames }

                    // Download missing files
                    filesToDownload.forEach { fileName ->
                        try {
                            downloadFile(fileName)
                            Log.d(TAG, "Downloaded file: $fileName")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to download $fileName: ${e.message}")
                        }
                    }

                    Log.d(TAG, "Client sync completed. Downloaded: ${filesToDownload.size} files")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Folder sync failed: ${e.message}")
                errorMessage = "Failed to sync folders: ${e.message}"
            } finally {
                isLoading = false
                refreshRemoteFiles()
            }
        }
    }

    fun refreshRemoteFiles() {
        viewModelScope.launch {
            try {
                if (isConnected && !isGroupOwner) {
                    val files = fileTransferClient?.requestFileList()
                    if (files != null) {
                        remoteFiles = files
                        Log.d(TAG, "Refreshed remote files: ${files.size} files")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing remote files: ${e.message}")
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
                val treeUri = context.getSharedPreferences("folder_prefs", Context.MODE_PRIVATE)
                    .getString("tree_uri", null)?.toUri()
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

                val client = fileTransferClient
                Log.d(TAG, "Starting file upload to peer")

                // Use DocumentFile to get the file name
                val documentFile = DocumentFile.fromSingleUri(context, uri)
                    ?: throw IllegalStateException("Failed to access file")

                val success = client?.sendFile(uri)
                success?.let {
                    if (!it) {
                        errorMessage = "Failed to upload file"
                        Log.e(TAG, "File upload failed")
                    } else {
                        Log.d(TAG, "File upload completed successfully")
                    }
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
        fileTransferClient?.disconnect()
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
        fileTransferClient?.disconnect()
        isServerRunning = false
        isConnected = false
    }

    private fun createFileInSharedFolder(folderUri: Uri, fileName: String): Uri? {
        try {
            // Get the root document ID from the tree URI
            val rootId = DocumentsContract.getTreeDocumentId(folderUri)

            // Build the root document URI
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, rootId)

            // Create new file in the root
            val newFileId = DocumentsContract.createDocument(
                context.contentResolver,
                rootUri,
                "application/octet-stream",
                fileName
            )

            if (newFileId == null) {
                Log.e(TAG, "Failed to create document: $fileName")
                return null
            }

            return newFileId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file in shared folder: ${e.message}")
            return null
        }
    }

    suspend fun getLocalFileCount(): Int {
        return sharedFolder?.listFiles()?.size ?: 0
    }

    private fun updateLocalFileCount() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Updating local file count...")
                localFileCount = getLocalFileCount()
                Log.d(TAG, "Updated local file count: $localFileCount")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating local file count: ${e.message}")
                localFileCount = 0
            }
        }
    }

    fun refreshFileLists() {
        Log.d(TAG, "Manually refreshing file lists")
        exchangeFileLists()
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