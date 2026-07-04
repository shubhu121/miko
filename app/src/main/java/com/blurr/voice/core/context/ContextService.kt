package com.blurr.voice.core.context

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.blurr.voice.ScreenInteractionService
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent
import java.util.Calendar

/**
 * The Context Collection Layer.
 *
 * Exposes an on-demand [snapshot] of normalized device context and, once [start]ed,
 * registers OS receivers/listeners that translate device signals into [MikoEvent]s on
 * the [EventBus]. Deliberately singleton + interface-free to fit Miko's existing
 * manual-wiring style while staying loosely coupled (it only emits events).
 */
class ContextService private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "ContextService"

        @Volatile private var INSTANCE: ContextService? = null
        fun getInstance(context: Context): ContextService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContextService(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val clipboard by lazy {
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private var started = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED ->
                    EventBus.publish(MikoEvent.ChargingChanged(isCharging = true))
                Intent.ACTION_POWER_DISCONNECTED ->
                    EventBus.publish(MikoEvent.ChargingChanged(isCharging = false))
                Intent.ACTION_BATTERY_LOW ->
                    EventBus.publish(MikoEvent.BatteryLow(level = currentBatteryLevel()))
                Intent.ACTION_USER_PRESENT ->
                    EventBus.publish(MikoEvent.PhoneUnlocked())
            }
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
            if (!text.isNullOrBlank()) EventBus.publish(MikoEvent.ClipboardChanged(text))
        } catch (e: Exception) {
            Log.w(TAG, "clipboard read failed: ${e.message}")
        }
    }

    private val connectivityManager by lazy {
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) =
            EventBus.publish(MikoEvent.ConnectivityChanged(isOnline = true))

        override fun onLost(network: Network) =
            EventBus.publish(MikoEvent.ConnectivityChanged(isOnline = false))
    }

    /** Watches MediaStore for newly-saved screenshots (best-effort; needs media read perm). */
    private val screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri == null) return
            try {
                appContext.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATA),
                    null, null, null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else ""
                    if (name.contains("screenshot", true) || path.contains("screenshot", true)) {
                        EventBus.publish(MikoEvent.ScreenshotTaken(path.ifBlank { uri.toString() }))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "screenshot observe failed: ${e.message}")
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            appContext.registerReceiver(powerReceiver, filter)
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Log.w(TAG, "network callback register failed: ${e.message}")
            }
            try {
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver
                )
            } catch (e: Exception) {
                Log.w(TAG, "screenshot observer register failed: ${e.message}")
            }
            Log.d(TAG, "Context collection started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start context collection", e)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            appContext.unregisterReceiver(powerReceiver)
            clipboard.removePrimaryClipChangedListener(clipboardListener)
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            runCatching { appContext.contentResolver.unregisterContentObserver(screenshotObserver) }
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
        }
    }

    /** Builds a fresh, normalized context snapshot. */
    fun snapshot(): ContextSnapshot {
        return ContextSnapshot(
            timestamp = System.currentTimeMillis(),
            foregroundApp = foregroundApp(),
            batteryLevel = currentBatteryLevel(),
            isCharging = isCharging(),
            isOnline = isOnline(),
            clipboardText = clipboardText(),
            timeOfDay = timeOfDay(),
            installedAppCount = installedAppCount()
        )
    }

    private fun foregroundApp(): String? = try {
        ScreenInteractionService.instance?.getForegroundAppPackageName()
    } catch (e: Exception) {
        null
    }

    private fun currentBatteryLevel(): Int = try {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) {
        -1
    }

    private fun isCharging(): Boolean = try {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.isCharging
    } catch (e: Exception) {
        false
    }

    private fun isOnline(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        false
    }

    private fun clipboardText(): String? = try {
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
    } catch (e: Exception) {
        null
    }

    private fun installedAppCount(): Int = try {
        appContext.packageManager.getInstalledApplications(0).size
    } catch (e: Exception) {
        0
    }

    private fun timeOfDay(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
    }
}
