package com.blurr.voice.core.memory

import android.content.Context
import android.util.Log
import com.blurr.voice.api.MemoryService
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent
import com.blurr.voice.data.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Miko's unified Memory Service.
 *
 * Consolidates the previously scattered memory paths into a single entry point:
 *  - **Local (authoritative):** Room + Gemini embeddings via [MemoryManager] — offline,
 *    semantic, private. This is always the source of truth.
 *  - **Cognee (enrichment):** best-effort cloud knowledge-graph via [MemoryService].
 *    Failures never affect local behaviour, honouring Miko's local-first principle.
 */
class MemoryRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "MemoryRepository"

        @Volatile private var INSTANCE: MemoryRepository? = null
        fun getInstance(context: Context): MemoryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val local = MemoryManager(context.applicationContext)
    private val cognee = MemoryService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Stores a new memory locally (authoritative) and syncs to Cognee in the background.
     * [nodeSet] groups the memory in the knowledge graph (e.g. "notes", "notifications").
     */
    suspend fun addMemory(text: String, nodeSet: String? = null): Boolean {
        if (text.isBlank()) return false
        val stored = runCatching { local.addMemory(text) }.getOrDefault(false)
        if (stored) {
            EventBus.publish(MikoEvent.MemoryLearned(text))
            // Fire-and-forget cloud enrichment.
            scope.launch {
                runCatching { cognee.addMemory(text, nodeSet = nodeSet) }
                    .onFailure { Log.w(TAG, "cognee sync failed: ${it.message}") }
            }
        }
        return stored
    }

    /** Semantic search across local memory, enriched with Cognee results. */
    suspend fun search(query: String, topK: Int = 5): List<String> = withContext(Dispatchers.IO) {
        val localResults = runCatching { local.searchMemories(query, topK) }.getOrDefault(emptyList())
        val cloudResults = runCatching { cognee.searchMemoryList(query) }.getOrDefault(emptyList())
        (localResults + cloudResults)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Relevant-memory block ready to inject into a prompt. */
    suspend fun relevantMemoriesFor(taskDescription: String): String =
        runCatching { local.getRelevantMemories(taskDescription) }.getOrDefault("")

    suspend fun getAll(): List<MikoMemory> = withContext(Dispatchers.IO) {
        runCatching {
            local.getAllMemoriesList().map {
                MikoMemory(id = it.id, text = it.originalText, createdAt = it.timestamp)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun delete(id: Long): Boolean =
        runCatching { local.deleteMemoryById(id) }.getOrDefault(false)

    suspend fun count(): Int = runCatching { local.getMemoryCount() }.getOrDefault(0)
}
