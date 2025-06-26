package com.example.peerconnect.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import androidx.documentfile.provider.DocumentFile

data class SharedFile(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val size: Long
)

class SharedFolder(
    private val context: Context,
    internal val folderUri: Uri
) {
    private val TAG = "SharedFolder"

    suspend fun listFiles(includeNested: Boolean = false): List<SharedFile> = withContext(Dispatchers.IO) {
        val files: MutableList<SharedFile> = mutableListOf()
        try {
            Log.d(TAG, "Listing files from folder: $folderUri")
            listFilesRecursive(folderUri, files, includeNested)
            Log.d(TAG, "Found ${files.size} files in folder")
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files: ${e.message}")
        }
        files
    }

    private suspend fun listFilesRecursive(
        uri: Uri,
        files: MutableList<SharedFile>,
        includeNested: Boolean
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val folder = DocumentFile.fromTreeUri(context, uri)
            if (folder == null || !folder.isDirectory) {
                Log.e(TAG, "Invalid folder DocumentFile for uri: $uri")
                return@withContext
            }
            for (file in folder.listFiles()) {
                if (file.isDirectory && includeNested) {
                    listFilesRecursive(file.uri, files, true)
                } else if (file.isFile) {
                    files.add(
                        SharedFile(
                            name = file.name ?: "",
                            uri = file.uri,
                            isDirectory = false,
                            size = file.length()
                        )
                    )
                    Log.d(TAG, "Added file: ${file.name} with URI: ${file.uri}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in listFilesRecursive: ${e.message}")
        }
    }

    suspend fun getFile(filename: String): SharedFile? = withContext(Dispatchers.IO) {
        listFiles(includeNested = true).find { it.name == filename }
    }

    fun openInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    fun openOutputStream(uri: Uri): OutputStream? {
        return context.contentResolver.openOutputStream(uri)
    }
} 