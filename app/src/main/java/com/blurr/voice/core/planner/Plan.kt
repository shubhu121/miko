package com.blurr.voice.core.planner

/**
 * The output of Miko's [PlannerService] — the reasoning layer that replaces the old
 * direct prompt → response flow with: intent → memory/context retrieval → plan.
 */
data class Plan(
    val intent: String,
    val reasoning: String,
    val steps: List<String>,
    /** A single concrete instruction Miko could execute for the user, if any. */
    val suggestedAction: String? = null,
    /** True when [suggestedAction] would change something and should be confirmed first. */
    val requiresConfirmation: Boolean = true
) {
    val isActionable: Boolean get() = !suggestedAction.isNullOrBlank()
}
