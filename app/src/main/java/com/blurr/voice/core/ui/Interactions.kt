package com.blurr.voice.core.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import com.blurr.voice.R
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView

/**
 * Miko's shared interaction & motion vocabulary — the small details that make the app feel
 * hand-crafted rather than generic Android. iOS leans on: precise easing, a gentle spring on
 * release, a light haptic tick on touch-down, and controls that "give" slightly when pressed.
 */
object Motion {
    /** iOS default ease-in-out (matches UIView animation curve ~ (0.25,0.1,0.25,1)). */
    val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    /** A subtle spring for release/settle — a touch of overshoot, no bounce circus. */
    val SPRING = PathInterpolator(0.2f, 0.9f, 0.3f, 1.2f)

    /** iOS "emphasized" curve for larger transitions. */
    val EMPHASIZED = PathInterpolator(0.3f, 0.0f, 0.0f, 1f)

    const val PRESS_DOWN_MS = 90L
    const val PRESS_UP_MS = 220L
}

/**
 * Makes a tappable view feel physical: scales down with a light haptic on press, springs back
 * on release. Returns false so any existing OnClickListener still fires normally.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(pressedScale: Float = 0.96f, haptics: Boolean = true) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (haptics) v.performHapticFeedback(
                    HapticFeedbackConstants.CONTEXT_CLICK,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
                v.animate().scaleX(pressedScale).scaleY(pressedScale)
                    .setDuration(Motion.PRESS_DOWN_MS).setInterpolator(Motion.EASE).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(Motion.PRESS_UP_MS).setInterpolator(Motion.SPRING).start()
            }
        }
        false
    }
}

/** A light haptic tick — use for selection/tab changes. */
fun View.tick() = performHapticFeedback(
    HapticFeedbackConstants.CLOCK_TICK,
    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
)

/** iOS-style push: start [intent] with a slide-in-from-right transition. */
@Suppress("DEPRECATION")
fun Activity.pushActivity(intent: Intent) {
    startActivity(intent)
    overridePendingTransition(R.anim.ios_push_enter, R.anim.ios_push_exit)
}

/** iOS-style back: finish with a slide-out-to-right transition. */
@Suppress("DEPRECATION")
fun Activity.finishWithPop() {
    finish()
    overridePendingTransition(R.anim.ios_pop_enter, R.anim.ios_pop_exit)
}

/**
 * Fades + rises a view into place with iOS timing. Used for hero elements (summary card,
 * greeting) so the screen assembles itself instead of snapping in.
 */
fun View.entrance(delayMs: Long = 0L, riseDp: Float = 14f) {
    val rise = riseDp * resources.displayMetrics.density
    alpha = 0f
    translationY = rise
    animate().alpha(1f).translationY(0f)
        .setStartDelay(delayMs)
        .setDuration(460)
        .setInterpolator(Motion.EMPHASIZED)
        .start()
}

/**
 * Staggers the entrance of a RecyclerView's visible items as they bind — each row rises and
 * fades slightly after the previous, the signature iOS list reveal.
 */
fun RecyclerView.ViewHolder.staggerIn(position: Int) {
    val v = itemView
    val rise = 18f * v.resources.displayMetrics.density
    v.alpha = 0f
    v.translationY = rise
    v.animate().alpha(1f).translationY(0f)
        .setStartDelay((position.coerceAtMost(8) * 40L))
        .setDuration(420)
        .setInterpolator(Motion.EMPHASIZED)
        .start()
}
