package io.github.nikarpoff.alv.controller

import io.github.nikarpoff.alv.model.Answer
import io.github.nikarpoff.alv.model.ErrorCode
import io.github.nikarpoff.alv.model.Settings
import io.github.nikarpoff.alv.model.State
import io.github.nikarpoff.alv.model.protocol.Accepted
import io.github.nikarpoff.alv.model.protocol.AnswerEnd
import io.github.nikarpoff.alv.model.protocol.AnswerStart
import io.github.nikarpoff.alv.model.protocol.AnswerText
import io.github.nikarpoff.alv.model.protocol.AskEnd
import io.github.nikarpoff.alv.model.protocol.AskStart
import io.github.nikarpoff.alv.model.protocol.AskTrigger
import io.github.nikarpoff.alv.model.protocol.AudioEncoding
import io.github.nikarpoff.alv.model.protocol.AudioFormat
import io.github.nikarpoff.alv.model.protocol.Cancel
import io.github.nikarpoff.alv.model.protocol.ClearReason
import io.github.nikarpoff.alv.model.protocol.ClientMessage
import io.github.nikarpoff.alv.model.protocol.ClientMetrics
import io.github.nikarpoff.alv.model.protocol.DegradedReason
import io.github.nikarpoff.alv.model.protocol.Describe
import io.github.nikarpoff.alv.model.protocol.DescribeMode
import io.github.nikarpoff.alv.model.protocol.ErrorMessage
import io.github.nikarpoff.alv.model.protocol.Frame
import io.github.nikarpoff.alv.model.protocol.FrameKind
import io.github.nikarpoff.alv.model.protocol.Hello
import io.github.nikarpoff.alv.model.protocol.Intent
import io.github.nikarpoff.alv.model.protocol.Network
import io.github.nikarpoff.alv.model.protocol.PassiveClear
import io.github.nikarpoff.alv.model.protocol.Priority
import io.github.nikarpoff.alv.model.protocol.Transcript
import io.github.nikarpoff.alv.model.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class AssistantControllerTest {
    /** Loop on a virtual clock: posted tasks run in [run], scheduled ones when the time comes. */
    private class ManualLoop : Loop {
        var nowNanos = 1_000_000_000L
        private val tasks = ArrayDeque<() -> Unit>()
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()

        override fun post(task: () -> Unit) {
            tasks.addLast(task)
        }

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val timer = nowNanos + delayMs * 1_000_000 to task
            timers += timer
            return Cancellable { timers.remove(timer) }
        }

        override fun nanoTime(): Long = nowNanos

        fun run() {
            while (tasks.isNotEmpty()) tasks.removeFirst()()
        }

        fun advanceMs(ms: Long) {
            val end = nowNanos + ms * 1_000_000
            while (true) {
                run()
                val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
                timers.remove(next)
                nowNanos = maxOf(nowNanos, next.first)
                next.second()
            }
            nowNanos = end
            run()
        }

        val nowMs: Long get() = nowNanos / 1_000_000
    }

    private class FakeSession : Session {
        override var connected = true
        var opened: String? = null
        val messages = mutableListOf<ClientMessage>()
        val frames = mutableListOf<Frame>()

        override fun open(url: String, token: String, hello: () -> Hello) {
            opened = url
        }

        override fun close() {
            opened = null
        }

        override fun send(message: ClientMessage): Boolean {
            if (connected) messages += message
            return connected
        }

        override fun send(frame: Frame): Boolean {
            if (connected) frames += frame
            return connected
        }

        inline fun <reified T : ClientMessage> sent(): List<T> = messages.filterIsInstance<T>()
    }

    private class FakeCamera : Camera {
        var captures = 0
        var discarded = 0

        override fun capture(maxSide: Int, jpegQuality: Int): PendingSnapshot {
            captures++
            return object : PendingSnapshot {
                override fun await(timeoutMs: Long) = Snapshot(JPEG, 640, 480, 10, 20, 30)
                override fun discard() {
                    discarded++
                }
            }
        }
    }

    private class FakeMicrophone : Microphone {
        var on = false

        override fun start() {
            on = true
        }

        override fun stop() {
            on = false
        }
    }

    private class FakePlayback : Playback {
        val calls = mutableListOf<String>()
        var lastId = -1L
        var replayed: Answer? = null

        override fun begin(id: Long, sampleRate: Int?) {
            lastId = id
            calls += "begin $sampleRate"
        }

        override fun text(id: Long, text: String) {
            calls += "text $text"
        }

        override fun audio(id: Long, pcm: ByteArray) {
            calls += "audio ${pcm.size}"
        }

        override fun end(id: Long) {
            calls += "end"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun replay(id: Long, answer: Answer) {
            lastId = id
            replayed = answer
            calls += "replay"
        }
    }

    private class FakeSignals : Signals {
        val earcons = mutableListOf<Earcon>()

        override fun signal(earcon: Earcon, eventNanos: Long, vibrate: Boolean) {
            earcons += earcon
        }
    }

    private class FakeAnnouncer : Announcer {
        val notices = mutableListOf<Notice>()

        override fun announce(notice: Notice, onDone: () -> Unit) {
            notices += notice
        }
    }

    private class MemoryStore : PassiveModeSetting.Store {
        var value: Boolean? = null

        override suspend fun load(): Boolean? = value

        override suspend fun save(enabled: Boolean) {
            value = enabled
        }
    }

    private val loop = ManualLoop()
    private val session = FakeSession()
    private val camera = FakeCamera()
    private val microphone = FakeMicrophone()
    private val playback = FakePlayback()
    private val signals = FakeSignals()
    private val announcer = FakeAnnouncer()
    private val store = MemoryStore()

    private val controller = AssistantController(
        loop = loop,
        session = session,
        camera = camera,
        microphone = microphone,
        playback = playback,
        signals = signals,
        announcer = announcer,
        passiveSetting = PassiveModeSetting(store),
        journal = DebugJournal(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        network = { Network.WIFI },
        appVersion = "0.1.0",
        platform = "android-34",
        pxPerDp = 1f,
    )

    @Before
    fun setUp() {
        controller.start(Settings(deviceId = "d", serverUrl = "wss://alv.example.org", deviceToken = "t"))
        controller.onPermissions(true)
        controller.onWelcome(WELCOME)
        loop.run()
    }

    private fun tap() {
        controller.onTouchDown(100f, 100f, loop.nowMs)
        loop.advanceMs(80)
        controller.onTouchUp(100f, 100f, loop.nowMs)
        loop.run()
    }

    private fun requestId(): UUID = session.sent<Describe>().last().requestId

    private fun answer(id: UUID, text: String = "Впереди стул.") {
        controller.onMessage(Accepted(id))
        controller.onMessage(AnswerStart(id, Answer.Kind.DESCRIPTION, Priority.NORMAL, PCM_24K))
        controller.onMessage(AnswerText(id, 0, text))
        controller.onFrame(Frame(FrameKind.AUDIO_OUT, id, 0, ByteArray(480)))
        controller.onMessage(AnswerEnd(id, text, Intent.DESCRIBE, false, false, DegradedReason.NONE, emptyList(), mapOf("server_ttfa_ms" to 900.0)))
        loop.run()
    }

    @Test
    fun opensTheSessionEndpoint() {
        assertEquals("wss://alv.example.org/v1/session", session.opened)
        assertEquals(AssistantController.Status.IDLE, controller.status)
    }

    @Test
    fun tapSendsDescribeWithTheSnapshotTakenOnTouchDown() {
        tap()

        assertEquals(listOf(Earcon.TAP), signals.earcons)
        assertEquals(1, camera.captures)
        val describe = session.sent<Describe>().single()
        assertEquals(DescribeMode.ON_DEMAND, describe.mode)
        assertEquals(JPEG.size, describe.image.bytes)
        val frame = session.frames.single()
        assertEquals(FrameKind.IMAGE_JPEG, frame.kind)
        assertEquals(describe.requestId, frame.requestId)
        assertEquals(State.ANALYZING, controller.state)
        assertEquals(AssistantController.Status.LOOKING, controller.status)
    }

    @Test
    fun answerIsPlayedThenMetricsAreSentAndTheWindowOpens() {
        tap()
        val id = requestId()

        answer(id)

        assertEquals(State.SPEAKING, controller.state)
        assertEquals(listOf("begin 24000", "text Впереди стул.", "audio 480", "end"), playback.calls)
        assertTrue(session.sent<ClientMetrics>().isEmpty())

        controller.onFirstAudio(playback.lastId, loop.nanoTime())
        controller.onPlaybackFinished(playback.lastId, 2)
        loop.run()

        val metrics = session.sent<ClientMetrics>().single()
        assertEquals(id, metrics.requestId)
        assertNotNull(metrics.metrics.triggerToFirstAudioMs)
        assertEquals(2, metrics.metrics.playbackUnderruns)
        assertEquals(State.LISTENING, controller.state)
        assertTrue(microphone.on)
        assertEquals("Впереди стул.", controller.screen.value.answerText)
    }

    @Test
    fun messagesOfAnotherRequestAreIgnored() {
        tap()

        controller.onMessage(AnswerStart(UUID.randomUUID(), Answer.Kind.DESCRIPTION, Priority.NORMAL, PCM_24K))
        loop.run()

        assertEquals(State.ANALYZING, controller.state)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun errorSpeaksThePhraseOfTheCodeAndSendsMetrics() {
        tap()
        val id = requestId()

        controller.onMessage(ErrorMessage(id, ErrorCode.DETECTOR_UNAVAILABLE, "detector down", true))
        loop.run()

        assertEquals(State.IDLE, controller.state)
        assertEquals(listOf(Notice.Error(ErrorCode.DETECTOR_UNAVAILABLE, false)), announcer.notices)
        assertEquals(Earcon.ERROR, signals.earcons.last())
        assertEquals(id, session.sent<ClientMetrics>().single().requestId)
    }

    @Test
    fun tapWithoutConnectionSaysNoConnectionAtOnce() {
        session.connected = false

        tap()

        assertEquals(listOf(Notice.NoConnection), announcer.notices)
        assertEquals(State.IDLE, controller.state)
        assertTrue(session.messages.isEmpty())
    }

    @Test
    fun lostConnectionDuringRequestIsAnError() {
        tap()

        session.connected = false
        controller.onDisconnected()
        loop.run()

        assertEquals(State.IDLE, controller.state)
        assertEquals(listOf(Notice.NoConnection), announcer.notices)
        assertEquals(AssistantController.Status.OFFLINE, controller.status)
    }

    @Test
    fun requestWithoutAnswerFailsAtTheDeadline() {
        tap()

        loop.advanceMs(AssistantController.REQUEST_DEADLINE_MS)

        assertEquals(State.IDLE, controller.state)
        assertEquals(listOf(Notice.Error(ErrorCode.UPSTREAM_TIMEOUT, false)), announcer.notices)
    }

    @Test
    fun secondTouchCancelsTheDescriptionAndRepeatsTheLastAnswer() {
        tap()
        answer(requestId())
        controller.onPlaybackFinished(playback.lastId, 0)
        loop.run()
        loop.advanceMs(1_000)
        tap()
        val started = requestId()

        controller.onTouchDown(100f, 100f, loop.nowMs + 100)
        loop.run()

        assertEquals(started, session.sent<Cancel>().single().requestId)
        assertEquals("Впереди стул.", playback.replayed?.text)
        assertEquals(State.SPEAKING, controller.state)
        assertEquals(Earcon.DOUBLE_TAP, signals.earcons.last())
    }

    @Test
    fun doubleTapWithNothingToRepeatIsUnavailable() {
        controller.onAccessibilityRepeat()
        loop.run()

        assertEquals(listOf(Earcon.UNAVAILABLE), signals.earcons)
        assertEquals(State.IDLE, controller.state)
    }

    @Test
    fun holdRecordsTheQuestionWithTheSnapshot() {
        controller.onTouchDown(100f, 100f, loop.nowMs)
        loop.advanceMs(GestureClassifier.HOLD_MS)

        assertEquals(Earcon.LISTEN_START, signals.earcons.single())
        val start = session.sent<AskStart>().single()
        assertEquals(AskTrigger.HOLD, start.trigger)
        assertTrue(start.withImage)
        assertTrue(microphone.on)

        repeat(5) { controller.onAudio(ShortArray(1600), speech = true) }
        loop.run()
        controller.onTouchUp(100f, 100f, loop.nowMs)
        loop.run()

        val audio = session.frames.filter { it.kind == FrameKind.AUDIO_IN }
        assertEquals((0L until 5L).toList(), audio.map { it.seq })
        assertEquals(500L, session.sent<AskEnd>().single().audioMs)
        assertEquals(State.ASKING, controller.state)
        assertFalse(microphone.on)
    }

    @Test
    fun holdWithoutSpeechCancelsTheQuestion() {
        controller.onTouchDown(100f, 100f, loop.nowMs)
        loop.advanceMs(GestureClassifier.HOLD_MS)
        controller.onAudio(ShortArray(1600), speech = false)
        controller.onTouchUp(100f, 100f, loop.nowMs)
        loop.run()

        assertEquals(session.sent<AskStart>().single().requestId, session.sent<Cancel>().single().requestId)
        assertEquals(State.IDLE, controller.state)
        assertEquals(Earcon.LISTEN_END, signals.earcons.last())
    }

    @Test
    fun listenWindowEndsWithoutSpeech() {
        tap()
        answer(requestId())
        controller.onPlaybackFinished(playback.lastId, 0)
        loop.run()

        repeat(50) { controller.onAudio(ShortArray(1600), speech = false) }
        loop.run()

        assertEquals(State.IDLE, controller.state)
        assertFalse(microphone.on)
    }

    @Test
    fun voiceRepeatCommandReplaysWithoutTheServer() {
        tap()
        answer(requestId())
        controller.onPlaybackFinished(playback.lastId, 0)
        loop.run()

        controller.onAudio(ShortArray(1600), speech = true)
        repeat(8) { controller.onAudio(ShortArray(1600), speech = false) }
        loop.run()
        val ask = session.sent<AskStart>().single()
        assertEquals(AskTrigger.LISTEN_WINDOW, ask.trigger)
        assertEquals(State.ASKING, controller.state)

        controller.onMessage(Transcript(ask.requestId, "повтори", Intent.REPEAT))
        loop.run()

        assertEquals("Впереди стул.", playback.replayed?.text)
        assertEquals(State.SPEAKING, controller.state)
    }

    @Test
    fun swipeUpTurnsThePassiveModeOnAndTheTimerSendsPassiveSnapshots() {
        controller.onTouchDown(100f, 400f, loop.nowMs)
        controller.onTouchMove(100f, 200f, loop.nowMs + 50)
        controller.onTouchUp(100f, 200f, loop.nowMs + 60)
        loop.run()

        assertEquals(listOf(Notice.PassiveMode(true)), announcer.notices)
        assertEquals(true, store.value)
        assertTrue(controller.screen.value.passiveMode)
        assertEquals(1, camera.discarded)

        loop.advanceMs(3_000)

        val describe = session.sent<Describe>().single()
        assertEquals(DescribeMode.PASSIVE, describe.mode)
        assertEquals(State.PASSIVE, controller.state)

        controller.onMessage(PassiveClear(describe.requestId, ClearReason.NO_HAZARD))
        loop.run()

        assertEquals(State.IDLE, controller.state)
        assertTrue(session.sent<ClientMetrics>().isEmpty())
    }

    @Test
    fun answerWithoutAudioGoesToTheFallbackSpeech() {
        tap()
        val id = requestId()

        controller.onMessage(AnswerStart(id, Answer.Kind.DESCRIPTION, Priority.NORMAL, null))
        controller.onMessage(AnswerText(id, 0, "Впереди стул."))
        loop.run()

        assertEquals(listOf("begin null", "text Впереди стул."), playback.calls)
    }

    @Test
    fun stopInterruptsTheAnswer() {
        tap()
        val id = requestId()
        controller.onMessage(AnswerStart(id, Answer.Kind.DESCRIPTION, Priority.NORMAL, PCM_24K))
        loop.run()

        controller.onAccessibilityStop()
        loop.run()

        assertEquals("stop", playback.calls.last())
        assertEquals(id, session.sent<Cancel>().single().requestId)
        assertEquals(State.IDLE, controller.state)
        assertNull(session.sent<ClientMetrics>().firstOrNull())
    }

    private companion object {
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val PCM_24K = AudioFormat(AudioEncoding.PCM_S16LE, 24_000, 1)
        val WELCOME = Welcome(
            sessionId = UUID.randomUUID(),
            serverVersion = "0.1.0",
            configId = "c9f2a1d0e3b4",
            config = AssistantController.DEFAULT_CONFIG,
        )
    }
}
