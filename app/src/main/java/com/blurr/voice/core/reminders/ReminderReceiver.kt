package com.blurr.voice.core.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blurr.voice.MainActivity
import com.blurr.voice.R

/**
 * Fires when a scheduled [Reminder] is due and posts it as a notification. Tapping the
 * notification opens Miko. Registered in the manifest.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ReminderService.EXTRA_ID, 0)
        val text = intent.getStringExtra(ReminderService.EXTRA_TEXT).orEmpty()
        if (text.isBlank()) return

        ensureChannel(context)

        val tapIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_miko_mascot)
            .setContentTitle("Miko reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Miko Reminders", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Reminders Miko sets for you" }
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "miko_reminders"
    }
}
