package com.myagent.app.memory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 记忆管理器 — 存储和检索对话记录，完全本地，不依赖外部服务。
 *
 * 借鉴 Mem0 设计思想，第一期使用 SQLite + SQL LIKE 实现。
 */
class MemoryManager(context: Context) {
  private val db = MemoryDatabase.getInstance(context)
  private val dao = db.memoryDao()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  companion object {
    /** 最多保留 30 天的记忆 */
    private const val MAX_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
  }

  /**
   * 异步保存一条对话记忆
   */
  fun saveMemory(
    role: String,
    content: String,
    sessionId: String = "default",
  ) {
    scope.launch {
      dao.insert(
        MemoryEntity(
          role = role,
          content = content,
          sessionId = sessionId,
        ),
      )
    }
  }

  /**
   * 获取最近 N 条记忆
   */
  suspend fun getRecentMemories(limit: Int = 5): List<MemoryEntity> {
    return dao.getRecentMemories(limit).reversed()
  }

  /**
   * 关键词搜索记忆（第一期用 SQL LIKE）
   */
  suspend fun searchMemories(keyword: String, limit: Int = 10): List<MemoryEntity> {
    return dao.searchByKeyword(keyword, limit)
  }

  /**
   * 清理过期记忆（超过 30 天）
   */
  fun cleanupOldMemories() {
    scope.launch {
      val cutoff = System.currentTimeMillis() - MAX_RETENTION_MS
      dao.deleteOlderThan(cutoff)
    }
  }

  /**
   * 获取记忆总数
   */
  suspend fun getMemoryCount(): Int = dao.getCount()
}