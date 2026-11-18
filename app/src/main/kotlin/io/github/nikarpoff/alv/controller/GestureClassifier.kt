package io.github.nikarpoff.alv.controller

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Recognizes the full-screen gestures of MOB-3 from touch events with times.
 *
 * The Android layer passes `MotionEvent` coordinates in pixels and `eventTime`
 * in milliseconds, and calls [onTimer] at [holdDeadlineMs] while the finger stays
 * still: a hold must be recognized without waiting for a move or the release.
 *
 * A tap is reported at once on release, without waiting for a possible second
 * tap. A second touch within [DOUBLE_TAP_TIMEOUT_MS] after the release of a tap
 * is reported as [Gesture.DOUBLE_TAP] on its `ACTION_DOWN`: the controller cancels
 * the description the tap started (`cancel`) and the rest of that touch is ignored.
 *
 * @param pxPerDp screen density, pixels in one dp
 */
class GestureClassifier(private val pxPerDp: Float) {
    /** Recognized gesture and the event of the automaton KM-2 it becomes. */
    enum class Gesture {
        /** Tap: E2. */
        TAP,

        /** Double tap: `cancel` of the started request and E12. */
        DOUBLE_TAP,

        /** Hold started: E10. */
        HOLD_START,

        /** Hold ended: E7 or E11, decided by [SpeechEndpointer]. */
        HOLD_END,

        /** Swipe up: E14. */
        SWIPE_UP,

        /** Swipe down: E15. */
        SWIPE_DOWN,
    }

    private var touch: Touch? = null
    private var lastTapUpMs: Long? = null

    /** Time to call [onTimer] at, or `null` when no hold is pending. */
    val holdDeadlineMs: Long?
        get() = touch?.takeIf { it.state == TouchState.PENDING }?.let { it.downMs + HOLD_MS }

    /** `ACTION_DOWN`. Returns [Gesture.DOUBLE_TAP] when the touch follows a tap closely enough. */
    fun onDown(x: Float, y: Float, timeMs: Long): Gesture? {
        val tapUp = lastTapUpMs
        lastTapUpMs = null
        if (tapUp != null && timeMs - tapUp <= DOUBLE_TAP_TIMEOUT_MS) {
            touch = Touch(x, y, timeMs, TouchState.CONSUMED)
            return Gesture.DOUBLE_TAP
        }
        touch = Touch(x, y, timeMs, TouchState.PENDING)
        return null
    }

    /** `ACTION_MOVE`. Returns a swipe or a hold once they are recognized. */
    fun onMove(x: Float, y: Float, timeMs: Long): Gesture? {
        val current = touch ?: return null
        if (current.state != TouchState.PENDING && current.state != TouchState.MOVED) return null

        swipe(current, y)?.let {
            current.state = TouchState.CONSUMED
            return it
        }
        if (current.state == TouchState.MOVED) return null
        if (distanceDp(current, x, y) >= TAP_SLOP_DP) {
            // Moved too far for a tap or a hold, but not yet a swipe
            current.state = TouchState.MOVED
            return null
        }
        return onTimer(timeMs)
    }

    /** Timer at [holdDeadlineMs]: returns [Gesture.HOLD_START] when the finger is still down and still. */
    fun onTimer(timeMs: Long): Gesture? {
        val current = touch ?: return null
        if (current.state != TouchState.PENDING || timeMs - current.downMs < HOLD_MS) return null
        current.state = TouchState.HOLDING
        return Gesture.HOLD_START
    }

    /** `ACTION_UP`. Returns a tap, the end of a hold or a swipe not yet reported. */
    fun onUp(x: Float, y: Float, timeMs: Long): Gesture? {
        val current = touch ?: return null
        touch = null
        return when (current.state) {
            TouchState.HOLDING -> Gesture.HOLD_END
            TouchState.CONSUMED -> null
            TouchState.PENDING, TouchState.MOVED -> {
                swipe(current, y)?.let { return it }
                if (current.state == TouchState.PENDING && timeMs - current.downMs < HOLD_MS &&
                    distanceDp(current, x, y) < TAP_SLOP_DP
                ) {
                    lastTapUpMs = timeMs
                    Gesture.TAP
                } else {
                    // A late release without the timer is a hold too short to start recording
                    null
                }
            }
        }
    }

    /** `ACTION_CANCEL`. Ends a started hold so that the recording stops. */
    fun onCancel(): Gesture? {
        val current = touch ?: return null
        touch = null
        return if (current.state == TouchState.HOLDING) Gesture.HOLD_END else null
    }

    private fun swipe(current: Touch, y: Float): Gesture? {
        val dyDp = (y - current.downY) / pxPerDp
        return when {
            dyDp < -SWIPE_DP -> Gesture.SWIPE_UP
            dyDp > SWIPE_DP -> Gesture.SWIPE_DOWN
            else -> null
        }
    }

    private fun distanceDp(current: Touch, x: Float, y: Float): Float =
        hypot(abs(x - current.downX), abs(y - current.downY)) / pxPerDp

    private enum class TouchState {
        /** Not yet recognized. */
        PENDING,

        /** Moved beyond the tap slop: can still become a swipe. */
        MOVED,

        /** Hold started: the release ends it. */
        HOLDING,

        /** Already reported: the rest of the touch is ignored. */
        CONSUMED,
    }

    private class Touch(val downX: Float, val downY: Float, val downMs: Long, var state: TouchState)

    companion object {
        /** A tap is released earlier, a hold lasts at least this long, ms. */
        const val HOLD_MS = 400L

        /** Largest time from the release of a tap to the second touch of a double tap, ms. */
        const val DOUBLE_TAP_TIMEOUT_MS = 300L

        /** A tap moves less, dp. */
        const val TAP_SLOP_DP = 24f

        /** A swipe moves vertically more, dp. */
        const val SWIPE_DP = 120f
    }
}
