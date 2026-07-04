package com.blurr.voice.core.memory

/** A semantic memory Miko holds about the user. */
data class MikoMemory(
    val id: Long,
    val text: String,
    val createdAt: Long,
    val source: String = "local"
)
