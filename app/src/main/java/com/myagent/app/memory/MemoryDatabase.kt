package com.myagent.app.memory

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Room 数据库 — 双层记忆存储，持久化到外部存储（清除数据后不丢失）。
 *
 * v1: 单表 memories（id, role, content, session_id, created_at_ms）
 * v2: 新增 memory_type 列（"short"/"long"），默认 "short"
 * v3: 数据库文件迁移到外部存储（getExternalFilesDir），清除数据/缓存不会删除
 */
@Database(
  entities = [MemoryEntity::class],
  version = 2,
  exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {
  abstract fun memoryDao(): MemoryDao

  companion object {
    private const val TAG = "MemoryDatabase"
    private const val DB_NAME = "lingji_memory.db"

    @Volatile
    private var INSTANCE: MemoryDatabase? = null

    private val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
          ALTER TABLE memories ADD COLUMN memory_type TEXT NOT NULL DEFAULT 'short'
        """.trimIndent())
      }
    }

    /**
     * 获取数据库实例。数据库文件存储在外部存储（getExternalFilesDir），
     * 用户清除应用数据后不会删除，确保长期记忆永久保留。
     */
    fun getInstance(context: Context): MemoryDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
      }
    }

    @Suppress("RoomWarnings")
    private fun buildDatabase(appContext: Context): MemoryDatabase {
      val externalDir = appContext.getExternalFilesDir(null)

      // 确定数据库目录：优先外部存储，不可用时回退内部存储
      val dbDir: File = if (externalDir != null) {
        File(externalDir, "databases").also { it.mkdirs() }
      } else {
        // 外部存储不可用，回退到内部存储（Android 默认行为）
        appContext.getDatabasePath(DB_NAME).parentFile ?: appContext.filesDir
      }

      // 自动迁移：如果内部存储有旧数据库，复制到外部存储
      if (externalDir != null) {
        migrateLegacyDatabase(appContext, dbDir)
      }

      // 使用 ContextWrapper 将数据库路径重定向到外部存储
      val wrappedContext = object : ContextWrapper(appContext) {
        override fun getDatabasePath(name: String): File = File(dbDir, name)
      }

      return Room
        .databaseBuilder(wrappedContext, MemoryDatabase::class.java, DB_NAME)
        .addMigrations(MIGRATION_1_2)
        .fallbackToDestructiveMigration()
        .build()
    }

    /**
     * 将旧内部存储中的数据库文件迁移到外部存储目录。
     * 同时处理 WAL 模式下的 -wal 和 -shm 附属文件。
     */
    private fun migrateLegacyDatabase(appContext: Context, newDbDir: File) {
      val newDbFile = File(newDbDir, DB_NAME)
      if (newDbFile.exists()) return // 目标已存在，无需迁移

      val oldDbPath = appContext.getDatabasePath(DB_NAME)
      if (!oldDbPath.exists()) return

      try {
        // 迁移主数据库文件
        oldDbPath.copyTo(newDbFile, overwrite = false)

        // 迁移 WAL 附属文件
        listOf("$DB_NAME-wal", "$DB_NAME-shm").forEach { suffix ->
          val oldFile = appContext.getDatabasePath(suffix)
          if (oldFile.exists()) {
            oldFile.copyTo(File(newDbDir, suffix), overwrite = false)
          }
        }

        Log.i(TAG, "数据库已从内部存储迁移到外部存储：${newDbFile.absolutePath}")
      } catch (e: Exception) {
        Log.w(TAG, "数据库迁移失败，将在外部存储创建新数据库：${e.message}")
        // 清理可能的不完整副本
        newDbFile.delete()
        listOf("$DB_NAME-wal", "$DB_NAME-shm").forEach { suffix ->
          File(newDbDir, suffix).delete()
        }
      }
    }
  }
}