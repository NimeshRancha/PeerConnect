package com.example.peerconnect.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import com.google.gson.Gson

class FileTransferServer(
    private val context: Context,
    private val sharedFolder: SharedFolder,
    private val scope: CoroutineScope,
    private val port: Int = 8988
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
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
                    else -> {
                        if (command?.startsWith("UPLOAD_FILE") == true) {
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
                    clientSocket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing client socket: ${e.message}")
                }
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

                val docId = android.provider.DocumentsContract.getTreeDocumentId(sharedFolder.folderUri)
                val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(sharedFolder.folderUri, docId)
                val newFileUri = android.provider.DocumentsContract.createDocument(
                    context.contentResolver,
                    docUri,
                    "application/octet-stream",
                    fileName
                ) ?: throw IOException("Failed to create destination file")

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

    fun stopServer() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }

    private fun createFileInFolder(context: Context, folderUri: Uri, fileName: String): Uri? {
        val contentResolver = context.contentResolver
        val docId = android.provider.DocumentsContract.getTreeDocumentId(folderUri)
        val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            folderUri,
            docId
        )
        return android.provider.DocumentsContract.createDocument(
            contentResolver,
            docUri,
            "application/octet-stream",
            fileName
        )
    }
}