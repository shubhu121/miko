package com.blurr.voice.core.search

/** A single hit from Miko's semantic unified search. */
data class SearchResult(
    val title: String,
    val snippet: String,
    val source: Source,
    val timestamp: Long? = null
) {
    enum class Source(val label: String) {
        MEMORY("Memory"),
        TIMELINE("Timeline")
    }
}
