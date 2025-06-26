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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import androidx.documentfile.provider.DocumentFile
import java.net.URLDecoder
import java.net.URLEncoder

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
                val input = DataInputStream(BufferedInputStream(clientSocket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(clientSocket.getOutputStream()))
                val command = input.readUTF()
                when {
                    command == "GET_FILE_LIST" -> {
                        val fileList = sharedFolder.listFiles(includeNested = true).map { it.name }
                        val jsonList = gson.toJson(fileList)
                        output.writeUTF(jsonList)
                        output.flush()
                        Log.d(TAG, "Sent file list to client: $fileList")
                    }
                    command == "READY_FOR_SERVER_TRANSFERS" -> {
                        transferReadyClients.add(clientSocket)
                        output.writeUTF("OK")
                        output.flush()
                        Log.d(TAG, "Client ${clientSocket.inetAddress.hostAddress} is ready for server transfers")
                    }
                    command.startsWith("GET_FILE:") -> {
                        val encodedName = command.substringAfter("GET_FILE:")
                        val fileName = URLDecoder.decode(encodedName, "UTF-8")
                        handleFileDownload(clientSocket, fileName, input, output)
                    }
                    command == "UPLOAD_FILE" -> {
                        handleFileUpload(clientSocket, input, output, onFileReceived)
                    }
                    else -> {
                        Log.e(TAG, "Unknown command: $command")
                        output.writeUTF("ERROR: Unknown command")
                        output.flush()
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

    private suspend fun handleFileDownload(clientSocket: Socket, fileName: String, input: DataInputStream, output: DataOutputStream) {
        withContext(Dispatchers.IO) {
            try {
                val file = sharedFolder.getFile(fileName)
                if (file == null) {
                    output.writeUTF("ERROR: File not found")
                    output.flush()
                    Log.e(TAG, "File not found: $fileName")
                    return@withContext
                }
                output.writeUTF("OK")
                output.writeUTF(URLEncoder.encode(fileName, "UTF-8"))
                output.writeLong(file.size)
                output.flush()
                Log.d(TAG, "Starting file download: $fileName")
                val fileInputStream = sharedFolder.openInputStream(file.uri) ?: throw IOException("Failed to open file for reading")
                var totalSent = 0L
                try {
                    val buffer = ByteArray(8192)
                    var read: Int
                    val startTime = System.currentTimeMillis()
                    var lastProgressUpdate = startTime
                    while (fileInputStream.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalSent += read
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
                    Log.d(TAG, "Total bytes sent for $fileName: $totalSent bytes")
                } finally {
                    fileInputStream.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during file download: ${e.message}")
                throw e
            }
        }
    }

    private suspend fun handleFileUpload(clientSocket: Socket, input: DataInputStream, output: DataOutputStream, onFileReceived: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                output.writeUTF("READY")
                output.flush()
                val encodedName = input.readUTF()
                val fileName = URLDecoder.decode(encodedName, "UTF-8")
                val fileSize = input.readLong()
                Log.d(TAG, "Receiving file: $fileName (size: $fileSize bytes)")
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
                        output.writeUTF("SUCCESS")
                        output.flush()
                        Log.d(TAG, "File received successfully: $fileName")
                        Log.d(TAG, "Total bytes received for $fileName: $totalRead bytes")
                        onFileReceived(fileName)
                    } else {
                        output.writeUTF("FAILED")
                        output.flush()
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
                        val writer = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
                        writer.writeUTF("SERVER_FILE:$fileName")
                        
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

            // Check for existing files and generate a unique name with (1), (2), etc. before the extension
            val pickedFolder = DocumentFile.fromTreeUri(context, folderUri)
            var baseName = fileName
            var extension = ""
            val dotIndex = fileName.lastIndexOf('.')
            if (dotIndex != -1) {
                baseName = fileName.substring(0, dotIndex)
                extension = fileName.substring(dotIndex)
            }
            var uniqueName = fileName
            var counter = 1
            while (pickedFolder?.findFile(uniqueName) != null) {
                uniqueName = if (extension.isNotEmpty()) {
                    "${baseName}(${counter})${extension}"
                } else {
                    "${baseName}(${counter})"
                }
                counter++
            }

            // Create new file in the root
            val newFileId = DocumentsContract.createDocument(
                context.contentResolver,
                rootUri,
                "application/octet-stream",
                uniqueName
            )
            if (newFileId == null) {
                Log.e(TAG, "Failed to create document: $uniqueName")
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