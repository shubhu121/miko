package com.blurr.voice.core.automation

import org.json.JSONObject

/**
 * A user-defined "when X happens, do Y" rule evaluated against Miko's event stream.
 * Distinct from the older time/charging [com.blurr.voice.triggers] system: these react to
 * semantic [com.blurr.voice.core.events.MikoEvent]s (notifications, app opens, clipboard…).
 */
data class AutomationRule(
    val id: String,
    val name: String,
    /** One of: notification, app, clipboard, charging, battery_low, unlock, screenshot. */
    val eventType: String,
    val matchPackage: String? = null,
    val matchKeyword: String? = null,
    /** Natural-language instruction handed to the agent when the rule fires. */
    val actionInstruction: String,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("eventType", eventType)
        put("matchPackage", matchPackage ?: JSONObject.NULL)
        put("matchKeyword", matchKeyword ?: JSONObject.NULL)
        put("actionInstruction", actionInstruction)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): AutomationRule = AutomationRule(
            id = o.optString("id"),
            name = o.optString("name"),
            eventType = o.optString("eventType"),
            matchPackage = o.optString("matchPackage").takeIf { it.isNotBlank() && it != "null" },
            matchKeyword = o.optString("matchKeyword").takeIf { it.isNotBlank() && it != "null" },
            actionInstruction = o.optString("actionInstruction"),
            enabled = o.optBoolean("enabled", true)
        )
    }
}
