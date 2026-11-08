package io.github.nikarpoff.alv.controller

import kotlin.random.Random

/**
 * Pauses between reconnection attempts (MOB-8).
 *
 * The nominal pause grows 0.5 → 1 → 2 → 4 → 8 s and stays at 8 s; each pause gets
 * a random spread of ±20 %, and no pause is longer than 8 s. A successful connection
 * starts the sequence over.
 *
 * @param random source of the spread; tests pass a fixed one
 */
class ReconnectPolicy(private val random: Random = Random.Default) {
    private var attempt = 0

    /** Pause before the next attempt, ms. Every call counts as a failed attempt. */
    fun nextDelayMs(): Long {
        val nominal = (BASE_DELAY_MS shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_DELAY_MS)
        attempt++
        val spread = 1.0 + JITTER * (2 * random.nextDouble() - 1)
        return (nominal * spread).toLong().coerceAtMost(MAX_DELAY_MS)
    }

    /** The connection is open: the next pause is the shortest again. */
    fun onConnected() {
        attempt = 0
    }

    companion object {
        /** First pause, ms. */
        const val BASE_DELAY_MS = 500L

        /** Longest pause, ms. */
        const val MAX_DELAY_MS = 8_000L

        /** Random spread, share of the pause. */
        const val JITTER = 0.2

        // 500 ms shl 4 = 8 s; a larger shift only overflows
        private const val MAX_SHIFT = 4
    }
}
