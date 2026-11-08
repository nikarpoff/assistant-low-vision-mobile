package io.github.nikarpoff.alv.view

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.nikarpoff.alv.controller.DebugJournal
import java.util.Locale

/**
 * Fallback speech (MOB-11): an answer that came with `audio: null` (`tts_unavailable`) is spoken
 * by the system [TextToSpeech] with a Russian voice, sentence by sentence as the texts arrive.
 *
 * The time from the call of the synthesis to the start of the speech
 * ([UtteranceProgressListener.onStart]) goes into [journal] as `tts_start_ms`:
 * it is the measurement for the estimate of variant C (EXP-7).
 * Without a Russian voice nothing is spoken: [onUnavailable] plays the error earcon instead.
 */
class FallbackSpeech(
    context: Context,
    private val journal: DebugJournal,
    private val onUnavailable: () -> Unit,
) {
    /** Reports of the speech, called from the synthesis thread. */
    interface Listener {
        /** Speech [id] started to sound at [nanos]. */
        fun onSpeechStart(id: Long, nanos: Long)

        /** Speech [id] is over: everything was spoken, or there is no voice. */
        fun onSpeechDone(id: Long)
    }

    var listener: Listener? = null

    private val lock = Any()
    private var ready = false
    private var activeId = NONE
    private var queued = 0
    private var ended = false
    private var started = false
    private var sequence = 0
    private val calledAt = HashMap<String, Long>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = tts.setLanguage(RUSSIAN)
        val voice = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(Progress())
        synchronized(lock) { ready = voice }
    }

    /** Starts speech [id]: the texts of the answer follow. */
    fun begin(id: Long) {
        synchronized(lock) {
            if (activeId != NONE) tts.stop()
            activeId = id
            queued = 0
            ended = false
            started = false
            calledAt.clear()
        }
    }

    /** Speaks a sentence of speech [id]. */
    fun speak(id: Long, text: String) {
        synchronized(lock) {
            if (id != activeId || !ready || text.isBlank()) return
            val utterance = "$id:${sequence++}"
            calledAt[utterance] = System.nanoTime()
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, utterance) == TextToSpeech.SUCCESS) queued++
        }
    }

    /** No more texts for speech [id]: report the end when everything was spoken. */
    fun end(id: Long) {
        val done = synchronized(lock) {
            if (id != activeId) return
            ended = true
            if (!ready) onUnavailable()
            queued == 0
        }
        if (done) finish(id)
    }

    /** Stops the speech at once; no report follows. */
    fun stop() {
        synchronized(lock) {
            activeId = NONE
            calledAt.clear()
        }
        tts.stop()
    }

    /** Frees the synthesis engine. */
    fun release() {
        stop()
        tts.shutdown()
    }

    private fun finish(id: Long) {
        synchronized(lock) {
            if (id != activeId) return
            activeId = NONE
        }
        listener?.onSpeechDone(id)
    }

    private fun idOf(utterance: String): Long = utterance.substringBefore(':').toLongOrNull() ?: NONE

    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val now = System.nanoTime()
            val id = idOf(utteranceId)
            val first = synchronized(lock) {
                calledAt.remove(utteranceId)?.let { journal.record("tts_start_ms", (now - it) / NANOS_PER_MILLI) }
                if (id != activeId || started) return
                started = true
                true
            }
            if (first) listener?.onSpeechStart(id, now)
        }

        override fun onDone(utteranceId: String) {
            utteranceFinished(idOf(utteranceId))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            utteranceFinished(idOf(utteranceId))
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            utteranceFinished(idOf(utteranceId))
        }

        private fun utteranceFinished(id: Long) {
            val done = synchronized(lock) {
                if (id != activeId) return
                queued--
                ended && queued <= 0
            }
            if (done) finish(id)
        }
    }

    private companion object {
        const val NONE = -1L
        const val NANOS_PER_MILLI = 1_000_000.0
        val RUSSIAN: Locale = Locale.forLanguageTag("ru-RU")
    }
}
