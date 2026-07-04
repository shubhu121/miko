package com.blurr.voice.core.timeline

import android.content.Context
import android.util.Log
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Records the stream of [MikoEvent]s onto a persistent, chronological timeline and
 * exposes it for the home feed, unified search and the daily summary worker.
 */
class TimelineRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "TimelineRepository"
        private const val RETENTION_DAYS = 30L
        private val RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000

        @Volatile private var INSTANCE: TimelineRepository? = null
        fun getInstance(context: Context): TimelineRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TimelineRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val dao = TimelineDatabase.getInstance(context).timelineDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeAll(): Flow<List<TimelineEntry>> = dao.observeAll()

    suspend fun getSince(since: Long): List<TimelineEntry> = dao.getSince(since)

    suspend fun getRecent(limit: Int = 50): List<TimelineEntry> = dao.getRecent(limit)

    suspend fun search(query: String): List<TimelineEntry> = dao.search("%$query%")

    suspend fun record(entry: TimelineEntry) {
        try {
            dao.insert(entry)
        } catch (e: Exception) {
            Log.e(TAG, "record failed", e)
        }
    }

    suspend fun clear() = dao.clear()

    /** Begins collecting events from the [EventBus] into the timeline. */
    fun startCollecting() {
        scope.launch {
            EventBus.events.collect { event ->
                toEntry(event)?.let { runCatching { dao.insert(it) } }
            }
        }
        // Prune old entries once at startup.
        scope.launch {
            runCatching { dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS) }
        }
    }

    /** Maps a device event to a human-readable timeline entry (or null to ignore). */
    private fun toEntry(event: MikoEvent): TimelineEntry? = when (event) {
        is MikoEvent.NotificationReceived -> TimelineEntry(
            timestamp = event.timestamp,
            type = "notification",
            title = event.title.ifBlank { "Notification" },
            subtitle = event.text,
            packageName = event.packageName
        )
        is MikoEvent.AppOpened -> TimelineEntry(
            timestamp = event.timestamp,
            type = "app",
            title = "Opened ${event.packageName}",
            packageName = event.packageName
        )
        is MikoEvent.ClipboardChanged -> TimelineEntry(
            timestamp = event.timestamp,
            type = "clipboard",
            title = "Copied to clipboard",
            subtitle = event.text.take(140)
        )
        is MikoEvent.ChargingChanged -> TimelineEntry(
            timestamp = event.timestamp,
            type = "power",
            title = if (event.isCharging) "Charging started" else "Charging stopped"
        )
        is MikoEvent.BatteryLow -> TimelineEntry(
            timestamp = event.timestamp,
            type = "power",
            title = "Battery low (${event.level}%)"
        )
        is MikoEvent.ScreenshotTaken -> TimelineEntry(
            timestamp = event.timestamp,
            type = "screenshot",
            title = "Screenshot taken",
            subtitle = event.path
        )
        is MikoEvent.UserTask -> TimelineEntry(
            timestamp = event.timestamp,
            type = "task",
            title = "Asked Miko",
            subtitle = event.instruction
        )
        is MikoEvent.MemoryLearned -> TimelineEntry(
            timestamp = event.timestamp,
            type = "memory",
            title = "Miko learned something",
            subtitle = event.text
        )
        is MikoEvent.MissedCall -> TimelineEntry(
            timestamp = event.timestamp,
            type = "call",
            title = "Missed call from ${event.contactName ?: event.number}",
            subtitle = if (event.contactName != null) event.number else ""
        )
        is MikoEvent.SmsReceived -> TimelineEntry(
            timestamp = event.timestamp,
            type = "sms",
            title = "Message from ${event.sender}",
            subtitle = event.body
        )
        is MikoEvent.EmailReceived -> TimelineEntry(
            timestamp = event.timestamp,
            type = "email",
            title = event.subject.ifBlank { "New email" },
            subtitle = event.preview,
            packageName = event.packageName
        )
        is MikoEvent.CalendarNotification -> TimelineEntry(
            timestamp = event.timestamp,
            type = "calendar",
            title = event.title.ifBlank { "Calendar" },
            subtitle = event.text
        )
        is MikoEvent.Custom -> TimelineEntry(
            timestamp = event.timestamp,
            type = event.type,
            title = event.title,
            subtitle = event.detail
        )
        // Not worth persisting on their own.
        is MikoEvent.PhoneUnlocked,
        is MikoEvent.ConnectivityChanged -> null
    }
}
