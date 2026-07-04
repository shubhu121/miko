package com.blurr.voice.core.search

import com.blurr.voice.core.memory.MemoryRepository
import com.blurr.voice.core.timeline.TimelineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Global search across everything Miko knows — semantic memory + the event timeline.
 * Memory uses embedding-based semantic retrieval; timeline uses keyword matching.
 */
class UnifiedSearchService(
    private val memory: MemoryRepository,
    private val timeline: TimelineRepository
) {

    suspend fun search(query: String): List<SearchResult> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()

        val memoryDeferred = async(Dispatchers.IO) {
            runCatching { memory.search(query, topK = 8) }.getOrDefault(emptyList())
                .map { SearchResult(title = it, snippet = "", source = SearchResult.Source.MEMORY) }
        }
        val timelineDeferred = async(Dispatchers.IO) {
            runCatching { timeline.search(query) }.getOrDefault(emptyList())
                .map {
                    SearchResult(
                        title = it.title,
                        snippet = it.subtitle,
                        source = SearchResult.Source.TIMELINE,
                        timestamp = it.timestamp
                    )
                }
        }

        val results = memoryDeferred.await() + timelineDeferred.await()
        // Timeline hits sort by recency; memory hits keep semantic order at the top.
        results.sortedWith(compareByDescending { it.timestamp ?: Long.MAX_VALUE })
    }
}
