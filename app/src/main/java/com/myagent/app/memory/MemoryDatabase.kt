package com.myagent.app.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room 数据库 — 本地记忆存储。
 */
@Database(
  entities = [MemoryEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {
  abstract fun memoryDao(): MemoryDao

  companion object {
    @Volatile
    private var INSTANCE: MemoryDatabase? = null

    fun getInstance(context: Context): MemoryDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room
          .databaseBuilder(context.applicationContext, MemoryDatabase::class.java, "lingji_memory.db")
          .fallbackToDestructiveMigration()
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}