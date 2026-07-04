package com.blurr.voice.core.events

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Application-wide, loosely-coupled event stream.
 *
 * Producers (notification listener, receivers, services) call [publish];
 * consumers ([com.blurr.voice.core.timeline.TimelineRepository], future Planner)
 * collect [events]. Backed by a [MutableSharedFlow] with a replay buffer so late
 * subscribers still see the most recent events.
 */
object EventBus {

    private const val TAG = "MikoEventBus"

    private val _events = MutableSharedFlow<MikoEvent>(
        replay = 16,
        extraBufferCapacity = 128
    )
    val events: SharedFlow<MikoEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Non-blocking publish. Safe to call from any thread / receiver. */
    fun publish(event: MikoEvent) {
        if (!_events.tryEmit(event)) {
            // Buffer full — fall back to a suspending emit so nothing is lost.
            scope.launch { _events.emit(event) }
        }
        if (com.blurr.voice.BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "event: ${event::class.simpleName}")
        }
    }
}
