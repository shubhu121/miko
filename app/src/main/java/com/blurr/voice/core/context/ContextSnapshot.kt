package com.blurr.voice.core.context

/**
 * A normalized, point-in-time view of the user's device context.
 * Produced by [ContextService] and consumed by reasoning / proactivity.
 */
data class ContextSnapshot(
    val timestamp: Long,
    val foregroundApp: String?,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val isOnline: Boolean,
    val clipboardText: String?,
    val timeOfDay: String,
    val installedAppCount: Int
) {
    /** Compact, human-readable form for injecting into LLM prompts. */
    fun toPromptString(): String = buildString {
        append("Time: $timeOfDay\n")
        foregroundApp?.let { append("Foreground app: $it\n") }
        append("Battery: $batteryLevel%${if (isCharging) " (charging)" else ""}\n")
        append("Network: ${if (isOnline) "online" else "offline"}\n")
        clipboardText?.takeIf { it.isNotBlank() }?.let {
            append("Clipboard: ${it.take(120)}\n")
        }
    }
}
