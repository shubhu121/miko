package com.blurr.voice.core.suggestions

import android.util.Log
import com.blurr.voice.core.learning.RoutineLearner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Predictive Suggestions (MIKO.md Phase 3 §8 Proactive Assistance).
 *
 * Surfaces a small, ranked set of proactive suggestions for the Home feed. Local-first and
 * cheap by default (learned routines relevant to the current time of day); no blocking cloud
 * calls, so the Home screen stays instant.
 */
object SuggestionService {

    private const val TAG = "SuggestionService"

    /** Returns up to [limit] suggestions relevant right now (may be empty). */
    suspend fun currentSuggestions(limit: Int = 3): List<Suggestion> = withContext(Dispatchers.IO) {
        try {
            RoutineLearner.routinesForNow()
                .take(limit)
                .map { routine ->
                    Suggestion(
                        title = routine.description,
                        detail = "A routine Miko noticed",
                        actionInstruction = routine.actionInstruction,
                        source = Suggestion.Source.PATTERN
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "currentSuggestions failed: ${e.message}")
            emptyList()
        }
    }
}
