package com.example.peerconnect.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class FileTransferClient(
    private val context: Context,
    private val sharedFolder: SharedFolder?,
    private val targetIp: String,
    private val targetPort: Int = 8988,
    private val connectionTimeoutMs: Int = 5000
) {
    private val TAG = "FileTransferClient"
    private val gson = Gson()
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var listenerJob: Job? = null
    private var isConnected = false

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val newSocket = Socket()

            newSocket.keepAlive = true
            newSocket.tcpNoDelay = true
//            newSocket.soTimeout = connectionTimeoutMs
            newSocket.reuseAddress = true
            newSocket.setPerformancePreferences(0, 1, 2)
            newSocket.receiveBufferSize = 65536
            newSocket.sendBufferSize = 65536

            val bestLocalAddress = findBestLocalAddress(targetIp)
            if (bestLocalAddress != null) {
                try {
                    newSocket.bind(InetSocketAddress(bestLocalAddress, 0))
                    Log.d(TAG, "Socket bound to local P2P address: ${bestLocalAddress.hostAddress}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind to P2P address: ${e.message}")
                    Log.d(TAG, "Attempting connection without explicit bind")
                }
            }

            newSocket.connect(InetSocketAddress(targetIp, targetPort), connectionTimeoutMs)
            socket = newSocket
            writer = PrintWriter(newSocket.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
            isConnected = true

            Log.d(TAG, "Connected to server $targetIp:$targetPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    fun disconnect() {
        try {
            isConnected = false
            writer?.close()
            reader?.close()
            socket?.close()
            listenerJob?.cancel()
            Log.d(TAG, "Disconnected from server")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnection error: ${e.message}")
        }
    }

    fun listenForMessages(
        onMessageReceived: (String) -> Unit,
        onFileReceived: (String, Uri) -> Unit
    ) {
        if (!isConnected) {
            Log.e(TAG, "Socket not connected. Call connect() first.")
            return
        }

        listenerJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (coroutineContext.isActive && isConnected) {
                    val line = reader?.readLine() ?: break
                    Log.d(TAG, "Message received: $line")

                    when {
                        line.startsWith("SERVER_FILE:") -> {
                            val fileName = line.substringAfter("SERVER_FILE:")
                            handleServerInitiatedTransfer(fileName, onFileReceived)
                        }

//                        0

                        else -> {
                            Log.d(TAG, "Forwarding message to handler: $line")
                            onMessageReceived(line)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listening error: ${e.message}")
            }
        }
    }


    private suspend fun handleServerInitiatedTransfer(fileName: String, onFileReceived: (String, Uri) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(BufferedInputStream(socket!!.getInputStream()))
            val actualFileName = input.readUTF()
            val fileSize = input.readLong()

            val tempFile = File.createTempFile("server_file_", "_$actualFileName")
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (total < fileSize) {
                    val read = input.read(buffer, 0, minOf(buffer.size, (fileSize - total).toInt()))
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    total += read
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
            onFileReceived(actualFileName, uri)
            Log.d(TAG, "Server-initiated transfer complete: $actualFileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error during server-initiated transfer: ${e.message}")
        }
    }

    suspend fun requestFileList(): List<String> = withContext(Dispatchers.IO) {
        if (!isConnected) throw IOException("Not connected")
        
        try {
            writer?.println("GET_FILE_LIST")
            val response = reader?.readLine()
            if (response == null) {
                Log.w(TAG, "No response from server for file list request")
                emptyList<String>()
            } else {
                Log.d(TAG, "Received response from server: $response")
                
                // Try to parse the response as JSON array
                try {
                    Log.d("Response from Server: ", response)
                    gson.fromJson(response, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse JSON response: $response, error: ${e.message}")
                    // If JSON parsing fails, try to handle as empty response
                    if (response.trim().isEmpty() || response == "[]") {
                        emptyList<String>()
                    } else {
                        throw IOException("Invalid response format from server: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting file list: ${e.message}")
            throw IOException("Failed to get file list: ${e.message}")
        }
    }

    suspend fun requestFile(fileName: String, destinationUri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) throw IOException("Not connected")
        writer?.println("GET_FILE:$fileName")
        if (reader?.readLine() != "OK") return@withContext false
        val input = DataInputStream(socket!!.getInputStream())
        val receivedFileName = input.readUTF()
        val fileSize = input.readLong()
        context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
            val buffer = ByteArray(8192)
            var totalRead = 0L
            while (totalRead < fileSize) {
                val read = input.read(buffer)
                if (read == -1) break
                outputStream.write(buffer, 0, read)
                totalRead += read
            }
            outputStream.flush()
            return@withContext totalRead == fileSize
        } ?: false
    }

    suspend fun sendFile(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) throw IOException("Not connected")
        val fileDescriptor = context.contentResolver.openFileDescriptor(fileUri, "r") ?: return@withContext false
        val fileName = getFileName(context, fileUri)
        val fileSize = fileDescriptor.statSize
        writer?.println("UPLOAD_FILE")
        if (reader?.readLine() != "READY") return@withContext false
        val output = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        output.writeUTF(fileName)
        output.writeLong(fileSize)
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
        }
        output.flush()
        val result = reader?.readLine()
        inputStream.close()
        fileDescriptor.close()
        return@withContext result == "SUCCESS"
    }

    private fun getFileName(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(nameIndex)
        } ?: "file.bin"
    }

    private fun findBestLocalAddress(targetIp: String): InetAddress? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback && intf.name.contains("p2p", ignoreCase = true)) {
                    for (addr in intf.inetAddresses.toList()) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            Log.d(TAG, "Found P2P interface ${intf.name} with address ${addr.hostAddress}")
                            return addr
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding local P2P address: ${e.message}")
            null
        }
    }

    fun isConnected(): Boolean {
        return isConnected && socket != null && !socket!!.isClosed
    }

}