package io.github.nikarpoff.alv.model

import io.github.nikarpoff.alv.model.protocol.ClientMetricValues
import io.github.nikarpoff.alv.model.protocol.ClientMetrics
import io.github.nikarpoff.alv.model.protocol.Network
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Collects the phone metrics of one request and builds `client.metrics` (MOB-13, PROTO-4, PLT-14).
 *
 * The controllers report the moments as they happen; the builder picks the trigger by the kind
 * of the request (PLT-14) and decides whether the message is sent:
 * after the answer was played and after an error - yes, after a cancel and after `passive.clear` - no.
 * A metric whose moments were not reported, such as the first audio after an error, is not sent.
 *
 * All moments are nanoseconds of one monotonic clock; metrics are whole milliseconds.
 *
 * @param requestId request the metrics belong to
 * @param kind kind of the request, it chooses the trigger
 */
class ClientMetricsBuilder(
    val requestId: UUID,
    val kind: RequestKind,
) {
    /** Kind of the request and its trigger (PLT-14). */
    enum class RequestKind {
        /** `describe` by a tap: the trigger is the touch (`ACTION_DOWN` event time). */
        DESCRIBE_ON_DEMAND,

        /** Passive `describe`: the trigger is the snapshot taken. */
        DESCRIBE_PASSIVE,

        /** `ask`: the trigger is the end of speech (E7). */
        ASK,
    }

    /** How the request ended. */
    enum class Outcome {
        /** The answer was played. */
        PLAYED,

        /** The request failed (E13). */
        ERROR,

        /** The request was cancelled. */
        CANCELLED,

        /** The passive request ended with `passive.clear`. */
        PASSIVE_CLEAR,
    }

    private var touchDown: Long? = null
    private var speechEnd: Long? = null
    private var captureRequested: Long? = null
    private var frameCaptured: Long? = null
    private var jpegEncoded: Long? = null
    private var inputSent: Long? = null
    private var accepted: Long? = null
    private var firstAudio: Long? = null
    private var underruns: Int = 0
    private var finished = false

    /** Touch down of the tap (`MotionEvent.getEventTime` of `ACTION_DOWN`). */
    fun touchDown(nanos: Long) = apply { touchDown = nanos }

    /** End of speech of the question (E7). */
    fun speechEnded(nanos: Long) = apply { speechEnd = nanos }

    /** Command to take the frame. */
    fun captureRequested(nanos: Long) = apply { captureRequested = nanos }

    /** Frame is in memory. */
    fun frameCaptured(nanos: Long) = apply { frameCaptured = nanos }

    /** JPEG is ready. */
    fun jpegEncoded(nanos: Long) = apply { jpegEncoded = nanos }

    /** Snapshot (`describe`) or `ask.end` (`ask`) is handed to the socket. */
    fun inputSent(nanos: Long) = apply { inputSent = nanos }

    /** `accepted` is received. */
    fun accepted(nanos: Long) = apply { accepted = nanos }

    /** First audio chunk started to sound, by the playback position ([PlaybackClock]). */
    fun firstAudioStarted(nanos: Long) = apply { firstAudio = nanos }

    /** Number of playback buffer underruns. */
    fun playbackUnderruns(count: Int) = apply {
        require(count >= 0) { "underruns must not be negative, got $count" }
        underruns = count
    }

    /** Trigger of the request by its kind, `null` until reported. */
    val trigger: Long?
        get() = when (kind) {
            RequestKind.DESCRIBE_ON_DEMAND -> touchDown
            RequestKind.DESCRIBE_PASSIVE -> frameCaptured
            RequestKind.ASK -> speechEnd
        }

    /**
     * Ends the request.
     *
     * @return `client.metrics` after [Outcome.PLAYED] and [Outcome.ERROR], `null` after
     * [Outcome.CANCELLED] and [Outcome.PASSIVE_CLEAR] and on every call after the first
     */
    fun finish(outcome: Outcome, network: Network): ClientMetrics? {
        if (finished) return null
        finished = true
        if (outcome == Outcome.CANCELLED || outcome == Outcome.PASSIVE_CLEAR) return null

        val trigger = trigger
        val values = ClientMetricValues(
            captureMs = millis(captureRequested, frameCaptured),
            encodeMs = millis(frameCaptured, jpegEncoded),
            triggerToSendMs = millis(trigger, inputSent),
            triggerToAcceptedMs = millis(trigger, accepted),
            triggerToFirstAudioMs = millis(trigger, firstAudio),
            playbackUnderruns = underruns,
        )
        return ClientMetrics(requestId = requestId, metrics = values, network = network)
    }

    private fun millis(from: Long?, to: Long?): Long? {
        if (from == null || to == null) return null
        return ((to - from) / NANOS_PER_MILLI).roundToLong().coerceAtLeast(0)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
