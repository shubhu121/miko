package com.blurr.voice.core.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.blurr.voice.core.Miko
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Miko's Reminder Intelligence (MIKO.md Phase 2).
 *
 * Schedules exact-ish alarms that surface as notifications via [ReminderReceiver], and can
 * propose reminders by reasoning over the user's memories + context. Reminders persist so
 * they can be listed and re-scheduled after reboot.
 */
object ReminderService {

    private const val TAG = "ReminderService"
    private const val PREFS = "miko_reminders"
    private const val KEY_REMINDERS = "reminders"
    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TEXT = "reminder_text"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Schedules [text] to fire at [triggerAtMillis]. Returns the created reminder. */
    fun schedule(text: String, triggerAtMillis: Long): Reminder {
        val reminder = Reminder(id = nextId(), text = text, triggerAtMillis = triggerAtMillis)
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntentFor(reminder)
        try {
            if (canScheduleExact(alarm)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                // No exact-alarm permission: fall back to inexact (still wakes the device).
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
            persistAdd(reminder)
            Log.d(TAG, "Scheduled reminder #${reminder.id} for $triggerAtMillis")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule reminder", e)
        }
        return reminder
    }

    fun cancel(id: Int) {
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val reminder = getAll().find { it.id == id } ?: Reminder(id, "", 0L)
        alarm.cancel(pendingIntentFor(reminder))
        persistRemove(id)
    }

    fun getAll(): List<Reminder> = try {
        val raw = prefs().getString(KEY_REMINDERS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { Reminder.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Asks the reasoning model to propose helpful reminders based on what Miko knows about
     * the user and their current context. Returns short reminder texts (may be empty).
     */
    suspend fun suggestReminders(): List<String> = withContext(Dispatchers.IO) {
        try {
            val memories = runCatching { Miko.memory.relevantMemoriesFor("things I should be reminded about") }
                .getOrDefault("")
            val context = runCatching { Miko.context.snapshot().toPromptString() }.getOrDefault("")
            val prompt = """
                You are Miko. Based on what you know about the user and their context, suggest up
                to 3 short, genuinely useful reminders (one line each). If nothing is worth
                reminding, reply with an empty list. Respond as a JSON array of strings only.

                What Miko knows:
                ${memories.ifBlank { "(nothing yet)" }}

                Context:
                ${context.ifBlank { "(unavailable)" }}
            """.trimIndent()
            val chat = addResponse("user", prompt, emptyList())
            val response = getReasoningModelApiResponse(chat) ?: return@withContext emptyList()
            val start = response.indexOf('[')
            val end = response.lastIndexOf(']')
            if (start < 0 || end <= start) return@withContext emptyList()
            val arr = JSONArray(response.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            Log.w(TAG, "suggestReminders failed: ${e.message}")
            emptyList()
        }
    }

    private fun canScheduleExact(alarm: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarm.canScheduleExactAlarms() else true

    private fun pendingIntentFor(reminder: Reminder): PendingIntent {
        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, reminder.id)
            putExtra(EXTRA_TEXT, reminder.text)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(appContext, reminder.id, intent, flags)
    }

    private fun nextId(): Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

    private fun persistAdd(reminder: Reminder) {
        val current = getAll().toMutableList()
        current.add(reminder)
        save(current)
    }

    private fun persistRemove(id: Int) = save(getAll().filterNot { it.id == id })

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs().edit().putString(KEY_REMINDERS, arr.toString()).apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
