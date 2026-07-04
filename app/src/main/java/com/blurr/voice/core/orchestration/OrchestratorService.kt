package com.blurr.voice.core.orchestration

import android.content.Context
import android.util.Log
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import com.blurr.voice.v2.AgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Multi-agent orchestration (MIKO.md Phase 3).
 *
 * Decomposes a complex goal into an ordered set of subtasks using the reasoning model, then
 * hands the goal + plan to the execution agent so it works through the steps deliberately.
 * The decomposition is also recorded to the timeline so the user can see how Miko is thinking.
 */
object OrchestratorService {

    private const val TAG = "OrchestratorService"

    data class SubTask(val step: Int, val description: String)

    /** Breaks [goal] into ordered subtasks (may be empty if the goal is atomic). */
    suspend fun decompose(goal: String): List<SubTask> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                You are Miko's orchestrator. Break the user's goal into a short ordered list of
                concrete subtasks (2-6). If the goal is already a single simple action, return an
                empty array. Respond with ONLY a JSON array of strings, each a subtask.

                Goal: $goal
            """.trimIndent()
            val chat = addResponse("user", prompt, emptyList())
            val response = getReasoningModelApiResponse(chat) ?: return@withContext emptyList()
            val start = response.indexOf('[')
            val end = response.lastIndexOf(']')
            if (start < 0 || end <= start) return@withContext emptyList()
            val arr = JSONArray(response.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }?.let { SubTask(i + 1, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decompose failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Decomposes [goal], records the plan, and starts an agent session steered by the plan.
     * Returns the subtasks so callers can display them.
     */
    suspend fun orchestrate(context: Context, goal: String): List<SubTask> {
        val subtasks = decompose(goal)
        if (subtasks.isEmpty()) {
            AgentService.start(context, goal)
            return emptyList()
        }
        val planText = subtasks.joinToString("\n") { "${it.step}. ${it.description}" }
        runCatching {
            EventBus.publish(
                MikoEvent.Custom(type = "orchestration", title = "Miko planned a multi-step task", detail = goal)
            )
        }
        AgentService.start(
            context,
            "$goal\n\nWork through this plan in order:\n$planText"
        )
        return subtasks
    }
}
