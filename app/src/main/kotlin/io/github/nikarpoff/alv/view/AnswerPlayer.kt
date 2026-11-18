package io.github.nikarpoff.alv.view

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.nikarpoff.alv.controller.Playback
import io.github.nikarpoff.alv.model.Answer
import io.github.nikarpoff.alv.model.PlaybackClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Answer playback (A5, A10, MOB-7).
 *
 * One streaming [AudioTrack] with `PERFORMANCE_MODE_LOW_LATENCY` and `USAGE_ASSISTANCE_ACCESSIBILITY`
 * is created in advance and plays every answer from its first chunk. The audio focus is transient
 * with ducking of other sounds. The start of the sound for `trigger_to_first_audio_ms` is found from
 * the playback head and the sample rate by [PlaybackClock], not from the moment of the write.
 * An answer without audio goes to [fallback] (MOB-11).
 *
 * All work with the track runs on one thread; [stop] reaches it between chunks of at most 40 ms.
 */
class AnswerPlayer(
    context: Context,
    private val fallback: FallbackSpeech,
) : Playback, FallbackSpeech.Listener {
    var listener: Playback.Listener? = null

    private val audioManager: AudioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(ATTRIBUTES)
        .build()

    private val commands = LinkedBlockingQueue<Command>()

    @Volatile
    private var stopRequested = 0

    private sealed interface Command {
        val id: Long

        class Begin(override val id: Long, val sampleRate: Int?) : Command
        class Text(override val id: Long, val text: String) : Command
        class Pcm(override val id: Long, val pcm: ByteArray) : Command
        class End(override val id: Long) : Command
        class Stop(override val id: Long = NONE) : Command
        class Quit(override val id: Long = NONE) : Command
    }

    // State of the worker thread
    private var track: AudioTrack? = null
    private var activeId = NONE
    private var speech = false
    private var clock: PlaybackClock? = null
    private var firstFrame = 0L
    private var written = 0L
    private var underrunsAtStart = 0
    private var ending = false
    private var firstReported = false

    private val worker = Thread(::run, "alv-player").apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    init {
        fallback.listener = this
        commands.add(Command.Begin(NONE, DEFAULT_SAMPLE_RATE))
    }

    override fun begin(id: Long, sampleRate: Int?) {
        commands.add(Command.Begin(id, sampleRate))
    }

    override fun text(id: Long, text: String) {
        commands.add(Command.Text(id, text))
    }

    override fun audio(id: Long, pcm: ByteArray) {
        commands.add(Command.Pcm(id, pcm))
    }

    override fun end(id: Long) {
        commands.add(Command.End(id))
    }

    override fun stop() {
        stopRequested++
        commands.clear()
        commands.add(Command.Stop())
        fallback.stop()
    }

    override fun replay(id: Long, answer: Answer) {
        val audio = answer.audio
        begin(id, audio?.sampleRate)
        if (audio == null) text(id, answer.text) else audio(id, audio.pcm)
        end(id)
    }

    /** Stops the thread and frees the track. */
    fun release() {
        stop()
        commands.add(Command.Quit())
        worker.join(QUIT_WAIT_MS)
    }

    override fun onSpeechStart(id: Long, nanos: Long) {
        listener?.onFirstAudio(id, nanos)
    }

    override fun onSpeechDone(id: Long) {
        audioManager.abandonAudioFocusRequest(focus)
        listener?.onPlaybackFinished(id, 0)
    }

    private fun run() {
        while (true) {
            val busy = activeId != NONE && !speech && written > 0
            val command = if (busy) commands.poll(TICK_MS, TimeUnit.MILLISECONDS) else commands.take()
            when (command) {
                is Command.Quit -> break
                null -> Unit
                else -> handle(command)
            }
            if (activeId != NONE && !speech) tick()
        }
        track?.release()
        track = null
    }

    private fun handle(command: Command) {
        if (command is Command.Stop) {
            halt()
            return
        }
        if (command is Command.Begin) {
            start(command)
            return
        }
        if (command.id != activeId) return
        when (command) {
            is Command.Text -> if (speech) fallback.speak(command.id, command.text)
            is Command.Pcm -> if (!speech) write(command.pcm)
            is Command.End -> when {
                speech -> fallback.end(command.id)
                written == 0L -> finished(0)
                else -> ending = true
            }
            else -> Unit
        }
    }

    private fun start(begin: Command.Begin) {
        halt()
        if (begin.id == NONE) {
            // Warm-up: the track is created before the first answer
            begin.sampleRate?.let { trackFor(it) }
            return
        }
        activeId = begin.id
        firstReported = false
        ending = false
        written = 0
        audioManager.requestAudioFocus(focus)
        val rate = begin.sampleRate
        if (rate == null) {
            speech = true
            fallback.begin(begin.id)
            return
        }
        speech = false
        val audioTrack = trackFor(rate) ?: return finished(0)
        firstFrame = head(audioTrack)
        underrunsAtStart = audioTrack.underrunCount
        clock = PlaybackClock(rate).apply { answerStarts(firstFrame) }
    }

    private fun write(pcm: ByteArray) {
        val audioTrack = track ?: return
        val stamp = stopRequested
        var offset = 0
        while (offset < pcm.size && stamp == stopRequested) {
            val size = minOf(WRITE_PIECE_BYTES, pcm.size - offset)
            val n = audioTrack.write(pcm, offset, size, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            offset += n
            written += n / BYTES_PER_FRAME
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) audioTrack.play()
            tick()
        }
    }

    /** Reports the first sound and the end of the answer from the playback head. */
    private fun tick() {
        val audioTrack = track ?: return
        if (written == 0L) return
        val position = head(audioTrack)
        if (!firstReported && position > firstFrame) {
            clock?.onPosition(position, System.nanoTime())?.let {
                firstReported = true
                listener?.onFirstAudio(activeId, it)
            }
        }
        if (ending && position >= firstFrame + written) {
            audioTrack.pause()
            finished(audioTrack.underrunCount - underrunsAtStart)
        }
    }

    private fun finished(underruns: Int) {
        val id = activeId
        activeId = NONE
        clock = null
        audioManager.abandonAudioFocusRequest(focus)
        listener?.onPlaybackFinished(id, underruns.coerceAtLeast(0))
    }

    /** Stops the current answer without a report. */
    private fun halt() {
        if (activeId == NONE) return
        activeId = NONE
        clock = null
        if (speech) {
            fallback.stop()
        } else {
            track?.let {
                it.pause()
                it.flush()
            }
        }
        speech = false
        audioManager.abandonAudioFocusRequest(focus)
    }

    private fun trackFor(sampleRate: Int): AudioTrack? {
        track?.let { if (it.sampleRate == sampleRate) return it }
        track?.release()
        track = null
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        track = try {
            AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(minBuffer * BUFFER_FACTOR)
                .build()
        } catch (e: UnsupportedOperationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        return track
    }

    /** Playback head as an unsigned 32-bit frame counter. */
    private fun head(audioTrack: AudioTrack): Long = audioTrack.playbackHeadPosition.toLong() and UINT32_MASK

    private companion object {
        const val NONE = -1L
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2
        const val BUFFER_FACTOR = 4

        /** 40 ms at 24 kHz: [stop] waits at most this long for a blocking write. */
        const val WRITE_PIECE_BYTES = 1_920
        const val TICK_MS = 5L
        const val QUIT_WAIT_MS = 500L
        const val UINT32_MASK = 0xFFFF_FFFFL

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
