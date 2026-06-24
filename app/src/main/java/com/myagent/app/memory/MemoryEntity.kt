package com.myagent.app.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对话记忆实体 — 存储一条对话记录到 SQLite。
 */
@Entity(tableName = "memories")
data class MemoryEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,

  @ColumnInfo(name = "role")
  val role: String, // "user" 或 "assistant"

  @ColumnInfo(name = "content")
  val content: String,

  @ColumnInfo(name = "session_id")
  val sessionId: String = "default",

  @ColumnInfo(name = "created_at_ms")
  val createdAtMs: Long = System.currentTimeMillis(),
)