package io.github.nikarpoff.alv.view

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.nikarpoff.alv.R
import io.github.nikarpoff.alv.controller.Announcer
import io.github.nikarpoff.alv.controller.DebugJournal
import io.github.nikarpoff.alv.controller.Earcon
import io.github.nikarpoff.alv.controller.Notice
import io.github.nikarpoff.alv.controller.Signals
import java.io.IOException

/**
 * Sound and vibration of the app (A13, A11, A12, MOB-4, MOB-11).
 *
 * Earcons are loaded into a [SoundPool] in advance, so the confirmation of a gesture starts within
 * 100 ms; the delay from the touch event to the call goes into [journal] as `confirm_ms`.
 * System phrases are the WAVs of `tts-service` (`res/raw/phrase_*.wav`), played one at a time.
 */
class Feedback(context: Context, private val journal: DebugJournal) : Signals, Announcer {
    private val appContext = context.applicationContext

    private val sounds = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val earcons: Map<Earcon, Int> = mapOf(
        Earcon.TAP to R.raw.earcon_tap,
        Earcon.LISTEN_START to R.raw.earcon_listen_start,
        Earcon.LISTEN_END to R.raw.earcon_listen_end,
        Earcon.DOUBLE_TAP to R.raw.earcon_double_tap,
        Earcon.ERROR to R.raw.earcon_error,
        Earcon.UNAVAILABLE to R.raw.earcon_unavailable,
        Earcon.HAZARD to R.raw.earcon_hazard,
    ).mapValues { (_, raw) -> sounds.load(appContext, raw, 1) }

    private val vibrator: Vibrator? = appContext.getSystemService(Vibrator::class.java)
    private val phraseLock = Any()
    private var phrase: MediaPlayer? = null

    override fun signal(earcon: Earcon, eventNanos: Long, vibrate: Boolean) {
        earcons[earcon]?.let { sounds.play(it, 1f, 1f, 1, 0, 1f) }
        if (vibrate) vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        journal.record("confirm_ms", (System.nanoTime() - eventNanos) / NANOS_PER_MILLI)
    }

    override fun announce(notice: Notice, onDone: () -> Unit) {
        val raw = phraseOf(notice).raw
        synchronized(phraseLock) {
            phrase?.release()
            phrase = null
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(SPEECH)
                appContext.resources.openRawResourceFd(raw).use { fd ->
                    player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                player.setOnCompletionListener {
                    synchronized(phraseLock) {
                        if (phrase === it) phrase = null
                    }
                    it.release()
                    onDone()
                }
                player.prepare()
                player.start()
                phrase = player
            } catch (e: IOException) {
                player.release()
                onDone()
            } catch (e: IllegalStateException) {
                player.release()
                onDone()
            }
        }
    }

    /** Frees the sounds and the phrase player. */
    fun release() {
        synchronized(phraseLock) {
            phrase?.release()
            phrase = null
        }
        sounds.release()
    }

    private fun phraseOf(notice: Notice): Phrase = when (notice) {
        is Notice.Error -> ErrorPhrases.forCode(notice.code, notice.question)
        Notice.NoConnection -> ErrorPhrases.noConnection
        Notice.CameraUnavailable -> ErrorPhrases.badRequest
        is Notice.PassiveMode -> SystemPhrases.passiveMode(notice.enabled)
        Notice.PermissionRationale -> SystemPhrases.permissionRationale
        Notice.PermissionDenied -> SystemPhrases.permissionDenied
    }

    private companion object {
        const val MAX_STREAMS = 3
        const val VIBRATION_MS = 30L
        const val NANOS_PER_MILLI = 1_000_000.0

        /** Phrases sound like the answers: accessibility speech. */
        val SPEECH: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
