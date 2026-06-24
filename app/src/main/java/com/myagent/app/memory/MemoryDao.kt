package com.myagent.app.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
  @Insert
  suspend fun insert(memory: MemoryEntity)

  @Query("SELECT * FROM memories ORDER BY created_at_ms DESC LIMIT :limit")
  suspend fun getRecentMemories(limit: Int): List<MemoryEntity>

  @Query("SELECT * FROM memories ORDER BY created_at_ms DESC LIMIT :limit")
  fun getRecentMemoriesFlow(limit: Int): Flow<List<MemoryEntity>>

  @Query("SELECT * FROM memories WHERE content LIKE '%' || :keyword || '%' ORDER BY created_at_ms DESC LIMIT :limit")
  suspend fun searchByKeyword(keyword: String, limit: Int = 10): List<MemoryEntity>

  @Query("SELECT COUNT(*) FROM memories")
  suspend fun getCount(): Int

  @Query("DELETE FROM memories WHERE created_at_ms < :beforeMs")
  suspend fun deleteOlderThan(beforeMs: Long)
}