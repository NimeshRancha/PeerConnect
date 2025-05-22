package com.example.peerconnect.util

sealed class FileCommand {
    object GetFileList : FileCommand() {
        override fun toString() = "GET_FILE_LIST"
    }
    
    data class GetFile(val filename: String) : FileCommand() {
        override fun toString() = "GET_FILE:$filename"
    }

    object UploadFile : FileCommand() {
        override fun toString() = "UPLOAD_FILE"
    }

    companion object {
        fun fromString(command: String): FileCommand? = when {
            command == "GET_FILE_LIST" -> GetFileList
            command.startsWith("GET_FILE:") -> {
                val filename = command.substringAfter("GET_FILE:")
                if (filename.isNotBlank()) GetFile(filename) else null
            }
            command == "UPLOAD_FILE" -> UploadFile
            else -> null
        }
    }
} 