package com.blurr.voice.core

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.blurr.voice.core.automation.AutomationService
import com.blurr.voice.core.context.ContextService
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.ingestion.IngestionService
import com.blurr.voice.core.memory.MemoryRepository
import com.blurr.voice.core.reminders.ReminderService
import com.blurr.voice.core.screenshot.ScreenshotUnderstandingService
import com.blurr.voice.core.search.UnifiedSearchService
import com.blurr.voice.core.summary.DailySummaryStore
import com.blurr.voice.core.summary.DailySummaryWorker
import com.blurr.voice.core.timeline.TimelineRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Miko's composition root — the single place the intelligent operating layer is wired.
 *
 * Fits the app's existing manual-singleton style: [init] is called once from
 * [com.blurr.voice.MyApplication] and exposes the Context, Memory, Timeline, Search
 * and Summary services plus the shared [EventBus].
 */
object Miko {

    private const val TAG = "Miko"

    val events = EventBus

    lateinit var context: ContextService
        private set
    lateinit var memory: MemoryRepository
        private set
    lateinit var timeline: TimelineRepository
        private set
    lateinit var search: UnifiedSearchService
        private set
    lateinit var summary: DailySummaryStore
        private set

    @Volatile private var initialized = false

    fun init(app: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            context = ContextService.getInstance(app)
            memory = MemoryRepository.getInstance(app)
            timeline = TimelineRepository.getInstance(app)
            summary = DailySummaryStore.getInstance(app)
            search = UnifiedSearchService(memory, timeline)

            // Begin turning device events into a persistent timeline.
            timeline.startCollecting()
            // Begin collecting normalized context signals.
            context.start()
            // Bridge the event stream into the Cognee knowledge graph.
            IngestionService.start()
            // Phase 2: event-driven automation rules.
            AutomationService.start(app)
            // Phase 2: reminder scheduling + intelligence.
            ReminderService.init(app)
            // Phase 2: understand screenshots as they're taken.
            ScreenshotUnderstandingService.start()
            // Schedule the daily summary background worker.
            scheduleDailySummary(app)

            initialized = true
            Log.d(TAG, "Miko operating layer initialized.")
        }
    }

    private fun scheduleDailySummary(app: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(millisUntilNextEvening(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                DailySummaryWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule daily summary", e)
        }
    }

    /** Milliseconds from now until the next 21:00 local time. */
    private fun millisUntilNextEvening(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
