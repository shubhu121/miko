package com.blurr.voice.core.events

/**
 * Everything that happens on the device becomes a [MikoEvent].
 * Events flow through the [EventBus] and are consumed by the Context Manager,
 * the Timeline and (eventually) the Planner for proactive assistance.
 */
sealed class MikoEvent {
    abstract val timestamp: Long

    data class NotificationReceived(
        val packageName: String,
        val title: String,
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    data class AppOpened(
        val packageName: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    data class ClipboardChanged(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    data class ChargingChanged(
        val isCharging: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    data class BatteryLow(
        val level: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    data class PhoneUnlocked(
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    data class ConnectivityChanged(
        val isOnline: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    data class ScreenshotTaken(
        val path: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    /** A task the user asked Miko to perform. */
    data class UserTask(
        val instruction: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    /** A memory Miko learned about the user. */
    data class MemoryLearned(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    /** An incoming call was missed. */
    data class MissedCall(
        val number: String,
        val contactName: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    /** An SMS was received. */
    data class SmsReceived(
        val sender: String,
        val body: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    /** An email notification arrived (derived from the notification stream). */
    data class EmailReceived(
        val packageName: String,
        val subject: String,
        val preview: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    /** A calendar/reminder-style notification arrived. */
    data class CalendarNotification(
        val title: String,
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    /** Escape hatch for anything not modelled above. */
    data class Custom(
        val type: String,
        val title: String,
        val detail: String = "",
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()

    data class ConversationCompleted(
        val summary: String,
        val transcript: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MikoEvent()
}
