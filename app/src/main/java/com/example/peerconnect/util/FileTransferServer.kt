package com.example.peerconnect.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var connectedClients = mutableListOf<Socket>()
    private var transferReadyClients = mutableSetOf<Socket>()
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
                            connectedClients.add(clientSocket)
                            handleClient(clientSocket, onFileReceived)
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

    private fun handleClient(clientSocket: Socket, onFileReceived: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Client connected from ${clientSocket.inetAddress.hostAddress}:${clientSocket.port}")
                
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val writer = PrintWriter(clientSocket.getOutputStream(), true)
                val command = reader.readLine()

                when (command) {
                    "GET_FILE_LIST" -> {
                        val fileList = sharedFolder.listFiles(includeNested = true).map { it.name }
                        val jsonList = gson.toJson(fileList)
                        writer.println(jsonList)
                        Log.d(TAG, "Sent file list to client: $fileList")
                    }
                    "READY_FOR_SERVER_TRANSFERS" -> {
                        transferReadyClients.add(clientSocket)
                        writer.println("OK")
                        Log.d(TAG, "Client ${clientSocket.inetAddress.hostAddress} is ready for server transfers")
                    }
                    else -> {
                        if (command?.startsWith("GET_FILE:") == true) {
                            val fileName = command.substringAfter("GET_FILE:")
                            handleFileDownload(clientSocket, fileName)
                        } else if (command?.startsWith("UPLOAD_FILE") == true) {
                            handleFileUpload(clientSocket, onFileReceived)
                        } else {
                            Log.e(TAG, "Unknown command: $command")
                            writer.println("ERROR: Unknown command")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client: ${e.message}")
            } finally {
                try {
                    connectedClients.remove(clientSocket)
                    transferReadyClients.remove(clientSocket)
                    clientSocket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing client socket: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleFileDownload(clientSocket: Socket, fileName: String) {
        withContext(Dispatchers.IO) {
            try {
                val writer = PrintWriter(clientSocket.getOutputStream(), true)
                val file = sharedFolder.getFile(fileName)
                
                if (file == null) {
                    writer.println("ERROR: File not found")
                    Log.e(TAG, "File not found: $fileName")
                    return@withContext
                }

                writer.println("OK")
                Log.d(TAG, "Starting file download: $fileName")

                val output = DataOutputStream(BufferedOutputStream(clientSocket.getOutputStream()))
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

    private suspend fun handleFileUpload(clientSocket: Socket, onFileReceived: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val input = DataInputStream(BufferedInputStream(clientSocket.getInputStream()))
                val writer = PrintWriter(clientSocket.getOutputStream(), true)
                
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

    suspend fun initiateFileTransfer(fileName: String, targetClient: Socket? = null) {
        withContext(Dispatchers.IO) {
            try {
                val file = sharedFolder.getFile(fileName)
                if (file == null) {
                    Log.e(TAG, "File not found: $fileName")
                    return@withContext
                }

                val clients = if (targetClient != null) {
                    if (transferReadyClients.contains(targetClient)) {
                        listOf(targetClient)
                    } else {
                        Log.e(TAG, "Target client is not ready for transfers")
                        emptyList()
                    }
                } else {
                    transferReadyClients.toList()
                }

                if (clients.isEmpty()) {
                    Log.e(TAG, "No clients ready for server-initiated transfers")
                    return@withContext
                }

                for (client in clients) {
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
                        transferReadyClients.remove(client)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during server-initiated file transfer: ${e.message}")
                throw e
            }
        }
    }

    fun stopServer() {
        isRunning = false
        try {
            transferReadyClients.clear()
            connectedClients.forEach { client ->
                try {
                    client.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing client socket: ${e.message}")
                }
            }
            connectedClients.clear()
            serverSocket?.close()
            serverSocket = null
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
}