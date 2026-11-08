package io.github.nikarpoff.alv.controller

/**
 * Start and end of the question in the microphone stream (MOB-6).
 *
 * The microphone layer feeds PCM 16 kHz mono chunks together with the verdict of
 * the voice activity detector, and the endpointer answers what to send and which
 * event of the automaton KM-2 happened. Time is counted in samples, so the class
 * needs no clock and works the same on synthetic sequences.
 *
 * Two modes:
 * - window after an answer ([startWindow]): the speech has [listenWindowMs] to start
 *   (otherwise [Output.ListenTimeout], E9); at its start [Output.SpeechStarted] carries
 *   [PREROLL_MS] of audio before the detector fired, so the first syllable is not lost;
 *   [END_SILENCE_MS] of silence ends the question ([Output.QuestionEnded], E7);
 * - hold ([startHold]): `ask.start` and the snapshot are sent when the hold begins,
 *   all audio is sent while the finger is down; [release] gives E7 after speech longer
 *   than [MIN_SPEECH_MS] and [Output.NoSpeech] (E11) otherwise.
 *
 * In both modes the question is ended by force ([Output.QuestionEnded] with `forced`)
 * once the audio sent reaches [maxQuestionMs]: the gateway rejects longer questions.
 *
 * @param listenWindowMs `listen_window_ms` from `welcome`
 * @param maxQuestionMs `max_question_ms` from `welcome`
 * @param sampleRate microphone sample rate, Hz
 */
class SpeechEndpointer(
    private val listenWindowMs: Long = 5_000,
    private val maxQuestionMs: Long = 15_000,
    private val sampleRate: Int = 16_000,
) {
    /** What the microphone layer does next. */
    sealed interface Output {
        /** Speech started in the window: send `ask.start`, the snapshot, then [preroll] as audio. */
        class SpeechStarted(val preroll: ShortArray) : Output

        /** Audio of the question to send as AUDIO_IN. */
        class Audio(val pcm: ShortArray) : Output

        /** The question is asked: E7. [forced] when it hit `max_question_ms`. */
        data class QuestionEnded(val forced: Boolean) : Output

        /** The window passed without speech: E9. */
        data object ListenTimeout : Output

        /** The hold ended without speech: E11. */
        data object NoSpeech : Output
    }

    private enum class Mode { IDLE, WINDOW, SPEECH, HOLD }

    private var mode = Mode.IDLE
    private val preroll = ShortArray(samples(PREROLL_MS).toInt())
    private var prerollSize = 0
    private var prerollStart = 0
    private var elapsedSamples = 0L
    private var sentSamples = 0L
    private var silenceSamples = 0L
    private var speechSamples = 0L

    /** Opens the window after an answer (S5). */
    fun startWindow() {
        reset(Mode.WINDOW)
    }

    /** Starts the hold: `ask.start` is already sent, every chunk goes out. */
    fun startHold() {
        reset(Mode.HOLD)
    }

    /** Stops listening without an event, e.g. after `cancel`. */
    fun stop() {
        reset(Mode.IDLE)
    }

    /** Next microphone chunk and whether the detector heard speech in it. */
    fun feed(pcm: ShortArray, speech: Boolean): List<Output> = when (mode) {
        Mode.IDLE -> emptyList()
        Mode.WINDOW -> feedWindow(pcm, speech)
        Mode.SPEECH -> feedSpeech(pcm, speech)
        Mode.HOLD -> feedHold(pcm, speech)
    }

    /** Release of the hold: E7 after speech longer than [MIN_SPEECH_MS], E11 otherwise. */
    fun release(): Output? {
        if (mode != Mode.HOLD) return null
        val spoke = speechSamples > samples(MIN_SPEECH_MS)
        reset(Mode.IDLE)
        return if (spoke) Output.QuestionEnded(forced = false) else Output.NoSpeech
    }

    private fun feedWindow(pcm: ShortArray, speech: Boolean): List<Output> {
        if (!speech) {
            elapsedSamples += pcm.size
            remember(pcm)
            if (elapsedSamples >= samples(listenWindowMs)) {
                reset(Mode.IDLE)
                return listOf(Output.ListenTimeout)
            }
            return emptyList()
        }
        val before = prerollCopy()
        reset(Mode.SPEECH)
        val out = mutableListOf<Output>()
        val head = budget(before)
        out += Output.SpeechStarted(head)
        sentSamples += head.size
        if (sentSamples >= samples(maxQuestionMs)) {
            reset(Mode.IDLE)
            return out + Output.QuestionEnded(forced = true)
        }
        return out + feedSpeech(pcm, speech = true)
    }

    private fun feedSpeech(pcm: ShortArray, speech: Boolean): List<Output> {
        val out = mutableListOf<Output>()
        val part = budget(pcm)
        if (part.isNotEmpty()) out += Output.Audio(part)
        sentSamples += part.size
        silenceSamples = if (speech) 0 else silenceSamples + part.size
        when {
            sentSamples >= samples(maxQuestionMs) -> {
                reset(Mode.IDLE)
                out += Output.QuestionEnded(forced = true)
            }
            silenceSamples >= samples(END_SILENCE_MS) -> {
                reset(Mode.IDLE)
                out += Output.QuestionEnded(forced = false)
            }
        }
        return out
    }

    private fun feedHold(pcm: ShortArray, speech: Boolean): List<Output> {
        val out = mutableListOf<Output>()
        val part = budget(pcm)
        if (part.isNotEmpty()) out += Output.Audio(part)
        sentSamples += part.size
        if (speech) speechSamples += part.size
        if (sentSamples >= samples(maxQuestionMs)) {
            reset(Mode.IDLE)
            out += Output.QuestionEnded(forced = true)
        }
        return out
    }

    /** The part of [pcm] that still fits into `max_question_ms`. */
    private fun budget(pcm: ShortArray): ShortArray {
        val left = samples(maxQuestionMs) - sentSamples
        return if (pcm.size <= left) pcm else pcm.copyOf(left.coerceAtLeast(0).toInt())
    }

    private fun remember(pcm: ShortArray) {
        for (sample in pcm) {
            val index = (prerollStart + prerollSize) % preroll.size
            preroll[index] = sample
            if (prerollSize < preroll.size) prerollSize++ else prerollStart = (prerollStart + 1) % preroll.size
        }
    }

    private fun prerollCopy(): ShortArray =
        ShortArray(prerollSize) { preroll[(prerollStart + it) % preroll.size] }

    private fun reset(next: Mode) {
        mode = next
        prerollSize = 0
        prerollStart = 0
        elapsedSamples = 0
        sentSamples = 0
        silenceSamples = 0
        speechSamples = 0
    }

    private fun samples(ms: Long): Long = ms * sampleRate / 1000

    companion object {
        /** Audio before the detector fired that goes into the question, ms. */
        const val PREROLL_MS = 300L

        /** Silence that ends the question, ms. */
        const val END_SILENCE_MS = 700L

        /** Shortest speech of a hold that counts as a question, ms. */
        const val MIN_SPEECH_MS = 300L
    }
}
