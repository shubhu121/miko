package com.blurr.voice.core.learning

import com.blurr.voice.core.Miko
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Workflow Learning (MIKO.md Phase 3): learns the user's recurring routines from the local
 * timeline — e.g. "you usually open Notion in the morning" — with zero cloud dependency.
 * Purely frequency + time-of-day analysis over recent [com.blurr.voice.core.timeline] data.
 */
object RoutineLearner {

    data class Routine(
        val description: String,
        val actionInstruction: String?,
        val timeBucket: String,
        val packageName: String?,
        val strength: Int
    )

    private const val LOOKBACK_DAYS = 7L
    private const val MIN_DISTINCT_DAYS = 2

    /** Detects recurring app-usage routines, strongest first. */
    suspend fun detectRoutines(): List<Routine> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(LOOKBACK_DAYS)
        val appEvents = runCatching { Miko.timeline.getSince(since) }.getOrDefault(emptyList())
            .filter { it.type == "app" && !it.packageName.isNullOrBlank() }

        // Group by (time-of-day bucket, package); a routine = same app, same bucket, on
        // multiple distinct days.
        appEvents
            .groupBy { bucketOf(it.timestamp) to it.packageName!! }
            .mapNotNull { (key, events) ->
                val distinctDays = events.map { dayOf(it.timestamp) }.distinct().size
                if (distinctDays < MIN_DISTINCT_DAYS) return@mapNotNull null
                val (bucket, pkg) = key
                val app = prettyAppName(pkg)
                Routine(
                    description = "You usually open $app in the $bucket.",
                    actionInstruction = "Open $app",
                    timeBucket = bucket,
                    packageName = pkg,
                    strength = distinctDays
                )
            }
            .sortedByDescending { it.strength }
    }

    /** Routines relevant to the current time of day. */
    suspend fun routinesForNow(): List<Routine> {
        val now = bucketOf(System.currentTimeMillis())
        return detectRoutines().filter { it.timeBucket == now }
    }

    private fun bucketOf(ts: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
    }

    private fun dayOf(ts: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun prettyAppName(pkg: String): String =
        pkg.substringAfterLast('.').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
}
