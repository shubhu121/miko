package com.blurr.voice.core.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent

/**
 * Detects missed calls from the system phone-state broadcast: a call that RANG and then went
 * IDLE without ever being answered (OFFHOOK) is a miss. Emits [MikoEvent.MissedCall] so it
 * lands on the timeline, feeds the graph, and can drive automation rules.
 *
 * The incoming number is only available with READ_CALL_LOG on newer Android; without it we
 * still detect the miss (number = "Unknown").
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (number != null) lastNumber = number

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> lastState = RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> lastState = OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState == RINGING) {
                    // Rang, then hung up without being answered → missed.
                    try {
                        EventBus.publish(MikoEvent.MissedCall(number = lastNumber ?: "Unknown"))
                        Log.d(TAG, "Missed call detected.")
                    } catch (e: Exception) {
                        Log.w(TAG, "publish missed call failed: ${e.message}")
                    }
                }
                lastState = IDLE
                lastNumber = null
            }
        }
    }

    companion object {
        private const val TAG = "CallStateReceiver"
        private const val IDLE = 0
        private const val RINGING = 1
        private const val OFFHOOK = 2

        // Static so state survives across separate broadcast deliveries.
        @Volatile private var lastState = IDLE
        @Volatile private var lastNumber: String? = null
    }
}
