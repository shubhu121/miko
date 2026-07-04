package com.blurr.voice.core.automation

import android.content.Context
import android.util.Log
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent
import com.blurr.voice.v2.AgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Miko's event-driven automation engine (MIKO.md Phase 2 "Automation Rules").
 *
 * Rules are matched against the [EventBus] and, when they fire, hand their instruction to
 * the agent ([AgentService]) to carry out. Rules persist in SharedPreferences. A per-rule
 * cooldown prevents a chatty event source from firing the same rule repeatedly.
 */
object AutomationService {

    private const val TAG = "AutomationService"
    private const val PREFS = "miko_automation"
    private const val KEY_RULES = "rules"
    private const val COOLDOWN_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context
    private val rules = mutableListOf<AutomationRule>()
    private val lastFired = mutableMapOf<String, Long>()
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        load()
        scope.launch {
            EventBus.events.collect { event -> evaluate(event) }
        }
        Log.d(TAG, "Automation started with ${rules.size} rule(s).")
    }

    fun getRules(): List<AutomationRule> = synchronized(rules) { rules.toList() }

    fun addRule(rule: AutomationRule) {
        synchronized(rules) {
            rules.removeAll { it.id == rule.id }
            rules.add(rule)
            persist()
        }
    }

    fun removeRule(id: String) {
        synchronized(rules) {
            rules.removeAll { it.id == id }
            persist()
        }
    }

    private fun evaluate(event: MikoEvent) {
        val now = event.timestamp
        val toFire = synchronized(rules) {
            rules.filter { it.enabled && matches(it, event) && !onCooldown(it.id, now) }
        }
        toFire.forEach { rule ->
            lastFired[rule.id] = now
            Log.d(TAG, "Rule '${rule.name}' fired.")
            runCatching { AgentService.start(appContext, rule.actionInstruction) }
                .onFailure { Log.w(TAG, "action failed: ${it.message}") }
        }
    }

    private fun onCooldown(id: String, now: Long): Boolean =
        (now - (lastFired[id] ?: 0L)) < COOLDOWN_MS

    private fun matches(rule: AutomationRule, event: MikoEvent): Boolean {
        val (type, pkg, text) = describe(event) ?: return false
        if (rule.eventType != type) return false
        rule.matchPackage?.let { if (!pkg.equals(it, ignoreCase = true)) return false }
        rule.matchKeyword?.let { if (!text.contains(it, ignoreCase = true)) return false }
        return true
    }

    /** Normalizes an event to (type, package, searchableText) for rule matching. */
    private fun describe(event: MikoEvent): Triple<String, String, String>? = when (event) {
        is MikoEvent.NotificationReceived ->
            Triple("notification", event.packageName, "${event.title} ${event.text}")
        is MikoEvent.AppOpened -> Triple("app", event.packageName, event.packageName)
        is MikoEvent.ClipboardChanged -> Triple("clipboard", "", event.text)
        is MikoEvent.ChargingChanged ->
            Triple("charging", "", if (event.isCharging) "charging" else "unplugged")
        is MikoEvent.BatteryLow -> Triple("battery_low", "", event.level.toString())
        is MikoEvent.PhoneUnlocked -> Triple("unlock", "", "")
        is MikoEvent.ScreenshotTaken -> Triple("screenshot", "", event.path)
        is MikoEvent.MissedCall -> Triple("missed_call", event.number, event.contactName ?: event.number)
        is MikoEvent.SmsReceived -> Triple("sms", event.sender, "${event.sender} ${event.body}")
        is MikoEvent.EmailReceived -> Triple("email", event.packageName, "${event.subject} ${event.preview}")
        is MikoEvent.CalendarNotification -> Triple("calendar", "", "${event.title} ${event.text}")
        else -> null
    }

    private fun load() {
        try {
            val raw = prefs().getString(KEY_RULES, null) ?: return
            val arr = JSONArray(raw)
            synchronized(rules) {
                rules.clear()
                for (i in 0 until arr.length()) {
                    rules.add(AutomationRule.fromJson(arr.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "load rules failed: ${e.message}")
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            rules.forEach { arr.put(it.toJson()) }
            prefs().edit().putString(KEY_RULES, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "persist rules failed: ${e.message}")
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
