package com.example.peerconnect.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.Socket
import kotlinx.coroutines.withTimeout

class FileTransferClient(
    private val context: Context,
    private val targetIp: String,
    private val targetPort: Int = 8988,
    private val connectionTimeoutMs: Int = 15000,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 2000
) {
    private val gson = Gson()
    private var currentSocket: Socket? = null

    private suspend fun createSocket(): Socket = withContext(Dispatchers.IO) {
        closeCurrentSocket()
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                Log.d("FileTransferClient", "Attempting to connect to $targetIp:$targetPort (attempt $attempt/$maxRetries)")
                val socket = Socket()
                socket.reuseAddress = true
                socket.keepAlive = true
                socket.connect(java.net.InetSocketAddress(targetIp, targetPort), connectionTimeoutMs)
                socket.soTimeout = 30000  // 30 seconds read timeout
                Log.d("FileTransferClient", "Successfully connected to $targetIp:$targetPort")
                currentSocket = socket
                return@withContext socket
            } catch (e: Exception) {
                lastException = e
                Log.e("FileTransferClient", "Connection attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    val delayMs = retryDelayMs * attempt
                    Log.d("FileTransferClient", "Waiting ${delayMs}ms before next attempt")
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        throw lastException ?: IllegalStateException("Failed to connect after $maxRetries attempts")
    }

    private fun closeCurrentSocket() {
        try {
            currentSocket?.close()
            currentSocket = null
        } catch (e: Exception) {
            Log.e("FileTransferClient", "Error closing socket: ${e.message}")
        }
    }

    suspend fun requestFileList(): List<String> = withContext(Dispatchers.IO) {
        try {
            val socket = createSocket()
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.println("GET_FILE_LIST")
            Log.d("FileTransferClient", "Sent GET_FILE_LIST command")

            val response = withTimeout(connectionTimeoutMs.toLong()) {
                reader.readLine()
            }
            Log.d("FileTransferClient", "Received response: $response")
            
            val type = object : TypeToken<List<String>>() {}.type
            val fileList = gson.fromJson<List<String>>(response, type)
            fileList ?: emptyList()
        } catch (e: Exception) {
            Log.e("FileTransferClient", "Error requesting file list: ${e.message}")
            throw e
        } finally {
            closeCurrentSocket()
        }
    }

    suspend fun requestFile(fileName: String, destinationUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = createSocket()
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Send GET_FILE command
            writer.println("GET_FILE:$fileName")

            // Read response status
            val status = reader.readLine()
            if (status != "OK") {
                Log.e("FileTransferClient", "Error: $status")
                return@withContext false
            }

            // Read file metadata and content
            val input = DataInputStream(socket.getInputStream())
            val receivedFileName = input.readUTF()
            val fileSize = input.readLong()

            // Open output stream to destination URI
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                while (totalRead < fileSize) {
                    val read = input.read(buffer, 0, minOf(buffer.size, (fileSize - totalRead).toInt()))
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    totalRead += read
                }
                outputStream.flush()
            }

            socket.close()
            true
        } catch (e: Exception) {
            Log.e("FileTransferClient", "Error requesting file: ${e.message}")
            false
        }
    }

    suspend fun sendFile(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = createSocket()
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            val fileDescriptor = context.contentResolver.openFileDescriptor(fileUri, "r") 
                ?: throw IOException("Failed to open file descriptor for URI: $fileUri")
            
            val fileName = getFileName(context, fileUri)
            val fileSize = fileDescriptor.statSize
            Log.d("FileTransferClient", "Preparing to send file: $fileName (size: $fileSize bytes)")

            // Send UPLOAD_FILE command first
            writer.println("UPLOAD_FILE")
            Log.d("FileTransferClient", "Sent UPLOAD_FILE command, waiting for server ready response")

            // Wait for server ready response with timeout
            val response = withTimeout(connectionTimeoutMs.toLong()) {
                reader.readLine()
            }
            Log.d("FileTransferClient", "Received server response: $response")
            if (response != "READY") {
                throw IOException("Server not ready: $response")
            }

            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)

            try {
                output.writeUTF(fileName)
                output.writeLong(fileSize)
                Log.d("FileTransferClient", "Sent file metadata")

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
                        val progress = (totalSent * 100.0 / fileSize).toInt()
                        val speed = totalSent * 1000.0 / (currentTime - startTime) / 1024 // KB/s
                        Log.d("FileTransferClient", "Upload progress: $progress% ($totalSent/$fileSize bytes), Speed: %.2f KB/s".format(speed))
                        lastProgressUpdate = currentTime
                    }
                }

                output.flush()
                Log.d("FileTransferClient", "File data sent, waiting for completion confirmation")

                // Wait for completion confirmation with timeout
                val completionStatus = withTimeout(connectionTimeoutMs.toLong()) {
                    reader.readLine()
                }
                Log.d("FileTransferClient", "Received completion status: $completionStatus")
                return@withContext completionStatus == "SUCCESS"
            } finally {
                inputStream.close()
                fileDescriptor.close()
                output.close()
                socket.close()
            }
        } catch (e: Exception) {
            Log.e("FileTransferClient", "Upload error: ${e.message}", e)
            return@withContext false
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(nameIndex)
        } ?: "file.bin"
    }

    fun cleanup() {
        closeCurrentSocket()
    }
}