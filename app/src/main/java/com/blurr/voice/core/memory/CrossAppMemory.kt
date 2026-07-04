package com.blurr.voice.core.memory

import com.blurr.voice.api.MemoryService
import com.blurr.voice.core.Miko
import com.blurr.voice.core.timeline.TimelineEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale


object CrossAppMemory {

    private val cognee = MemoryService()
    private val stopWords = setOf(
        "the", "and", "for", "with", "you", "your", "from", "this", "that", "opened",
        "app", "notification", "miko", "user", "copied", "clipboard"
    )

    /** Graph-level answer describing how a topic connects across the user's apps. */
    suspend fun connections(topic: String): String = cognee.searchMemory(
        "How does \"$topic\" connect across the user's different apps and activity? " +
            "Summarize the relationships briefly."
    )


    suspend fun relatedTo(entry: TimelineEntry, windowMs: Long = 30 * 60 * 1000): List<TimelineEntry> =
        withContext(Dispatchers.IO) {
            val since = entry.timestamp - windowMs
            val until = entry.timestamp + windowMs
            val keywords = keywordsOf(entry)
            if (keywords.isEmpty()) return@withContext emptyList()
            runCatching { Miko.timeline.getSince(since) }.getOrDefault(emptyList())
                .filter { other ->
                    other.id != entry.id &&
                        other.timestamp in since..until &&
                        other.packageName != entry.packageName &&
                        keywords.any { k -> "${other.title} ${other.subtitle}".contains(k, ignoreCase = true) }
                }
                .distinctBy { it.id }
        }

    private fun keywordsOf(entry: TimelineEntry): List<String> =
        "${entry.title} ${entry.subtitle}"
            .lowercase(Locale.getDefault())
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 3 && it !in stopWords }
            .distinct()
            .take(8)
}
