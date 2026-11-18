package io.github.nikarpoff.alv.controller

import io.github.nikarpoff.alv.controller.SpeechEndpointer.Output
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndpointerTest {
    private val endpointer = SpeechEndpointer(listenWindowMs = 5_000, maxQuestionMs = 15_000)

    /** 100 ms chunk at 16 kHz; every sample carries [mark] to trace it in the output. */
    private fun chunk(mark: Int) = ShortArray(CHUNK_SAMPLES) { mark.toShort() }

    private fun feed(count: Int, speech: Boolean, firstMark: Int = 0): List<Output> =
        (0 until count).flatMap { endpointer.feed(chunk(firstMark + it), speech) }

    private fun audioSamples(outputs: List<Output>): Int = outputs.sumOf {
        when (it) {
            is Output.SpeechStarted -> it.preroll.size
            is Output.Audio -> it.pcm.size
            else -> 0
        }
    }

    @Test
    fun speechStartCarries300MsBeforeDetector() {
        endpointer.startWindow()
        assertTrue(feed(10, speech = false, firstMark = 1).isEmpty())

        val outputs = endpointer.feed(chunk(100), speech = true)

        val started = outputs[0] as Output.SpeechStarted
        // Chunks 8, 9 and 10: exactly 300 ms before the detector fired
        assertEquals(3 * CHUNK_SAMPLES, started.preroll.size)
        assertEquals(8.toShort(), started.preroll.first())
        assertEquals(10.toShort(), started.preroll.last())
        assertArrayEquals(chunk(100), (outputs[1] as Output.Audio).pcm)
    }

    @Test
    fun speechAtOnceHasShortPreroll() {
        endpointer.startWindow()
        endpointer.feed(chunk(1), speech = false)

        val started = endpointer.feed(chunk(2), speech = true)[0] as Output.SpeechStarted

        assertEquals(CHUNK_SAMPLES, started.preroll.size)
    }

    @Test
    fun silenceOf700MsEndsQuestion() {
        endpointer.startWindow()
        feed(5, speech = true)
        assertTrue(feed(6, speech = false).none { it is Output.QuestionEnded })

        val outputs = endpointer.feed(chunk(0), speech = false)

        assertEquals(Output.QuestionEnded(forced = false), outputs.last())
        // The endpointer is idle after the event
        assertTrue(endpointer.feed(chunk(0), speech = true).isEmpty())
    }

    @Test
    fun speechResetsSilence() {
        endpointer.startWindow()
        feed(3, speech = true)
        feed(6, speech = false)
        feed(1, speech = true)

        assertTrue(feed(6, speech = false).none { it is Output.QuestionEnded })
        assertEquals(Output.QuestionEnded(forced = false), feed(1, speech = false).last())
    }

    @Test
    fun windowOf5SecondsWithoutSpeechGivesTimeout() {
        endpointer.startWindow()
        assertTrue(feed(49, speech = false).isEmpty())

        assertEquals(listOf(Output.ListenTimeout), endpointer.feed(chunk(0), speech = false))
    }

    @Test
    fun windowFollowsServerConfig() {
        val short = SpeechEndpointer(listenWindowMs = 2_000)
        short.startWindow()
        repeat(19) { assertTrue(short.feed(chunk(0), speech = false).isEmpty()) }

        assertEquals(listOf(Output.ListenTimeout), short.feed(chunk(0), speech = false))
    }

    @Test
    fun speechJustBeforeWindowEndStartsQuestion() {
        endpointer.startWindow()
        feed(49, speech = false)

        assertTrue(endpointer.feed(chunk(0), speech = true)[0] is Output.SpeechStarted)
    }

    @Test
    fun questionLimitEndsQuestionByForce() {
        endpointer.startWindow()
        feed(10, speech = false)

        val outputs = feed(200, speech = true)

        assertEquals(Output.QuestionEnded(forced = true), outputs.last())
        assertEquals(1, outputs.count { it is Output.QuestionEnded })
        // The preroll counts too: not a sample more than max_question_ms goes out
        assertEquals(15_000 * SAMPLES_PER_MS, audioSamples(outputs))
    }

    @Test
    fun holdSendsAllAudioAndGivesQuestionAfterSpeech() {
        endpointer.startHold()
        val outputs = feed(2, speech = false) + feed(4, speech = true) + feed(3, speech = false)

        assertEquals(9, outputs.count { it is Output.Audio })
        assertEquals(Output.QuestionEnded(forced = false), endpointer.release())
    }

    @Test
    fun holdWithSpeechOf300MsOrLessGivesNoSpeech() {
        endpointer.startHold()
        feed(3, speech = true)
        feed(5, speech = false)

        assertEquals(Output.NoSpeech, endpointer.release())
    }

    @Test
    fun holdWithSilenceGivesNoSpeech() {
        endpointer.startHold()
        feed(20, speech = false)

        assertEquals(Output.NoSpeech, endpointer.release())
    }

    @Test
    fun holdIsNotEndedBySilence() {
        endpointer.startHold()
        feed(5, speech = true)

        assertTrue(feed(30, speech = false).none { it is Output.QuestionEnded })
    }

    @Test
    fun holdLimitEndsQuestionByForce() {
        endpointer.startHold()

        val outputs = feed(151, speech = true)

        assertEquals(Output.QuestionEnded(forced = true), outputs.last())
        assertEquals(15_000 * SAMPLES_PER_MS, audioSamples(outputs))
        assertNull(endpointer.release())
    }

    @Test
    fun stopDropsEverything() {
        endpointer.startWindow()
        feed(3, speech = true)
        endpointer.stop()

        assertTrue(feed(60, speech = false).isEmpty())
    }

    private companion object {
        const val SAMPLES_PER_MS = 16
        const val CHUNK_SAMPLES = 100 * SAMPLES_PER_MS
    }
}
