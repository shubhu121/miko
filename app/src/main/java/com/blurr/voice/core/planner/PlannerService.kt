package com.blurr.voice.core.planner

import android.util.Log
import com.blurr.voice.core.Miko
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Miko's reasoning engine (MIKO.md §4 "Planning Engine").
 *
 * Instead of prompt → response, it does: intent → memory retrieval + context →
 * structured [Plan] → (optional) reflection. Backed by the reasoning LLM, grounded with
 * the user's relevant memories and live device context.
 */
object PlannerService {

    private const val TAG = "PlannerService"

    /** Produces a grounded [Plan] for a user intent, or null if reasoning fails. */
    suspend fun plan(intent: String): Plan? = withContext(Dispatchers.IO) {
        if (intent.isBlank()) return@withContext null
        try {
            val context = runCatching { Miko.context.snapshot().toPromptString() }.getOrDefault("")
            val memories = runCatching { Miko.memory.relevantMemoriesFor(intent) }.getOrDefault("")

            val prompt = buildPlanPrompt(intent, context, memories)
            val chat = addResponse("user", prompt, emptyList())
            val response = getReasoningModelApiResponse(chat) ?: return@withContext null
            parsePlan(intent, response)
        } catch (e: Exception) {
            Log.e(TAG, "plan failed", e)
            null
        }
    }

    /**
     * Reflection step: given a plan and what actually happened, produce a short lesson and
     * persist it as a memory so future planning improves.
     */
    suspend fun reflect(plan: Plan, outcome: String): String = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                You are Miko reflecting on a completed task to learn from it.
                Intent: ${plan.intent}
                Plan steps: ${plan.steps.joinToString("; ")}
                Outcome: $outcome

                In one or two sentences, note anything worth remembering about this user or
                task for next time. If nothing is worth remembering, reply exactly with "NONE".
            """.trimIndent()
            val chat = addResponse("user", prompt, emptyList())
            val reflection = getReasoningModelApiResponse(chat)?.trim().orEmpty()
            if (reflection.isNotBlank() && !reflection.equals("NONE", ignoreCase = true)) {
                runCatching { Miko.memory.addMemory(reflection) }
                reflection
            } else ""
        } catch (e: Exception) {
            Log.w(TAG, "reflect failed: ${e.message}")
            ""
        }
    }

    private fun buildPlanPrompt(intent: String, context: String, memories: String): String = """
        You are Miko, an AI personal operating layer. Plan how to help the user with their
        intent. Use what you know about them and their current context. Think step by step,
        then respond with ONLY a JSON object (no markdown) of exactly this shape:
        {
          "reasoning": "brief why",
          "steps": ["step 1", "step 2"],
          "suggestedAction": "a single concrete instruction Miko could run, or null",
          "requiresConfirmation": true
        }

        User intent:
        $intent

        What Miko knows about the user:
        ${memories.ifBlank { "(nothing relevant yet)" }}

        Current device context:
        ${context.ifBlank { "(unavailable)" }}
    """.trimIndent()

    private fun parsePlan(intent: String, raw: String): Plan? {
        return try {
            val json = JSONObject(extractJson(raw))
            val stepsArray = json.optJSONArray("steps")
            val steps = if (stepsArray != null) {
                (0 until stepsArray.length()).mapNotNull { stepsArray.optString(it).takeIf { s -> s.isNotBlank() } }
            } else emptyList()
            val action = json.optString("suggestedAction").takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true)
            }
            Plan(
                intent = intent,
                reasoning = json.optString("reasoning"),
                steps = steps,
                suggestedAction = action,
                requiresConfirmation = json.optBoolean("requiresConfirmation", true)
            )
        } catch (e: Exception) {
            Log.w(TAG, "parsePlan failed, wrapping raw text: ${e.message}")
            // Fall back to a minimal plan so callers still get the reasoning text.
            Plan(intent = intent, reasoning = raw.trim(), steps = emptyList())
        }
    }

    /** Strips ```json fences / surrounding prose to the outermost JSON object. */
    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }
}
