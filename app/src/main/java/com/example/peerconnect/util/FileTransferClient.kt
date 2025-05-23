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
import kotlinx.coroutines.withTimeout
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

class FileTransferClient(
    private val context: Context,
    private val targetIp: String,
    private val targetPort: Int = 8988,
    private val connectionTimeoutMs: Int = 5000,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000
) {
    private val gson = Gson()
    private var currentSocket: Socket? = null
    private val TAG = "FileTransferClient"

    private suspend fun createSocket(): Socket = withContext(Dispatchers.IO) {
        closeCurrentSocket()
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                Log.d(TAG, "Attempting to connect to $targetIp:$targetPort (attempt $attempt/$maxRetries)")
                
                // Create new socket for each attempt
                val socket = Socket()
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.soTimeout = connectionTimeoutMs
                socket.reuseAddress = true
                
                // Set socket options before connect
                socket.setPerformancePreferences(0, 1, 2) // Prioritize bandwidth and latency over connection time
                socket.receiveBufferSize = 65536 // 64KB receive buffer
                socket.sendBufferSize = 65536 // 64KB send buffer

                // Try to bind to the best local address
                val bestLocalAddress = findBestLocalAddress(targetIp)
                if (bestLocalAddress != null) {
                    try {
                        socket.bind(java.net.InetSocketAddress(bestLocalAddress, 0))
                        Log.d(TAG, "Socket bound to local address: ${bestLocalAddress.hostAddress}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to bind to ${bestLocalAddress.hostAddress}: ${e.message}")
                        // If binding fails, try connecting without explicit bind
                        Log.d(TAG, "Attempting connection without explicit bind")
                    }
                } else {
                    Log.w(TAG, "No suitable local address found, attempting connection without explicit bind")
                }
                
                // Connect with timeout
                socket.connect(java.net.InetSocketAddress(targetIp, targetPort), connectionTimeoutMs)
                
                // Verify connection
                if (!socket.isConnected || socket.isClosed) {
                    throw java.net.SocketException("Socket not connected after creation")
                }
                
                // Log connection details
                val connectedLocalAddress = socket.localAddress.hostAddress
                val connectedLocalPort = socket.localPort
                Log.d(TAG, "Successfully connected from $connectedLocalAddress:$connectedLocalPort to $targetIp:$targetPort")
                
                currentSocket = socket
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

    private fun closeCurrentSocket() {
        try {
            currentSocket?.let { socket ->
                if (!socket.isClosed) {
                    try {
                        socket.shutdownInput()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error shutting down input: ${e.message}")
                    }
                    try {
                        socket.shutdownOutput()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error shutting down output: ${e.message}")
                    }
                    socket.close()
                    Log.d(TAG, "Closed existing socket connection")
                }
            }
            currentSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
    }

    suspend fun requestFileList(): List<String> = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = createSocket()
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.println("GET_FILE_LIST")
            Log.d(TAG, "Sent GET_FILE_LIST command")

            val response = withTimeout(connectionTimeoutMs.toLong()) {
                reader.readLine()
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
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.println("GET_FILE:$fileName")
            Log.d(TAG, "Sent GET_FILE command for: $fileName")

            val status = withTimeout(connectionTimeoutMs.toLong()) {
                reader.readLine()
            }
            if (status != "OK") {
                Log.e(TAG, "Error response from server: $status")
                return@withContext false
            }

            val input = DataInputStream(socket.getInputStream())
            val receivedFileName = input.readUTF()
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

                    // Log progress every second
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
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            val fileDescriptor = context.contentResolver.openFileDescriptor(fileUri, "r") 
                ?: throw IOException("Failed to open file descriptor for URI: $fileUri")
            
            val fileName = getFileName(context, fileUri)
            val fileSize = fileDescriptor.statSize
            Log.d(TAG, "Preparing to send file: $fileName (size: $fileSize bytes)")

            writer.println("UPLOAD_FILE")
            Log.d(TAG, "Sent UPLOAD_FILE command, waiting for server ready response")

            val response = withTimeout(connectionTimeoutMs.toLong()) {
                reader.readLine()
            }
            Log.d(TAG, "Received server response: $response")
            if (response != "READY") {
                throw IOException("Server not ready: $response")
            }

            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)

            try {
                output.writeUTF(fileName)
                output.writeLong(fileSize)
                output.flush()
                Log.d(TAG, "Sent file metadata")

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
                        Log.d(TAG, "Upload progress: $progress% ($totalSent/$fileSize bytes), Speed: %.2f KB/s".format(speed))
                        lastProgressUpdate = currentTime
                    }
                }

                output.flush()
                Log.d(TAG, "File data sent, waiting for completion confirmation")

                val completionStatus = withTimeout(connectionTimeoutMs.toLong()) {
                    reader.readLine()
                }
                Log.d(TAG, "Received completion status: $completionStatus")
                return@withContext completionStatus == "SUCCESS"
            } finally {
                inputStream.close()
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

    fun cleanup() {
        closeCurrentSocket()
    }
}