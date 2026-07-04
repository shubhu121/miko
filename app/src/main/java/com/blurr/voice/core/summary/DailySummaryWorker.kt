package com.blurr.voice.core.summary

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blurr.voice.core.timeline.TimelineRepository
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Background-intelligence worker: once a day it reads the timeline, asks the LLM to
 * distil a short, friendly summary of what happened, and stores it for the home feed.
 */
class DailySummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val timeline = TimelineRepository.getInstance(applicationContext)
            val entries = timeline.getSince(startOfToday())
            if (entries.isEmpty()) {
                Log.d(TAG, "No timeline entries today; skipping summary.")
                return Result.success()
            }

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val bulletLog = entries.joinToString("\n") { e ->
                "${timeFmt.format(Date(e.timestamp))} — ${e.title}${if (e.subtitle.isNotBlank()) ": ${e.subtitle.take(120)}" else ""}"
            }

            val prompt = """
                You are Miko, a calm, thoughtful personal AI. Below is a chronological log of the user's day on their phone.
                Write a short, warm daily summary (3-5 sentences). Highlight what seemed important, any patterns you notice,
                and one gentle, helpful suggestion for tomorrow. Speak directly to the user. Do not use markdown headings.

                Today's activity:
                $bulletLog
            """.trimIndent()

            val chat = addResponse("user", prompt, emptyList())
            val response = getReasoningModelApiResponse(chat)

            if (response.isNullOrBlank()) {
                Log.w(TAG, "LLM returned no summary.")
                Result.retry()
            } else {
                DailySummaryStore.getInstance(applicationContext).save(response.trim(), todayKey())
                Log.d(TAG, "Daily summary saved.")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Daily summary failed", e)
            Result.retry()
        }
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    companion object {
        private const val TAG = "DailySummaryWorker"
        const val WORK_NAME = "miko_daily_summary"
    }
}
