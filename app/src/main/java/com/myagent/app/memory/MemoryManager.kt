package com.myagent.app.memory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 双层记忆管理器 — 短期清晰 + 长期模糊，永久记忆不删除。
 *
 * 架构：
 * ┌─────────────────────────────────────────┐
 * │  短期记忆（SHORT_TERM）                  │
 * │  · 逐字保存，最多 15 条                  │
 * │  · 超出时自动压缩到长期                  │
 * │  · 注入 Prompt 时取最近 5 条            │
 * ├─────────────────────────────────────────┤
 * │  长期记忆（LONG_TERM）                   │
 * │  · 压缩摘要，永久存储                    │
 * │  · 关键词提取 + 结构化合并              │
 * │  · 注入 Prompt 时取最近 3 条摘要       │
 * └─────────────────────────────────────────┘
 *
 * 压缩策略（短期→长期）：
 * 短期超过 15 条时，取最旧的 20 条 → 提取关键词/话题 → 合并为一条长期摘要。
 * 压缩在每次 saveMemory() 后异步触发，对用户无感知。
 *
 * 磁盘占用估算：
 * - 短期 15 条 × 平均 200 字 ≈ 3 KB
 * - 长期每 20 条压缩为 1 条 ≈ 每月 ~100 条摘要 ≈ 20 KB
 * - 一年 ≈ 240 KB，完全可接受
 */
class MemoryManager(context: Context) {
  private val db = MemoryDatabase.getInstance(context)
  private val dao = db.memoryDao()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  // M-20 修复：串行化 compactIfNeeded，防止并发压缩产生重复长期记忆与重复删除
  private val compactMutex = Mutex()

  companion object {
    /** 短期记忆上限（超出触发压缩） */
    const val SHORT_TERM_MAX = 15

    /** 压缩批次大小（每次取最旧 N 条压缩） */
    const val COMPACT_BATCH_SIZE = 20

    /** 注入 Prompt 的短期记忆条数 */
    const val CONTEXT_SHORT_TERM_COUNT = 5

    /** 注入 Prompt 的长期摘要条数 */
    const val CONTEXT_LONG_TERM_COUNT = 3
  }

  /**
   * 保存一条对话记忆（默认为短期记忆）。
   * 保存后自动检查是否需要压缩。
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
          memoryType = MemoryEntity.TYPE_SHORT_TERM,
        ),
      )
      // 异步检查是否需要压缩
      compactIfNeeded()
    }
  }

  /**
   * 获取用于注入 Prompt 的短期记忆（最近 N 条，正序）。
   */
  suspend fun getRecentMemories(limit: Int = CONTEXT_SHORT_TERM_COUNT): List<MemoryEntity> {
    return dao.getRecentShortTerm(limit).reversed()
  }

  /**
   * 获取用于注入 Prompt 的长期记忆摘要（最近 N 条，正序）。
   */
  suspend fun getLongTermContext(limit: Int = CONTEXT_LONG_TERM_COUNT): List<MemoryEntity> {
    return dao.getRecentLongTerm(limit).reversed()
  }

  /**
   * 获取完整的 Prompt 上下文（短期 + 长期）。
   */
  suspend fun getFullContext(): String {
    val shortTerm = getRecentMemories()
    val longTerm = getLongTermContext()

    return buildString {
      if (longTerm.isNotEmpty()) {
        append("--- 长期记忆（摘要） ---\n")
        longTerm.forEach { append("· ${it.content}\n") }
        append("\n")
      }
      if (shortTerm.isNotEmpty()) {
        append("--- 最近的对话 ---\n")
        shortTerm.forEach { append("${it.role}: ${it.content}\n") }
      }
    }
  }

  /**
   * 关键词搜索所有记忆（短期 + 长期）。
   */
  suspend fun searchMemories(keyword: String, limit: Int = 10): List<MemoryEntity> {
    return dao.searchByKeyword(keyword, limit)
  }

  /**
   * 获取记忆统计信息。
   */
  suspend fun getMemoryStats(): MemoryStats = MemoryStats(
    shortTermCount = dao.getShortTermCount(),
    longTermCount = dao.getLongTermCount(),
    totalCount = dao.getCount(),
  )

  /**
   * 清空所有记忆（仅用于测试/重置）。
   */
  fun clearAllMemories() {
    scope.launch {
      dao.deleteAll()
    }
  }

  // ── 内部：压缩逻辑 ──

  private suspend fun compactIfNeeded() {
    // M-20 修复：用 Mutex 串行化压缩，防止并发 compact 读取同一批记录导致重复摘要与重复删除
    compactMutex.withLock {
      val count = dao.getShortTermCount()
      if (count <= SHORT_TERM_MAX) return@withLock

      // 取最旧的 COMPACT_BATCH_SIZE 条
      val oldest = dao.getOldestShortTerm(COMPACT_BATCH_SIZE)
      if (oldest.isEmpty()) return@withLock

      // 提取摘要
      val summary = buildSummary(oldest)

      // 写入长期记忆
      dao.insert(
        MemoryEntity(
          role = "system",
          content = summary,
          sessionId = "compacted",
          memoryType = MemoryEntity.TYPE_LONG_TERM,
        ),
      )

      // 删除已压缩的短期记忆
      dao.deleteByIds(oldest.map { it.id })
    }
  }

  /**
   * 将一批短期记忆压缩为一条摘要。
   *
   * 策略：提取用户发言中的关键词/话题，合并助手回复的要点。
   * 后续版本可接入 LLM 做智能摘要。
   */
  private fun buildSummary(memories: List<MemoryEntity>): String {
    val userMessages = memories
      .filter { it.role == "user" }
      .map { it.content }
    val assistantMessages = memories
      .filter { it.role == "assistant" }
      .map { it.content }

    // 提取关键词（简单规则：找高频有意义的中文词）
    val allUserText = userMessages.joinToString(" ")
    val keywords = extractKeywords(allUserText)

    val timeRange = if (memories.isNotEmpty()) {
      val first = memories.first().createdAtMs
      val last = memories.last().createdAtMs
      formatTimeRange(first, last)
    } else ""

    return buildString {
      if (timeRange.isNotEmpty()) {
        append("[$timeRange] ")
      }
      if (keywords.isNotEmpty()) {
        append("话题：${keywords.joinToString("、")}。")
      }
      if (userMessages.isNotEmpty()) {
        append(" 用户讨论了：${userMessages.joinToString("；") { it.take(80) }}")
      }
      if (assistantMessages.size >= 2) {
        append(" 助手回应了 ${assistantMessages.size} 条消息。")
      }
    }
  }

  /**
   * 简单关键词提取：取长度 ≥ 2 的中文片段，去重后取前 5 个。
   */
  private fun extractKeywords(text: String): List<String> {
    // 按常见分隔符切分
    val segments = text
      .split(Regex("[，。！？,.!?\\s]+"))
      .filter { it.length in 2..8 }
      .filter { seg -> seg.any { c -> c in '\u4e00'..'\u9fff' } } // 至少含一个中文
      .distinct()
      .take(5)
    return segments
  }

  private fun formatTimeRange(fromMs: Long, toMs: Long): String {
    val sdf = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())
    return "${sdf.format(java.util.Date(fromMs))}-${sdf.format(java.util.Date(toMs))}"
  }
}

/**
 * 记忆统计信息。
 */
data class MemoryStats(
  val shortTermCount: Int,
  val longTermCount: Int,
  val totalCount: Int,
)