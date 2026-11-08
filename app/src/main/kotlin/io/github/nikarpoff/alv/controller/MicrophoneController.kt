package io.github.nikarpoff.alv.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.konovalov.vad.webrtc.Vad
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/** Microphone of A6 and A8. */
interface Microphone {
    /** Turns the microphone on; chunks go to the controller. */
    fun start()

    /** Turns the microphone off. */
    fun stop()
}

/**
 * Microphone on `AudioRecord` (MOB-6): 16 kHz, mono, PCM 16 bit, source `VOICE_RECOGNITION`.
 *
 * Reads chunks of 100 ms on its own thread, runs the WebRTC voice activity detector on them and
 * hands each chunk with the verdict to [sink]. Audio lives in memory only (MOB-16).
 *
 * @param sink receives every chunk and whether the detector heard speech in it
 */
class MicrophoneController(
    private val context: Context,
    private val sink: (ShortArray, Boolean) -> Unit,
) : Microphone {
    private val lock = Any()
    private var worker: Thread? = null

    @Volatile
    private var session = 0

    override fun start() {
        synchronized(lock) {
            if (worker != null) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val record = try {
                createRecord()
            } catch (e: SecurityException) {
                return
            } ?: return
            val id = ++session
            worker = Thread({ run(record, id) }, "alv-microphone").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            session++
            val thread = worker ?: return
            worker = null
            // The next start may open the microphone at once: let the old record go first
            thread.join(STOP_WAIT_MS)
        }
    }

    @Suppress("MissingPermission") // checked in start()
    private fun createRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_SAMPLES * 2 * BUFFER_CHUNKS),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    private fun run(record: AudioRecord, id: Int) {
        val vad = Vad.builder()
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_320)
            .setMode(Mode.VERY_AGGRESSIVE)
            .setSpeechDurationMs(VAD_SPEECH_MS)
            .setSilenceDurationMs(VAD_SILENCE_MS)
            .build()
        try {
            record.startRecording()
            val frame = ShortArray(VAD_FRAME_SAMPLES)
            while (session == id) {
                val chunk = ShortArray(CHUNK_SAMPLES)
                var read = 0
                while (read < CHUNK_SAMPLES && session == id) {
                    val n = record.read(chunk, read, CHUNK_SAMPLES - read)
                    if (n < 0) return
                    read += n
                }
                if (session != id) break
                var speech = false
                for (offset in 0 until CHUNK_SAMPLES step VAD_FRAME_SAMPLES) {
                    chunk.copyInto(frame, 0, offset, offset + VAD_FRAME_SAMPLES)
                    if (vad.isSpeech(frame)) speech = true
                }
                sink(chunk, speech)
            }
        } catch (e: IllegalStateException) {
            // The record was released under us: the microphone is off anyway
        } finally {
            runCatching { record.stop() }
            record.release()
            vad.close()
        }
    }

    private companion object {
        const val SAMPLE_RATE = AssistantController.MIC_SAMPLE_RATE

        /** 100 ms of audio: one AUDIO_IN frame. */
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10

        /** 20 ms frame of the detector. */
        const val VAD_FRAME_SAMPLES = 320

        const val BUFFER_CHUNKS = 4
        const val VAD_SPEECH_MS = 60
        const val VAD_SILENCE_MS = 100
        const val STOP_WAIT_MS = 300L
    }
}
