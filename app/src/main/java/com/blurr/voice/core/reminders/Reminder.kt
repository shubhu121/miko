package com.blurr.voice.core.reminders

import org.json.JSONObject

/** A single scheduled reminder surfaced to the user via a notification. */
data class Reminder(
    val id: Int,
    val text: String,
    val triggerAtMillis: Long,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("triggerAtMillis", triggerAtMillis)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Reminder = Reminder(
            id = o.optInt("id"),
            text = o.optString("text"),
            triggerAtMillis = o.optLong("triggerAtMillis"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
