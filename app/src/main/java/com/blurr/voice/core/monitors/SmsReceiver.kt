package com.blurr.voice.core.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent

/**
 * Emits [MikoEvent.SmsReceived] when a text message arrives, so incoming messages show on the
 * timeline and can trigger automation. Requires the RECEIVE_SMS permission.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (messages.isEmpty()) return
            val sender = messages.first().displayOriginatingAddress ?: "Unknown"
            val body = messages.joinToString("") { it.messageBody ?: "" }
            if (body.isNotBlank()) {
                EventBus.publish(MikoEvent.SmsReceived(sender = sender, body = body))
                Log.d(TAG, "SMS received from $sender")
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMS parse failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
