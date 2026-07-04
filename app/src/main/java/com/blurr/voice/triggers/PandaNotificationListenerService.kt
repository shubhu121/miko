package com.blurr.voice.triggers

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PandaNotificationListenerService : NotificationListenerService() {

    private val TAG = "PandaNotification"
    private lateinit var triggerManager: TriggerManager

    companion object {
        private val EMAIL_APPS = listOf(
            "com.google.android.gm",            // Gmail
            "com.microsoft.office.outlook",     // Outlook
            "com.yahoo.mobile.client.android.mail",
            "com.samsung.android.email",
            "ch.protonmail.android",
            "me.bluemail.mail"
        )
        private val CALENDAR_APPS = listOf(
            "com.google.android.calendar",
            "com.samsung.android.calendar",
            "com.microsoft.office.outlook.calendar"
        )
    }

    override fun onCreate() {
        super.onCreate()
        triggerManager = TriggerManager.getInstance(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        Log.d(TAG, "Notification posted from package: $packageName")

        if (packageName == this.packageName) {
            Log.d(TAG, "Ignoring notification from own package.")
            return
        }

        // Feed the notification into Miko's context/event stream so it lands on the timeline.
        try {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            if (title.isNotBlank() || text.isNotBlank()) {
                val bus = com.blurr.voice.core.events.EventBus
                bus.publish(
                    com.blurr.voice.core.events.MikoEvent.NotificationReceived(
                        packageName = packageName,
                        title = title,
                        text = text,
                        timestamp = sbn.postTime
                    )
                )
                // Also emit a more specific event for email / calendar sources so Miko can
                // reason about "missed email" / upcoming events, not just raw notifications.
                when {
                    EMAIL_APPS.any { packageName.startsWith(it) } -> bus.publish(
                        com.blurr.voice.core.events.MikoEvent.EmailReceived(
                            packageName = packageName, subject = title, preview = text,
                            timestamp = sbn.postTime
                        )
                    )
                    CALENDAR_APPS.any { packageName.startsWith(it) } -> bus.publish(
                        com.blurr.voice.core.events.MikoEvent.CalendarNotification(
                            title = title, text = text, timestamp = sbn.postTime
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish notification event: ${e.message}")
        }

        CoroutineScope(Dispatchers.IO).launch {
            val notificationTriggers = triggerManager.getTriggers()
                .filter { it.type == TriggerType.NOTIFICATION && it.isEnabled }

            // First, check for the "All Applications" trigger
            var matchingTrigger = notificationTriggers.find { it.packageName == "*" }

            // If no "All Applications" trigger is found, check for a specific app trigger
            if (matchingTrigger == null) {
                matchingTrigger = notificationTriggers.find { it.packageName == packageName }
            }

            if (matchingTrigger != null) {
                val extras = sbn.notification.extras
                val title = extras.getString("android.title") ?: ""
                val text = extras.getCharSequence("android.text")?.toString() ?: ""
                val notificationContent = "Notification Content: $title - $text"
                val finalInstruction = "${matchingTrigger.instruction}\n\n$notificationContent"

                Log.d(TAG, "Found matching trigger for package: $packageName. Executing instruction: $finalInstruction")
                // Use the TriggerReceiver to start the agent service
                val intent = android.content.Intent(this@PandaNotificationListenerService, TriggerReceiver::class.java).apply {
                    action = TriggerReceiver.ACTION_EXECUTE_TASK
                    putExtra(TriggerReceiver.EXTRA_TASK_INSTRUCTION, finalInstruction)
                }
                sendBroadcast(intent)
            }
        }
    }
}
