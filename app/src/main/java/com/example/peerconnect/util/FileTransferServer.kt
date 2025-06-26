package com.example.peerconnect.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class FileTransferServer(
    private val context: Context,
    private val sharedFolder: SharedFolder,
    private val scope: CoroutineScope,
    private val port: Int = 8988
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var connectedClient: Socket? = null
    private var isTransferReady: Boolean = false
    private val TAG = "FileTransferServer"
    private val gson = Gson()

    fun startServer(onFileReceived: (String) -> Unit) {
        if (isRunning) {
            Log.d(TAG, "Server is already running")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Try to bind to all interfaces
                serverSocket = ServerSocket(port, 50, null).apply {
                    reuseAddress = true
                    soTimeout = 0 // Infinite timeout
                }
                
                Log.d(TAG, "Starting server on port $port")
                Log.d(TAG, "Server bound to ${serverSocket?.inetAddress?.hostAddress}, local port ${serverSocket?.localPort}")
                
                isRunning = true

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null) {
                            connectedClient = clientSocket
                            scope.launch(Dispatchers.IO) {
                                handleClient(clientSocket, onFileReceived)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client connection: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server: ${e.message}")
                stopServer()
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket, onFileReceived: (String) -> Unit) {
        var reader: BufferedReader? = null
        var writer: PrintWriter? = null
        var dataInput: DataInputStream? = null
        var dataOutput: DataOutputStream? = null

        try {
            Log.d(TAG, "Client connected from ${clientSocket.inetAddress.hostAddress}:${clientSocket.port}")

            reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            writer = PrintWriter(clientSocket.getOutputStream(), true)
            dataInput = DataInputStream(BufferedInputStream(clientSocket.getInputStream()))
            dataOutput = DataOutputStream(BufferedOutputStream(clientSocket.getOutputStream()))

            // Keep connection alive and handle multiple commands
            while (isRunning && !clientSocket.isClosed) {
                try {
                    val command = reader.readLine()
                    if (command == null) {
                        Log.d(TAG, "Client disconnected")
                        break
                    }
                    
                    Log.d(TAG, "Received command from client: $command")
                    
                    when {
                        command == "GET_FILE_LIST" -> {
                            try {
                                val fileList = sharedFolder.listFiles(includeNested = true).map { it.name }
                                val jsonList = gson.toJson(fileList)
                                writer.println(jsonList)
                                Log.d(TAG, "Sent file list to client: $fileList")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting file list: ${e.message}")
                                writer.println("[]") // Send empty array on error
                            }
                        }

                        command == "READY_FOR_SERVER_TRANSFERS" -> {
                            isTransferReady = true
                            connectedClient = clientSocket
                            writer.println("OK")
                            Log.d(TAG, "Client ${clientSocket.inetAddress.hostAddress} is ready for server transfers")
                        }

                        command?.startsWith("GET_FILE:") == true -> {
                            val fileName = command.substringAfter("GET_FILE:")
                            handleFileDownload(fileName, writer, dataOutput)
                        }

                        command == "UPLOAD_FILE" -> {
                            handleFileUpload(dataInput, writer, onFileReceived)
                        }

                        else -> {
                            Log.e(TAG, "Unknown command: $command")
                            writer.println("ERROR: Unknown command")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling client command: ${e.message}")
                    break
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
        } finally {
            try {
                if (connectedClient == clientSocket) connectedClient = null
                isTransferReady = false
                clientSocket.close()
                Log.d(TAG, "Client connection closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket: ${e.message}")
            }
        }
    }


    //    Sending the files requested by the client (Client Initiated)
    private suspend fun handleFileDownload(
        fileName: String,
        writer: PrintWriter,
        output: DataOutputStream
    ) {
        withContext(Dispatchers.IO) {
            try {
                val file = sharedFolder.getFile(fileName)
                
                if (file == null) {
                    writer.println("ERROR: File not found")
                    Log.e(TAG, "File not found: $fileName")
                    return@withContext
                }

                writer.println("OK")
                Log.d(TAG, "Starting file download: $fileName")

                val inputStream = sharedFolder.openInputStream(file.uri) ?: throw IOException("Failed to open file for reading")

                try {
                    output.writeUTF(fileName)
                    output.writeLong(file.size)
                    output.flush()

                    val buffer = ByteArray(8192)
                    var read: Int
                    var totalSent = 0L
                    val startTime = System.currentTimeMillis()
                    var lastProgressUpdate = startTime

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalSent += read

                        // Log progress every second
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastProgressUpdate >= 1000) {
                            val progress = (totalSent * 100.0 / file.size).toInt()
                            val speed = totalSent * 1000.0 / (currentTime - startTime) / 1024 // KB/s
                            Log.d(TAG, "Download progress: $progress% ($totalSent/${file.size} bytes), Speed: %.2f KB/s".format(speed))
                            lastProgressUpdate = currentTime
                        }
                    }

                    output.flush()
                    Log.d(TAG, "File download completed: $fileName")
                } finally {
                    inputStream.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during file download: ${e.message}")
                throw e
            }
        }
    }

//    Receive file from the client (Client Initiated)
private suspend fun handleFileUpload(
    input: DataInputStream,
    writer: PrintWriter,
    onFileReceived: (String) -> Unit
)
{
        withContext(Dispatchers.IO) {
            try {
                writer.println("READY")
                
                val fileName = input.readUTF()
                val fileSize = input.readLong()
                Log.d(TAG, "Receiving file: $fileName (size: $fileSize bytes)")

                // Create a new file in the shared folder using the correct URI structure
                val newFileUri = createFileInFolder(context, sharedFolder.folderUri, fileName)
                    ?: throw IOException("Failed to create destination file")

                context.contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var lastProgressUpdate = System.currentTimeMillis()

                    while (totalRead < fileSize) {
                        val remaining = fileSize - totalRead
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining.toInt()))
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        totalRead += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 1000) {
                            val progress = (totalRead * 100.0 / fileSize).toInt()
                            Log.d(TAG, "Upload progress: $progress% ($totalRead/$fileSize bytes)")
                            lastProgressUpdate = now
                        }
                    }
                    outputStream.flush()
                    
                    if (totalRead == fileSize) {
                        writer.println("SUCCESS")
                        Log.d(TAG, "File received successfully: $fileName")
                        onFileReceived(fileName)
                    } else {
                        writer.println("FAILED")
                        Log.e(TAG, "File transfer incomplete: $totalRead/$fileSize bytes")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during file upload: ${e.message}")
                throw e
            }
        }
    }

    // File transfers to the client initiated by the server
    suspend fun initiateFileTransfer(fileName: String, targetClient: Socket? = null) {
        withContext(Dispatchers.IO) {
            try {
                val file = sharedFolder.getFile(fileName)
                if (file == null) {
                    Log.e(TAG, "File not found: $fileName")
                    return@withContext
                }

                val client = connectedClient
                if (!isTransferReady || client == null) {
                    Log.e(TAG, "Client is not ready or not connected")
                    return@withContext
                }

                try {
                    val writer = PrintWriter(client.getOutputStream(), true)
                    writer.println("SERVER_FILE:$fileName")
                        
                    val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
                    val inputStream = sharedFolder.openInputStream(file.uri) ?: throw IOException("Failed to open file for reading")

                    try {
                        output.writeUTF(fileName)
                        output.writeLong(file.size)
                        output.flush()

                        val buffer = ByteArray(8192)
                        var read: Int
                        var totalSent = 0L
                        val startTime = System.currentTimeMillis()
                        var lastProgressUpdate = startTime

                        while (inputStream.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalSent += read

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProgressUpdate >= 1000) {
                                val progress = (totalSent * 100.0 / file.size).toInt()
                                val speed = totalSent * 1000.0 / (currentTime - startTime) / 1024 // KB/s
                                Log.d(TAG, "Server-initiated transfer progress: $progress% ($totalSent/${file.size} bytes), Speed: %.2f KB/s".format(speed))
                                lastProgressUpdate = currentTime
                            }
                        }

                        output.flush()
                        Log.d(TAG, "Server-initiated file transfer completed: $fileName to ${client.inetAddress.hostAddress}")
                    } finally {
                        inputStream.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending file to client ${client.inetAddress.hostAddress}: ${e.message}")
                    isTransferReady = false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during server-initiated file transfer: ${e.message}")
                throw e
            }
        }
    }

    fun requestClientFileList(onFileListReceived: (List<String>) -> Unit) {
        if (connectedClient == null) {
            Log.w(TAG, "No client connected to request file list from")
            onFileListReceived(emptyList())
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val writer = PrintWriter(connectedClient!!.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(connectedClient!!.getInputStream()))

                // Send request for client file list
//                writer.println("CLIENT_FILE_LIST_REQUEST")
                Log.d(TAG, "Requested file list from client")

                // Wait for response with timeout
                var response: String? = null
                var attempts = 0
                val maxAttempts = 10
                
                while (response == null && attempts < maxAttempts) {
                    try {
                        response = reader.readLine()
                        attempts++
                        
                        if (response == null) {
                            Log.d(TAG, "No response yet, waiting... (attempt $attempts)")
                            delay(500)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading response: ${e.message}")
                        break
                    }
                }
                
                if (response?.startsWith("CLIENT_FILE_LIST_RESPONSE:") == true) {
                    val jsonList = response.substringAfter("CLIENT_FILE_LIST_RESPONSE:")
                    try {
                        val fileList: List<String> = gson.fromJson(jsonList, object : TypeToken<List<String>>() {}.type)
                        Log.d(TAG, "Received client file list: $fileList")
                        onFileListReceived(fileList)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing client file list: ${e.message}")
                        onFileListReceived(emptyList())
                    }
                } else {
                    Log.w(TAG, "Invalid or no response from client: $response")
                    onFileListReceived(emptyList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting client file list: ${e.message}")
                onFileListReceived(emptyList())
            }
        }
    }

    fun getServerInfo(): String {
        // Implementation of getServerInfo method
        // This method should return a string representation of the server's current state
        // For example, you can return the server's IP address and port
        return "Server IP: ${serverSocket?.inetAddress?.hostAddress}, Port: ${serverSocket?.localPort}"
    }

    fun stopServer() {
        isRunning = false
        try {
            connectedClient?.close()
            serverSocket?.close()
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }

    private fun createFileInFolder(context: Context, folderUri: Uri, fileName: String): Uri? {
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
            
            // Build the document URI and ensure we have permission
            val documentUri = newFileId
            try {
                context.contentResolver.takePersistableUriPermission(
                    documentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to take persistable URI permission for new file: ${e.message}")
            }
            
            return documentUri
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file in folder: ${e.message}")
            return null
        }
    }

    fun hasConnectedClient(): Boolean {
        return connectedClient != null && !connectedClient!!.isClosed
    }
}