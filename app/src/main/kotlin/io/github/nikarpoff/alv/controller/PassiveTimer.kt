package io.github.nikarpoff.alv.controller

/**
 * Timer of the passive mode: E1 of KM-2 (MOB-14).
 *
 * Runs while [update] says it is active - the automaton is in S1 with the passive mode on and the
 * app is on the screen and connected. Each entry into S1 arms it anew, so the next snapshot is taken
 * [intervalMs] after the previous passive request ended, never earlier.
 *
 * @param onFire called on the loop when the interval passed
 */
class PassiveTimer(private val loop: Loop, private val onFire: () -> Unit) {
    /** `passive_interval_ms` from `welcome`. */
    var intervalMs: Long = DEFAULT_INTERVAL_MS

    private var task: Cancellable? = null

    /** The timer is armed. */
    val armed: Boolean get() = task != null

    /** Arms the timer when [active] and it is not armed yet; disarms it otherwise. */
    fun update(active: Boolean) {
        if (!active) {
            task?.cancel()
            task = null
            return
        }
        if (task != null) return
        task = loop.schedule(intervalMs) {
            task = null
            onFire()
        }
    }

    companion object {
        /** Interval before the first `welcome`, ms. */
        const val DEFAULT_INTERVAL_MS = 3_000L
    }
}
