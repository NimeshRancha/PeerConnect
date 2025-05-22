package com.example.peerconnect.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import com.google.gson.Gson

class FileTransferServer(
    private val context: Context,
    private val port: Int = 8988,
    private val sharedFolder: SharedFolder,
    private val scope: CoroutineScope
) {
    private var isRunning = true
    private val gson = Gson()
    private var serverSocket: ServerSocket? = null

    fun startServer(onFileReceived: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d("FileTransferServer", "Starting server on port $port")
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress("0.0.0.0", port))
                    soTimeout = 0  // Infinite timeout for accept()
                }
                Log.d("FileTransferServer", "Server successfully bound to port $port")
                
                while (isRunning) {
                    try {
                        val client: Socket = serverSocket?.accept() ?: break
                        client.soTimeout = 30000  // 30 seconds timeout for client operations
                        Log.d("FileTransferServer", "Accepted connection from ${client.inetAddress}")
                        handleClient(client, onFileReceived)
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e("FileTransferServer", "Error handling client connection: ${e.message}", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("FileTransferServer", "Failed to start server on port $port: ${e.message}", e)
                isRunning = false
            } finally {
                closeServerSocket()
            }
        }
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
            serverSocket = null
            Log.d("FileTransferServer", "Server socket closed")
        } catch (e: IOException) {
            Log.e("FileTransferServer", "Error closing server socket: ${e.message}", e)
        }
    }

    private suspend fun handleClient(client: Socket, onFileReceived: (String) -> Unit) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)
            val commandStr = reader.readLine()

            when (val command = FileCommand.fromString(commandStr)) {
                is FileCommand.GetFileList -> {
                    val files = sharedFolder.listFiles(includeNested = true)
                    val fileList = files.map { it.name }
                    writer.println(gson.toJson(fileList))
                }
                
                is FileCommand.GetFile -> {
                    val file = sharedFolder.getFile(command.filename)
                    if (file != null) {
                        writer.println("OK")
                        sendFile(file, client.getOutputStream())
                    } else {
                        writer.println("ERROR:File not found")
                    }
                }

                is FileCommand.UploadFile -> {
                    handleFileUpload(client, onFileReceived)
                }

                null -> {
                    Log.e("FileTransferServer", "Invalid command received: $commandStr")
                    writer.println("ERROR:Invalid command")
                }
            }
        } catch (e: Exception) {
            Log.e("FileTransferServer", "Error handling client: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (e: IOException) {
                Log.e("FileTransferServer", "Error closing client socket: ${e.message}")
            }
        }
    }

    private fun sendFile(file: SharedFile, outputStream: OutputStream) {
        val input = sharedFolder.openInputStream(file.uri)
        if (input != null) {
            try {
                // Send file metadata
                val dataOutput = DataOutputStream(outputStream)
                dataOutput.writeUTF(file.name)
                dataOutput.writeLong(file.size)

                // Send file content
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    dataOutput.write(buffer, 0, bytesRead)
                }
                dataOutput.flush()
            } finally {
                input.close()
            }
        }
    }

    private fun handleFileUpload(client: Socket, onFileReceived: (String) -> Unit) {
        val writer = PrintWriter(client.getOutputStream(), true)
        try {
            Log.d("FileTransferServer", "New file upload request from ${client.inetAddress}")
            writer.println("READY")
            Log.d("FileTransferServer", "Sent READY signal to client")

            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val fileName = input.readUTF()
            val fileSize = input.readLong()

            Log.d("FileTransferServer", "Starting upload of $fileName ($fileSize bytes)")

            val outputUri = createFileInFolder(context, sharedFolder.folderUri, fileName)
            if (outputUri == null) {
                Log.e("FileTransferServer", "Failed to create output file: $fileName")
                writer.println("ERROR:Failed to create output file")
                return
            }
            Log.d("FileTransferServer", "Created output file at: $outputUri")

            var success = false
            try {
                context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    Log.d("FileTransferServer", "Opened output stream for writing")
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    while (totalRead < fileSize) {
                        val read = input.read(buffer, 0, minOf(buffer.size, (fileSize - totalRead).toInt()))
                        if (read == -1) {
                            Log.e("FileTransferServer", "Unexpected end of stream at $totalRead/$fileSize bytes")
                            break
                        }
                        outputStream.write(buffer, 0, read)
                        totalRead += read
                        if (totalRead % (fileSize / 10) < 8192) {  // Log every ~10% progress
                            Log.d("FileTransferServer", "Download progress: ${(totalRead * 100 / fileSize)}% ($totalRead/$fileSize bytes)")
                        }
                    }
                    outputStream.flush()
                    success = totalRead == fileSize
                    Log.d("FileTransferServer", "File transfer completed. Success: $success, Total bytes: $totalRead")
                }
            } catch (e: Exception) {
                Log.e("FileTransferServer", "Error during file upload: ${e.message}", e)
                // Delete partial file if upload failed
                context.contentResolver.delete(outputUri, null, null)
                writer.println("ERROR:${e.message}")
                return
            }

            if (success) {
                writer.println("SUCCESS")
                Log.d("FileTransferServer", "File upload successful: $fileName")
                onFileReceived(fileName)
            } else {
                context.contentResolver.delete(outputUri, null, null)
                writer.println("ERROR:Incomplete transfer")
                Log.e("FileTransferServer", "Incomplete file transfer for: $fileName")
            }
        } catch (e: Exception) {
            Log.e("FileTransferServer", "Upload handling error: ${e.message}", e)
            writer.println("ERROR:${e.message}")
        }
    }

    fun stopServer() {
        Log.d("FileTransferServer", "Stopping server")
        isRunning = false
        closeServerSocket()
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