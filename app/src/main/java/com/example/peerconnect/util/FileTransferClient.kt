package com.example.peerconnect.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.net.URLDecoder

class FileTransferClient(
    private val context: Context,
    private val targetIp: String,
    private val targetPort: Int = 8988,
    private val connectionTimeoutMs: Int = 5000,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000
) {
    private val gson = Gson()
    private val TAG = "FileTransferClient"

    private suspend fun createSocket(): Socket = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                Log.d(TAG, "Attempting to connect to $targetIp:$targetPort (attempt $attempt/$maxRetries)")
                val socket = Socket()
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.soTimeout = connectionTimeoutMs
                socket.reuseAddress = true
                socket.setPerformancePreferences(0, 1, 2)
                socket.receiveBufferSize = 65536
                socket.sendBufferSize = 65536
                val bestLocalAddress = findBestLocalAddress(targetIp)
                if (bestLocalAddress != null) {
                    try {
                        socket.bind(java.net.InetSocketAddress(bestLocalAddress, 0))
                        Log.d(TAG, "Socket bound to local address: ${bestLocalAddress.hostAddress}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to bind to ${bestLocalAddress.hostAddress}: ${e.message}")
                        Log.d(TAG, "Attempting connection without explicit bind")
                    }
                } else {
                    Log.w(TAG, "No suitable local address found, attempting connection without explicit bind")
                }
                socket.connect(java.net.InetSocketAddress(targetIp, targetPort), connectionTimeoutMs)
                if (!socket.isConnected || socket.isClosed) {
                    throw java.net.SocketException("Socket not connected after creation")
                }
                val connectedLocalAddress = socket.localAddress.hostAddress
                val connectedLocalPort = socket.localPort
                Log.d(TAG, "Successfully connected from $connectedLocalAddress:$connectedLocalPort to $targetIp:$targetPort")
                return@withContext socket
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "Connection attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    val delayMs = retryDelayMs * attempt
                    Log.d(TAG, "Waiting ${delayMs}ms before next attempt")
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        throw lastException ?: IllegalStateException("Failed to connect after $maxRetries attempts")
    }

    private fun findBestLocalAddress(targetIp: String): java.net.InetAddress? {
        try {
            // Get all network interfaces
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val targetAddress = java.net.InetAddress.getByName(targetIp)
            
            // First, try to find the Wi-Fi Direct interface (p2p)
            interfaces?.toList()?.forEach { networkInterface ->
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    if (networkInterface.name.contains("p2p", ignoreCase = true)) {
                        networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                            val address = interfaceAddress.address
                            if (address is java.net.Inet4Address) {
                                Log.d(TAG, "Found p2p interface ${networkInterface.name}: ${address.hostAddress}")
                                return address
                            }
                        }
                    }
                }
            }
            
            // Then try to find a matching subnet
            interfaces?.toList()?.forEach { networkInterface ->
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                        val address = interfaceAddress.address
                        if (address is java.net.Inet4Address) {
                            // Check if the target IP is in the same subnet
                            if (isInSameSubnet(targetAddress, address, interfaceAddress.networkPrefixLength)) {
                                Log.d(TAG, "Found matching subnet interface ${networkInterface.name}: ${address.hostAddress}")
                                return address
                            }
                        }
                    }
                }
            }
            
            // If still not found, try any interface in the 192.168.49.x range
            interfaces?.toList()?.forEach { networkInterface ->
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                        val address = interfaceAddress.address
                        if (address is java.net.Inet4Address) {
                            address.hostAddress?.let { hostAddress ->
                                if (hostAddress.startsWith("192.168.49.")) {
                                    Log.d(TAG, "Found 192.168.49.x interface ${networkInterface.name}: $hostAddress")
                                    return address
                                }
                            }
                        }
                    }
                }
            }

            // As a last resort, try any non-loopback IPv4 interface
            interfaces?.toList()?.forEach { networkInterface ->
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                        val address = interfaceAddress.address
                        if (address is java.net.Inet4Address) {
                            Log.d(TAG, "Found fallback interface ${networkInterface.name}: ${address.hostAddress}")
                            return address
                        }
                    }
                }
            }
            
            Log.e(TAG, "No suitable local address found. Available interfaces:")
            interfaces?.toList()?.forEach { networkInterface ->
                Log.d(TAG, "Interface ${networkInterface.name} (up=${networkInterface.isUp}, loopback=${networkInterface.isLoopback}):")
                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    Log.d(TAG, "  Address: ${interfaceAddress.address.hostAddress}")
                }
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding local address: ${e.message}")
            return null
        }
    }

    private fun isInSameSubnet(addr1: java.net.InetAddress, addr2: java.net.InetAddress, prefixLength: Short): Boolean {
        val mask = -1L shl (32 - prefixLength)
        val addr1Bits = addr1.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val addr2Bits = addr2.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        return (addr1Bits and mask) == (addr2Bits and mask)
    }

    suspend fun requestFileList(): List<String> = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = createSocket()
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            output.writeUTF("GET_FILE_LIST")
            output.flush()
            Log.d(TAG, "Sent GET_FILE_LIST command")

            val response = withTimeout(connectionTimeoutMs.toLong()) {
                input.readUTF()
            }
            Log.d(TAG, "Received response: $response")
            val type = object : TypeToken<List<String>>() {}.type
            val fileList = gson.fromJson<List<String>>(response, type)
            fileList ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting file list: ${e.message}")
            throw e
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket after file list request: ${e.message}")
            }
        }
    }

    suspend fun requestFile(fileName: String, destinationUri: Uri): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = createSocket()
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            val encodedName = URLEncoder.encode(fileName, "UTF-8")
            output.writeUTF("GET_FILE:$encodedName")
            output.flush()
            Log.d(TAG, "Sent GET_FILE command for: $fileName (encoded: $encodedName)")

            val status = input.readUTF()
            if (status != "OK") {
                Log.e(TAG, "Error response from server: $status")
                return@withContext false
            }

            val receivedFileName = URLDecoder.decode(input.readUTF(), "UTF-8")
            val fileSize = input.readLong()
            Log.d(TAG, "Receiving file: $receivedFileName (size: $fileSize bytes)")

            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
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
                        Log.d(TAG, "Download progress: $progress% ($totalRead/$fileSize bytes)")
                        lastProgressUpdate = now
                    }
                }
                outputStream.flush()
                Log.d(TAG, "File download completed: $totalRead bytes transferred")
                return@withContext totalRead == fileSize
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting file: ${e.message}")
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket after file request: ${e.message}")
            }
        }
    }

    suspend fun sendFile(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = createSocket()
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            val fileDescriptor = context.contentResolver.openFileDescriptor(fileUri, "r") 
                ?: throw IOException("Failed to open file descriptor for URI: $fileUri")
            val fileName = getFileName(context, fileUri)
            val encodedName = URLEncoder.encode(fileName, "UTF-8")
            val fileSize = fileDescriptor.statSize
            Log.d(TAG, "Preparing to send file: $fileName (size: $fileSize bytes)")

            output.writeUTF("UPLOAD_FILE")
            output.flush()
            Log.d(TAG, "Sent UPLOAD_FILE command, waiting for server ready response")

            val response = input.readUTF()
            Log.d(TAG, "Received server response: $response")
            if (response != "READY") {
                throw IOException("Server not ready: $response")
            }

            output.writeUTF(encodedName)
            output.writeLong(fileSize)
            output.flush()
            Log.d(TAG, "Sent file metadata")

            val fileInputStream = FileInputStream(fileDescriptor.fileDescriptor)
            try {
                val buffer = ByteArray(8192)
                var read: Int
                var totalSent = 0L
                val startTime = System.currentTimeMillis()
                var lastProgressUpdate = startTime

                while (fileInputStream.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    totalSent += read
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProgressUpdate >= 1000) {
                        val progress = (totalSent * 100.0 / fileSize).toInt()
                        val speed = totalSent * 1000.0 / (currentTime - startTime) / 1024 // KB/s
                        Log.d(TAG, "Upload progress: $progress% ($totalSent/$fileSize bytes), Speed: %.2f KB/s".format(speed))
                        lastProgressUpdate = currentTime
                    }
                }
                output.flush()
                Log.d(TAG, "File data sent, waiting for completion confirmation")
                val completionStatus = input.readUTF()
                Log.d(TAG, "Received completion status: $completionStatus")
                return@withContext completionStatus == "SUCCESS"
            } finally {
                fileInputStream.close()
                fileDescriptor.close()
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            return@withContext false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket after file upload: ${e.message}")
            }
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

    fun startListening(onFileReceived: (String, Uri) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = createSocket()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // Send a special command to indicate this client is ready for server-initiated transfers
                writer.println("READY_FOR_SERVER_TRANSFERS")
                Log.d(TAG, "Sent READY_FOR_SERVER_TRANSFERS command")

                // Wait for server acknowledgment
                val response = reader.readLine()
                if (response != "OK") {
                    Log.e(TAG, "Server did not acknowledge transfer readiness: $response")
                    return@launch
                }
                Log.d(TAG, "Server acknowledged transfer readiness")

                while (true) {
                    try {
                        val command = reader.readLine()
                        if (command == null) {
                            Log.d(TAG, "Connection closed by server")
                            break
                        }

                        when {
                            command.startsWith("SERVER_FILE:") -> {
                                val fileName = command.substringAfter("SERVER_FILE:")
                                handleServerInitiatedTransfer(socket, fileName, onFileReceived)
                            }
                            else -> {
                                Log.d(TAG, "Received unknown command: $command")
                            }
                        }
                    } catch (e: Exception) {
                        if (true) {
                            Log.e(TAG, "Error reading from server: ${e.message}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in server transfer listener: ${e.message}")
            }
        }
    }

    private suspend fun handleServerInitiatedTransfer(
        socket: Socket,
        fileName: String,
        onFileReceived: (String, Uri) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val receivedFileName = input.readUTF()
                val fileSize = input.readLong()
                Log.d(TAG, "Receiving server-initiated file: $receivedFileName (size: $fileSize bytes)")

                // Create a temporary file to store the received data
                val tempFile = File.createTempFile("server_transfer_", "_$receivedFileName")
                val outputStream = FileOutputStream(tempFile)

                try {
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
                            Log.d(TAG, "Server transfer progress: $progress% ($totalRead/$fileSize bytes)")
                            lastProgressUpdate = now
                        }
                    }

                    outputStream.flush()

                    if (totalRead == fileSize) {
                        // Convert the temporary file to a content URI
                        val contentUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            tempFile
                        )
                        onFileReceived(receivedFileName, contentUri)
                        Log.d(TAG, "Server-initiated file transfer completed: $receivedFileName")
                    } else {
                        Log.e(TAG, "Server transfer incomplete: $totalRead/$fileSize bytes")
                        tempFile.delete()
                    }
                } finally {
                    outputStream.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling server-initiated transfer: ${e.message}")
                throw e
            }
        }
    }

    fun stopListening() {
        // Implementation needed
    }

    fun cleanup() {
        // Implementation needed
    }
}