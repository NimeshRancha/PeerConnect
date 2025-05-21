package com.example.peerconnect.util

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class FileTransferServer(
    private val context: Context,
    private val port: Int = 8988,
    private val targetFolderUri: Uri
) {
    private var isRunning = true
    fun startServer(onReceive: (String) -> Unit) {
        Thread {
            try {
                val serverSocket = ServerSocket(port)
                while (isRunning) {
                    val client: Socket = serverSocket.accept()
                    val input = DataInputStream(BufferedInputStream(client.getInputStream()))

                    val fileName = input.readUTF()
                    val fileSize = input.readLong()

                    val outputUri = createFileInFolder(context, targetFolderUri, fileName)
                    val outputStream = context.contentResolver.openOutputStream(outputUri!!)

                    val buffer = ByteArray(4096)
                    var totalRead = 0L
                    while (totalRead < fileSize) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        outputStream?.write(buffer, 0, read)
                        totalRead += read
                    }

                    outputStream?.flush()
                    outputStream?.close()
                    input.close()
                    client.close()

                    onReceive(fileName)
                }
                serverSocket.close()
            } catch (e: IOException) {
                Log.e("FileTransferServer", "Server error: ${e.message}")
            }
        }.start()
    }

    fun stopServer() {
        isRunning = false
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