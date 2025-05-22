package com.example.peerconnect.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

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
    suspend fun listFiles(includeNested: Boolean = false): List<SharedFile> = withContext(Dispatchers.IO) {
        val files: MutableList<SharedFile> = mutableListOf()
        listFilesRecursive(folderUri, files, includeNested)
        files
    }

    private suspend fun listFilesRecursive(
        uri: Uri,
        files: MutableList<SharedFile>,
        includeNested: Boolean
    ): Unit = withContext(Dispatchers.IO) {
        val cursor: android.database.Cursor? = context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null,
            null,
            null
        )
        
        cursor?.use { safeCursor ->
            val idColumn: Int = safeCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn: Int = safeCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn: Int = safeCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn: Int = safeCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

            while (safeCursor.moveToNext()) {
                val id: String = safeCursor.getString(idColumn)
                val name: String = safeCursor.getString(nameColumn)
                val mime: String = safeCursor.getString(mimeColumn)
                val size: Long = safeCursor.getLong(sizeColumn)
                val isDirectory: Boolean = mime == DocumentsContract.Document.MIME_TYPE_DIR

                val childUri: Uri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
                
                if (isDirectory && includeNested) {
                    listFilesRecursive(childUri, files, true)
                } else if (!isDirectory) {
                    files.add(SharedFile(name, childUri, isDirectory, size))
                }
            }
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