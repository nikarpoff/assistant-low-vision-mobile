package io.github.nikarpoff.alv.model

/**
 * Moment the answer starts to sound, by the playback position (MOB-7, PLT-14).
 *
 * Writing a chunk into the buffer of the track is not the start: the sound comes out later.
 * The start is found from a position report of the track - a frame number and the time it was
 * presented (`AudioTrack.getTimestamp`, or the playback head read at a known time) - and the
 * sample rate: start = report time - (report frame - first frame of the answer) / rate.
 *
 * The track is created once and plays several answers, so frame numbers count from the start
 * of the track; [answerStarts] tells which frame begins the answer.
 *
 * All times are nanoseconds of one monotonic clock.
 *
 * @param sampleRate sample rate of the track in Hz, from `answer.start`
 */
class PlaybackClock(private val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "sample rate must be positive, got $sampleRate" }
    }

    private var firstFrame: Long? = null

    /** Moment the first frame of the answer started to sound, `null` until a report reaches it. */
    var startNanos: Long? = null
        private set

    /**
     * The first chunk of the answer is about to be written to the track.
     *
     * @param framesWritten frames written to the track before the chunk: the first frame of the answer
     */
    fun answerStarts(framesWritten: Long) {
        require(framesWritten >= 0) { "frames written must not be negative, got $framesWritten" }
        firstFrame = framesWritten
        startNanos = null
    }

    /**
     * Position report of the track.
     *
     * @param framePosition frame presented at [nanoTime], counted from the start of the track
     * @param nanoTime moment the frame was presented
     * @return the start of the answer once known; it is fixed by the first report that reaches the answer
     */
    fun onPosition(framePosition: Long, nanoTime: Long): Long? {
        val first = firstFrame ?: return null
        if (startNanos == null && framePosition >= first) {
            startNanos = nanoTime - framesToNanos(framePosition - first)
        }
        return startNanos
    }

    /** Forgets the answer: nothing is playing. */
    fun reset() {
        firstFrame = null
        startNanos = null
    }

    private fun framesToNanos(frames: Long): Long = frames * NANOS_PER_SECOND / sampleRate

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
