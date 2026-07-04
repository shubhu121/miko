package com.blurr.voice.core.notes

import org.json.JSONObject

/**
 * A single note in Miko's local, file-backed memory notepad. Holds free-form text and,
 * optionally, a voice recording. Stored on-device so saving never depends on the network.
 */
data class Note(
    val id: String,
    val title: String,
    val text: String,
    val audioPath: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    val hasAudio: Boolean get() = !audioPath.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("text", text)
        put("audioPath", audioPath ?: JSONObject.NULL)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    /** A short preview line for the list. */
    fun preview(): String = when {
        text.isNotBlank() -> text
        hasAudio -> "🎙 Voice note"
        else -> ""
    }

    companion object {
        fun fromJson(o: JSONObject): Note = Note(
            id = o.optString("id"),
            title = o.optString("title"),
            text = o.optString("text"),
            audioPath = o.optString("audioPath").takeIf { it.isNotBlank() && it != "null" },
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
