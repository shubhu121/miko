package com.blurr.voice.core.suggestions

/**
 * A proactive suggestion Miko surfaces to the user (MIKO.md Phase 3 "Predictive suggestions").
 * [actionInstruction], when present, can be run by the agent — after explicit confirmation.
 */
data class Suggestion(
    val title: String,
    val detail: String = "",
    val actionInstruction: String? = null,
    val source: Source = Source.PATTERN
) {
    enum class Source { PATTERN, MEMORY, REMINDER }

    val isActionable: Boolean get() = !actionInstruction.isNullOrBlank()
}
