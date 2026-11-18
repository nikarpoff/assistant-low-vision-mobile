package io.github.nikarpoff.alv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackClockTest {
    private val ms = 1_000_000L

    @Test
    fun startIsPositionMinusPlayedFramesAtRate() {
        val clock = PlaybackClock(sampleRate = 24_000)
        clock.answerStarts(framesWritten = 0)

        // 2400 frames at 24 kHz are 100 ms: the first frame sounded 100 ms before the report.
        val start = clock.onPosition(framePosition = 2_400, nanoTime = 1_000 * ms)

        assertEquals(900 * ms, start)
        assertEquals(900 * ms, clock.startNanos)
    }

    @Test
    fun startDependsOnRate() {
        val clock = PlaybackClock(sampleRate = 16_000)
        clock.answerStarts(framesWritten = 0)

        assertEquals(850 * ms, clock.onPosition(framePosition = 2_400, nanoTime = 1_000 * ms))
    }

    @Test
    fun startIsNotTheMomentOfWriting() {
        val clock = PlaybackClock(sampleRate = 24_000)
        // The chunk goes to the buffer at 500 ms: writing gives no start.
        clock.answerStarts(framesWritten = 0)
        assertNull(clock.startNanos)

        // The track reports frame 1200 at 600 ms: the answer started at 550 ms, 50 ms after the write.
        assertEquals(550 * ms, clock.onPosition(framePosition = 1_200, nanoTime = 600 * ms))
    }

    @Test
    fun answerStartsAfterFramesOfEarlierAnswers() {
        val clock = PlaybackClock(sampleRate = 24_000)
        clock.answerStarts(framesWritten = 48_000)

        // The track is still playing the previous answer: the start is unknown.
        assertNull(clock.onPosition(framePosition = 47_000, nanoTime = 2_000 * ms))

        // Frame 50400 is 2400 frames (100 ms) into the answer.
        assertEquals(3_000 * ms, clock.onPosition(framePosition = 50_400, nanoTime = 3_100 * ms))
    }

    @Test
    fun firstReportFixesTheStart() {
        val clock = PlaybackClock(sampleRate = 24_000)
        clock.answerStarts(framesWritten = 0)

        val first = clock.onPosition(framePosition = 240, nanoTime = 110 * ms)
        // A later report with a stall (underrun) would give a later start; the start stays.
        val later = clock.onPosition(framePosition = 2_400, nanoTime = 400 * ms)

        assertEquals(100 * ms, first)
        assertEquals(first, later)
    }

    @Test
    fun noAnswerNoStart() {
        val clock = PlaybackClock(sampleRate = 24_000)

        assertNull(clock.onPosition(framePosition = 2_400, nanoTime = 1_000 * ms))

        clock.answerStarts(0)
        clock.onPosition(framePosition = 2_400, nanoTime = 1_000 * ms)
        clock.reset()
        assertNull(clock.startNanos)
        assertNull(clock.onPosition(framePosition = 4_800, nanoTime = 1_100 * ms))
    }

    @Test
    fun newAnswerForgetsOldStart() {
        val clock = PlaybackClock(sampleRate = 24_000)
        clock.answerStarts(0)
        clock.onPosition(framePosition = 2_400, nanoTime = 1_000 * ms)

        clock.answerStarts(framesWritten = 24_000)

        assertNull(clock.startNanos)
        assertEquals(2_000 * ms, clock.onPosition(framePosition = 24_000, nanoTime = 2_000 * ms))
    }
}
