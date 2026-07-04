package com.blurr.voice.core.ingestion

import android.util.Log
import com.blurr.voice.api.MemoryService
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * The missing bridge between Miko's raw activity stream and the Cognee knowledge graph.
 *
 * Cognee does the heavy lifting (chunking, embedding, entity/relationship extraction,
 * graph construction) server-side — so Miko does NOT need its own embedder or chunker.
 * What Miko needs, and what this provides, is *ingestion*: deciding which device events
 * are worth remembering, turning them into natural-language statements, tagging them with
 * a graph `node_set`, batching them, and flushing to Cognee's /add + a single /cognify.
 *
 * Local-first & best-effort: failures never affect the app. Explicit conversation memories
 * are ingested separately by [com.blurr.voice.core.memory.MemoryRepository]; this service
 * covers the ambient timeline (notifications, apps, clipboard, tasks, screenshots).
 */
object IngestionService {

    private const val TAG = "IngestionService"
    private val FLUSH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(3)
    private const val FLUSH_THRESHOLD = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = MemoryService()

    private data class Pending(val text: String, val nodeSet: String)

    private val buffer = mutableListOf<Pending>()
    private val mutex = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (!memory.isConfigured()) {
            Log.d(TAG, "Cognee not configured; ingestion disabled.")
            return
        }
        // Consume salient events into the batch buffer.
        scope.launch {
            EventBus.events.collect { event ->
                toStatement(event)?.let { (text, nodeSet) -> enqueue(text, nodeSet) }
            }
        }
        // Time-based flush so low-traffic periods still ingest.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
        Log.d(TAG, "Ingestion started.")
    }

    private suspend fun enqueue(text: String, nodeSet: String) {
        val shouldFlush = mutex.withLock {
            buffer.add(Pending(text, nodeSet))
            buffer.size >= FLUSH_THRESHOLD
        }
        if (shouldFlush) flush()
    }

    /** Adds every buffered statement to Cognee, then rebuilds the graph once. */
    private suspend fun flush() {
        val batch = mutex.withLock {
            if (buffer.isEmpty()) return
            buffer.toList().also { buffer.clear() }
        }
        Log.d(TAG, "Flushing ${batch.size} statements to Cognee.")
        for (item in batch) {
            // triggerCognify = false: cognify once after the whole batch, not per item.
            runCatching { memory.addMemory(item.text, nodeSet = item.nodeSet, triggerCognify = false) }
                .onFailure { Log.w(TAG, "ingest add failed: ${it.message}") }
        }
        runCatching { memory.cognify() }
            .onFailure { Log.w(TAG, "ingest cognify failed: ${it.message}") }
    }

    /**
     * Turns a device event into a natural-language statement + graph node_set, or null to
     * ignore it. Statements read like observations so the graph captures meaning, not logs.
     */
    private fun toStatement(event: MikoEvent): Pair<String, String>? = when (event) {
        is MikoEvent.NotificationReceived -> {
            val body = listOf(event.title, event.text).filter { it.isNotBlank() }.joinToString(" — ")
            if (body.isBlank()) null
            else "Notification from ${event.packageName}: $body" to "notifications"
        }
        is MikoEvent.AppOpened ->
            "The user opened the app ${event.packageName}." to "apps"
        is MikoEvent.ClipboardChanged ->
            // Only ingest clipboard content of some substance.
            if (event.text.length < 12) null
            else "The user copied to the clipboard: ${event.text.take(280)}" to "clipboard"
        is MikoEvent.UserTask ->
            "The user asked Miko to: ${event.instruction}" to "tasks"
        is MikoEvent.ScreenshotTaken ->
            "The user took a screenshot (${event.path})." to "screenshots"
        is MikoEvent.MissedCall ->
            "The user missed a call from ${event.contactName ?: event.number}." to "calls"
        is MikoEvent.SmsReceived ->
            "Message from ${event.sender}: ${event.body.take(280)}" to "messages"
        is MikoEvent.EmailReceived ->
            "Email (${event.packageName}): ${event.subject}. ${event.preview.take(280)}" to "emails"
        is MikoEvent.CalendarNotification ->
            "Calendar: ${event.title}. ${event.text.take(200)}" to "calendar"
        is MikoEvent.Custom ->
            "${event.title}${if (event.detail.isNotBlank()) ": ${event.detail}" else ""}" to event.type
        // MemoryLearned is ingested by MemoryRepository; power/unlock/connectivity aren't
        // worth graphing on their own.
        else -> null
    }
}
