package com.example.peerconnect.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.*
import java.net.Socket

class FileTransferClient(
    private val context: Context,
    private val targetIp: String,
    private val targetPort: Int = 8988
) {
    fun sendFile(fileUri: Uri, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val socket = Socket(targetIp, targetPort)
                val fileDescriptor =
                    context.contentResolver.openFileDescriptor(fileUri, "r") ?: return@Thread
                val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
                val fileName = getFileName(context, fileUri)
                val fileSize = fileDescriptor.statSize

                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                output.writeUTF(fileName)
                output.writeLong(fileSize)

                val buffer = ByteArray(4096)
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }

                output.flush()
                output.close()
                inputStream.close()
                fileDescriptor.close()
                socket.close()

                onResult(true)
            } catch (e: IOException) {
                Log.e("FileTransferClient", "Client error: ${e.message}")
                onResult(false)
            }
        }.start()
    }

    private fun getFileName(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(nameIndex)
        } ?: "file.bin"
    }
}