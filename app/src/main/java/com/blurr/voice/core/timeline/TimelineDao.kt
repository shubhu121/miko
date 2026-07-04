package com.blurr.voice.core.timeline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineDao {

    @Insert
    suspend fun insert(entry: TimelineEntry): Long

    @Query("SELECT * FROM timeline_entries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TimelineEntry>>

    @Query("SELECT * FROM timeline_entries WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getSince(since: Long): List<TimelineEntry>

    @Query("SELECT * FROM timeline_entries ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<TimelineEntry>

    @Query("SELECT * FROM timeline_entries WHERE title LIKE :q OR subtitle LIKE :q ORDER BY timestamp DESC LIMIT 50")
    suspend fun search(q: String): List<TimelineEntry>

    @Query("DELETE FROM timeline_entries")
    suspend fun clear()

    @Query("DELETE FROM timeline_entries WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
