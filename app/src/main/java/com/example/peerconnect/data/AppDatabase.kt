package com.example.peerconnect.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TransferLog::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transferLogDao(): TransferLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peerconnect_database"
                ).build().also { INSTANCE = it }
            }
        }
    }
}