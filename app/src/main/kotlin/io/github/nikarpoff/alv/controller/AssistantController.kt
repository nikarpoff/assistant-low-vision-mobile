package io.github.nikarpoff.alv.controller

import io.github.nikarpoff.alv.model.Action
import io.github.nikarpoff.alv.model.Answer
import io.github.nikarpoff.alv.model.AssistantStateMachine
import io.github.nikarpoff.alv.model.ClientMetricsBuilder
import io.github.nikarpoff.alv.model.ClientMetricsBuilder.Outcome
import io.github.nikarpoff.alv.model.ClientMetricsBuilder.RequestKind
import io.github.nikarpoff.alv.model.ErrorCode
import io.github.nikarpoff.alv.model.Event
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
import io.github.nikarpoff.alv.model.protocol.ClientConfig
import io.github.nikarpoff.alv.model.protocol.Describe
import io.github.nikarpoff.alv.model.protocol.DescribeMode
import io.github.nikarpoff.alv.model.protocol.ErrorMessage
import io.github.nikarpoff.alv.model.protocol.Focus
import io.github.nikarpoff.alv.model.protocol.Frame
import io.github.nikarpoff.alv.model.protocol.FrameKind
import io.github.nikarpoff.alv.model.protocol.Hello
import io.github.nikarpoff.alv.model.protocol.ImageInfo
import io.github.nikarpoff.alv.model.protocol.Intent
import io.github.nikarpoff.alv.model.protocol.Network
import io.github.nikarpoff.alv.model.protocol.PROTOCOL_LOCALE
import io.github.nikarpoff.alv.model.protocol.PROTOCOL_VERSION
import io.github.nikarpoff.alv.model.protocol.PassiveClear
import io.github.nikarpoff.alv.model.protocol.ServerMessage
import io.github.nikarpoff.alv.model.protocol.Transcript
import io.github.nikarpoff.alv.model.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Thread of the controllers: every entry point posts its work here, so the automaton,
 * the request and the microphone stream are touched by one task at a time.
 */
interface Loop {
    /** Runs [task] on the loop after the tasks already posted. */
    fun post(task: () -> Unit)

    /** Runs [task] on the loop after [delayMs]. */
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable

    /**
     * Monotonic clock, ns. On Android it is `System.nanoTime`: the same `CLOCK_MONOTONIC`
     * as `MotionEvent.getEventTime` and `AudioTrack` positions, so all moments compare.
     */
    fun nanoTime(): Long
}

/** Scheduled task that can be called off. */
fun interface Cancellable {
    fun cancel()
}

/** [Loop] on one thread of a scheduled executor. */
class ExecutorLoop(name: String = "alv-controller") : Loop {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    override fun post(task: () -> Unit) {
        if (!executor.isShutdown) executor.execute(task)
    }

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        if (executor.isShutdown) return Cancellable { }
        val future = executor.schedule(task, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun nanoTime(): Long = System.nanoTime()

    /** Stops the thread; later posts are dropped. */
    fun shutdown() {
        executor.shutdownNow()
    }
}

/** Answer playback (A5, MOB-7), done by the View. Every call carries the id of the playback it belongs to. */
interface Playback {
    /** Reports of the playback, called from any thread. */
    interface Listener {
        /** The first frame of playback [id] started to sound at [nanos] ([io.github.nikarpoff.alv.model.PlaybackClock]). */
        fun onFirstAudio(id: Long, nanos: Long)

        /** Playback [id] finished after [end]: everything sounded; [underruns] - buffer underruns. */
        fun onPlaybackFinished(id: Long, underruns: Int)
    }

    /** Starts playback [id]: PCM at [sampleRate], or the system speech synthesis when it is `null` (MOB-11). */
    fun begin(id: Long, sampleRate: Int?)

    /** Text of a sentence; spoken only by the system synthesis of an answer without audio. */
    fun text(id: Long, text: String)

    /** PCM s16le chunk of playback [id]. */
    fun audio(id: Long, pcm: ByteArray)

    /** Nothing more comes for playback [id]: report the end once all of it sounded. */
    fun end(id: Long)

    /** Stops everything at once (A9); no report follows. */
    fun stop()

    /** Plays the last answer again (A10) as playback [id]. */
    fun replay(id: Long, answer: Answer)
}

/** Earcons of MOB-4. */
enum class Earcon {
    TAP,
    LISTEN_START,
    LISTEN_END,
    DOUBLE_TAP,
    ERROR,
    UNAVAILABLE,
    HAZARD,
}

/** Earcons and vibration (A13), done by the View. */
interface Signals {
    /**
     * Plays [earcon] and vibrates when [vibrate]; the delay from [eventNanos] to the call
     * goes into the debug journal (MOB-4).
     */
    fun signal(earcon: Earcon, eventNanos: Long, vibrate: Boolean)
}

/** System phrase recorded in advance (MOB-11). */
sealed interface Notice {
    /** Phrase by the error code (PROTO-11); `null` is a code this version does not know. */
    data class Error(val code: ErrorCode?, val question: Boolean) : Notice

    /** «Нет связи с сервером». */
    data object NoConnection : Notice

    /** The camera gave no frame. */
    data object CameraUnavailable : Notice

    /** New state of the passive mode (A12). */
    data class PassiveMode(val enabled: Boolean) : Notice

    /** Explanation before the permission request (MOB-15). */
    data object PermissionRationale : Notice

    /** How to grant the permissions in the system settings (MOB-15). */
    data object PermissionDenied : Notice
}

/** Plays system phrases, done by the View. */
interface Announcer {
    /** Plays the phrase of [notice] and calls [onDone] when it finished or could not play. */
    fun announce(notice: Notice, onDone: () -> Unit = {})
}

/**
 * Debug journal of the app (MOB-4, MOB-11, MOB-13): the last value of every measured delay,
 * shown by the debug panel; [sink] also prints each record (logcat on Android).
 */
class DebugJournal(private val sink: (String) -> Unit = {}) {
    private val mutableValues = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** Last value of every record, ms. */
    val values: StateFlow<Map<String, Double>> = mutableValues.asStateFlow()

    /** Records [name] = [ms]. */
    fun record(name: String, ms: Double) {
        mutableValues.update { it + (name to ms) }
        sink("$name=${"%.1f".format(java.util.Locale.ROOT, ms)}")
    }
}

/**
 * Connects the Model with the View (MOB-2): turns gestures, the microphone, the session and the
 * passive timer into events of [AssistantStateMachine] and performs the actions of its transitions.
 *
 * Mapping of the inputs to the events of KM-2:
 * - gestures ([GestureClassifier.Gesture]): tap - E2, double tap - `cancel` of the description the tap
 *   started and E12, hold - E10, its release - E7 or E11 by [SpeechEndpointer], swipe up - E14, swipe down - E15;
 * - microphone ([SpeechEndpointer.Output]): end of the question - E7, empty window - E9, hold without speech - E11;
 * - session: `answer.start` - E3, E4 or E8 by the current state, `passive.clear` - E5, `error`, a lost
 *   connection or the request deadline - E13, `transcript` with a command - E16;
 * - [PassiveTimer] - E1.
 *
 * Every entry point posts to [loop]; the hardware and the View are reached through ports.
 *
 * @param passiveSetting stored passive mode (MOB-14); the automaton starts with [initialPassiveMode]
 * @param network current network of the phone for `hello` and `client.metrics`
 * @param appVersion `app_version` of `hello`
 * @param platform `platform` of `hello`, `android-<API>`
 * @param pxPerDp screen density for [GestureClassifier]
 */
class AssistantController(
    private val loop: Loop,
    private val session: Session,
    private val camera: Camera,
    private val microphone: Microphone,
    private val playback: Playback,
    private val signals: Signals,
    private val announcer: Announcer,
    private val passiveSetting: PassiveModeSetting,
    private val journal: DebugJournal,
    private val scope: CoroutineScope,
    private val network: () -> Network,
    private val appVersion: String,
    private val platform: String,
    pxPerDp: Float,
    initialPassiveMode: Boolean = false,
) : Session.Listener, Playback.Listener {
    /** Status on the screen for low vision users (MOB-12). */
    enum class Status {
        IDLE,
        LISTENING,
        LOOKING,
        SPEAKING,
        OFFLINE,
    }

    /**
     * What the screen shows.
     *
     * @property status status of the assistant
     * @property answerText text of the last answer
     * @property passiveMode passive mode is on
     */
    data class Screen(
        val status: Status = Status.OFFLINE,
        val answerText: String = "",
        val passiveMode: Boolean = false,
    )

    private val mutableScreen = MutableStateFlow(Screen(passiveMode = initialPassiveMode))

    /** What the screen shows. */
    val screen: StateFlow<Screen> = mutableScreen.asStateFlow()

    /** Status shown on the screen. */
    val status: Status get() = screen.value.status

    /** Current state of the automaton, for the debug panel and tests. */
    val state: State get() = machine.state

    private var machine = AssistantStateMachine(initialPassiveMode)
    private val classifier = GestureClassifier(pxPerDp)
    private var endpointer = SpeechEndpointer()
    private val passiveTimer = PassiveTimer(loop) { onPassiveTimer() }
    private var config = DEFAULT_CONFIG
    private var settings = Settings(passiveMode = initialPassiveMode)

    private var started = false
    private var paused = false
    private var permissionsReady = false
    private var micOn = false
    private var current: Request? = null
    private var deadline: Cancellable? = null
    private var lastAnswer: Answer? = null
    private var lastTapRequestId: UUID? = null
    private var playbackId = 0L
    private var touchSnapshot: PendingSnapshot? = null
    private var holdTimer: Cancellable? = null

    /** Request in flight and its metrics (MOB-13). */
    private class Request(val kind: RequestKind, val metrics: ClientMetricsBuilder) {
        val id: UUID get() = metrics.requestId
        val question: Boolean get() = kind == RequestKind.ASK
        var audioSeq = 0L
        var audioSamples = 0L
        var answerKind: Answer.Kind? = null
        var audioFormat: AudioFormat? = null
        val pcm = ByteArrayOutputStream()
        val sentences = mutableListOf<String>()
        var ended = false
        val audioMs: Long get() = audioSamples * MILLIS_PER_SECOND / MIC_SAMPLE_RATE
    }

    /** What caused an event: its time, the snapshot taken for it, the phrase of an error. */
    private class Trigger(
        val eventNanos: Long,
        var snapshot: PendingSnapshot? = null,
        val notice: Notice? = null,
        val accessibility: Boolean = false,
    ) {
        fun takeSnapshot(): PendingSnapshot? = snapshot.also { snapshot = null }
    }

    // region Lifecycle

    /** The app is on the screen: connect with [settings] (MOB-8). */
    fun start(settings: Settings) = loop.post {
        this.settings = settings
        if (machine.passiveMode != settings.passiveMode) machine = AssistantStateMachine(settings.passiveMode)
        started = true
        openSession()
        refresh()
    }

    /** The app left the screen: everything stops, the connection is closed (MOB-8, MOB-14). */
    fun stop() = loop.post {
        interruptAll()
        started = false
        session.close()
        refresh()
    }

    /** The settings screen is open: the assistant rests in S1 (MOB-9). */
    fun pause() = loop.post {
        interruptAll()
        paused = true
        refresh()
    }

    /** Back from the settings with [settings]: the passive mode and the connection follow them. */
    fun resume(settings: Settings) = loop.post {
        val reconnect = settings.serverUrl != this.settings.serverUrl ||
            settings.deviceToken != this.settings.deviceToken ||
            settings.participantLabel != this.settings.participantLabel
        this.settings = settings
        paused = false
        if (machine.passiveMode != settings.passiveMode) {
            machine = AssistantStateMachine(settings.passiveMode)
        }
        if (started && reconnect) openSession()
        refresh()
    }

    /** Camera and microphone permissions are granted, or not (MOB-15). */
    fun onPermissions(granted: Boolean) = loop.post {
        permissionsReady = granted
        refresh()
    }

    private fun openSession() {
        val url = SessionUrl.resolve(settings.serverUrl)
        if (url == null) {
            session.close()
            return
        }
        session.open(url, settings.deviceToken) {
            Hello(
                protocol = PROTOCOL_VERSION,
                deviceId = settings.deviceId,
                appVersion = appVersion,
                locale = PROTOCOL_LOCALE,
                platform = platform,
                network = network(),
                label = settings.participantLabel.ifBlank { null },
            )
        }
    }

    // endregion

    // region Gestures (MOB-3)

    /** `ACTION_DOWN` at [timeMs] of the monotonic clock: the snapshot starts to be taken at once (MOB-5). */
    fun onTouchDown(x: Float, y: Float, timeMs: Long) = loop.post {
        discardTouchSnapshot()
        holdTimer?.cancel()
        val gesture = classifier.onDown(x, y, timeMs)
        if (gesture != null) {
            onGesture(gesture, timeMs)
            return@post
        }
        if (permissionsReady && !paused) touchSnapshot = camera.capture(config.imageMaxSide, config.jpegQuality)
        classifier.holdDeadlineMs?.let { deadlineMs ->
            holdTimer = loop.schedule(deadlineMs - nowMs()) {
                classifier.onTimer(nowMs())?.let { onGesture(it, nowMs()) }
            }
        }
    }

    /** `ACTION_MOVE`. */
    fun onTouchMove(x: Float, y: Float, timeMs: Long) = loop.post {
        classifier.onMove(x, y, timeMs)?.let { onGesture(it, timeMs) }
    }

    /** `ACTION_UP`. */
    fun onTouchUp(x: Float, y: Float, timeMs: Long) = loop.post {
        holdTimer?.cancel()
        classifier.onUp(x, y, timeMs)?.let { onGesture(it, timeMs) }
        discardTouchSnapshot()
    }

    /** `ACTION_CANCEL`. */
    fun onTouchCancel() = loop.post {
        holdTimer?.cancel()
        classifier.onCancel()?.let { onGesture(it, nowMs()) }
        discardTouchSnapshot()
    }

    private fun onGesture(gesture: GestureClassifier.Gesture, timeMs: Long) {
        if (paused) return
        val nanos = timeMs * NANOS_PER_MILLI
        when (gesture) {
            GestureClassifier.Gesture.TAP -> {
                val snapshot = takeTouchSnapshot()
                if (requireReady(nanos)) dispatch(Event.Tap, Trigger(nanos, snapshot)) else snapshot?.discard()
            }
            GestureClassifier.Gesture.DOUBLE_TAP -> doubleTap(nanos, afterTap = true)
            GestureClassifier.Gesture.HOLD_START -> {
                val snapshot = takeTouchSnapshot()
                if (requireReady(nanos)) dispatch(Event.HoldStarted, Trigger(nanos, snapshot)) else snapshot?.discard()
            }
            GestureClassifier.Gesture.HOLD_END -> holdEnded(nanos)
            GestureClassifier.Gesture.SWIPE_UP -> {
                discardTouchSnapshot()
                dispatch(Event.SwipeUp, Trigger(nanos))
            }
            GestureClassifier.Gesture.SWIPE_DOWN -> {
                discardTouchSnapshot()
                dispatch(Event.Stop, Trigger(nanos))
            }
        }
    }

    /**
     * Double tap: the second touch cancels the description the first tap started (MOB-3).
     * KM-2 has no way from S3 back to S1, so the automaton starts over in S1 with the same passive mode.
     */
    private fun doubleTap(nanos: Long, afterTap: Boolean) {
        val request = current
        if (afterTap && request != null && request.id == lastTapRequestId) {
            playback.stop()
            playbackId++
            cancelCurrent()
            machine = AssistantStateMachine(machine.passiveMode)
        }
        lastTapRequestId = null
        if (lastAnswer == null && (machine.state == State.IDLE || machine.state == State.LISTENING)) {
            // Nothing to repeat yet
            signals.signal(Earcon.UNAVAILABLE, nanos, vibrate = true)
            refresh()
            return
        }
        dispatch(Event.DoubleTap, Trigger(nanos))
    }

    private fun holdEnded(nanos: Long) {
        when (endpointer.release()) {
            is SpeechEndpointer.Output.QuestionEnded -> questionEnded(nanos)
            SpeechEndpointer.Output.NoSpeech -> dispatch(Event.HoldEndedWithoutSpeech, Trigger(nanos))
            else -> Unit
        }
    }

    /** A gesture that needs the server: without the permissions or the connection a phrase says why at once (MOB-8). */
    private fun requireReady(nanos: Long): Boolean {
        val notice = when {
            !permissionsReady -> Notice.PermissionDenied
            !session.connected -> Notice.NoConnection
            else -> return true
        }
        signals.signal(Earcon.ERROR, nanos, vibrate = true)
        announcer.announce(notice)
        return false
    }

    private fun takeTouchSnapshot(): PendingSnapshot? = touchSnapshot.also { touchSnapshot = null }

    private fun discardTouchSnapshot() {
        touchSnapshot?.discard()
        touchSnapshot = null
    }

    // endregion

    // region TalkBack (MOB-10)

    /** TalkBack double tap on the surface: describe the scene. */
    fun onAccessibilityDescribe() = loop.post {
        if (paused) return@post
        val nanos = loop.nanoTime()
        if (!requireReady(nanos)) return@post
        dispatch(Event.Tap, Trigger(nanos, camera.capture(config.imageMaxSide, config.jpegQuality)))
    }

    /** Action «Задать вопрос»: E10, the question is ended by the speech detector as in the window after an answer. */
    fun onAccessibilityAsk() = loop.post {
        if (paused) return@post
        val nanos = loop.nanoTime()
        if (!requireReady(nanos)) return@post
        dispatch(Event.HoldStarted, Trigger(nanos, accessibility = true))
    }

    /** Action «Повторить»: E12. */
    fun onAccessibilityRepeat() = loop.post {
        if (!paused) doubleTap(loop.nanoTime(), afterTap = false)
    }

    /** Action «Пассивный режим»: E14. */
    fun onAccessibilityPassive() = loop.post {
        if (!paused) dispatch(Event.SwipeUp, Trigger(loop.nanoTime()))
    }

    /** Action «Стоп»: E15. */
    fun onAccessibilityStop() = loop.post {
        if (!paused) dispatch(Event.Stop, Trigger(loop.nanoTime()))
    }

    // endregion

    // region Microphone (MOB-6)

    /** 100 ms of the microphone, PCM 16 kHz mono, and whether the speech detector heard speech in it. */
    fun onAudio(pcm: ShortArray, speech: Boolean) = loop.post {
        if (!micOn) return@post
        for (output in endpointer.feed(pcm, speech)) {
            when (output) {
                is SpeechEndpointer.Output.SpeechStarted -> windowQuestionStarted(output.preroll)
                is SpeechEndpointer.Output.Audio -> current?.takeIf { it.question }?.let { sendAudio(it, output.pcm) }
                is SpeechEndpointer.Output.QuestionEnded -> questionEnded(loop.nanoTime())
                SpeechEndpointer.Output.ListenTimeout -> dispatch(Event.ListenTimeout, Trigger(loop.nanoTime()))
                SpeechEndpointer.Output.NoSpeech -> dispatch(Event.HoldEndedWithoutSpeech, Trigger(loop.nanoTime()))
            }
        }
    }

    /** Speech started in the window: `ask.start`, the snapshot and the audio before the detector fired. */
    private fun windowQuestionStarted(preroll: ShortArray) {
        val request = newRequest(RequestKind.ASK)
        val snapshot = camera.capture(config.imageMaxSide, config.jpegQuality).await(SNAPSHOT_TIMEOUT_MS)
        if (!sendAskStart(request, AskTrigger.LISTEN_WINDOW, snapshot)) return
        sendAudio(request, preroll)
    }

    private fun questionEnded(nanos: Long) {
        val request = current?.takeIf { it.question }
        request?.metrics?.speechEnded(nanos)
        val result = dispatch(Event.QuestionAsked, Trigger(nanos))
        if (result.ignored) return
        if (request == null) {
            failLater(Notice.NoConnection)
            return
        }
        if (!session.send(AskEnd(request.id, request.audioMs))) {
            failLater(Notice.NoConnection)
            return
        }
        request.metrics.inputSent(loop.nanoTime())
        watchDeadline(request)
    }

    private fun startWindow() {
        endpointer.startWindow()
        micOn(true)
    }

    private fun startHold(pending: PendingSnapshot?) {
        val request = newRequest(RequestKind.ASK)
        endpointer.startHold()
        val snapshot = pending?.await(SNAPSHOT_TIMEOUT_MS)
        if (!sendAskStart(request, AskTrigger.HOLD, snapshot)) return
        micOn(true)
    }

    private fun sendAskStart(request: Request, trigger: AskTrigger, snapshot: Snapshot?): Boolean {
        snapshot?.let { request.metrics.captureRequested(it.requestedNanos).frameCaptured(it.capturedNanos).jpegEncoded(it.encodedNanos) }
        val sent = session.send(AskStart(request.id, trigger, withImage = snapshot != null)) &&
            (snapshot == null || session.send(Frame(FrameKind.IMAGE_JPEG, request.id, 0, snapshot.jpeg)))
        if (!sent) failLater(Notice.NoConnection)
        return sent
    }

    private fun sendAudio(request: Request, pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(pcm)
        session.send(Frame(FrameKind.AUDIO_IN, request.id, request.audioSeq++, bytes.array()))
        request.audioSamples += pcm.size
    }

    private fun micOn(on: Boolean) {
        if (micOn == on) return
        micOn = on
        if (on) microphone.start() else microphone.stop()
    }

    // endregion

    // region Session (PROTO-5 - PROTO-8)

    override fun onWelcome(welcome: Welcome) = loop.post {
        config = welcome.config
        if (!micOn) endpointer = SpeechEndpointer(config.listenWindowMs.toLong(), config.maxQuestionMs.toLong(), MIC_SAMPLE_RATE)
        passiveTimer.intervalMs = config.passiveIntervalMs.toLong()
        refresh()
    }

    override fun onDisconnected() = loop.post {
        val request = current
        if (request != null && !request.ended) fail(Notice.NoConnection)
        refresh()
    }

    override fun onMessage(message: ServerMessage) = loop.post {
        val requestId = message.requestId
        if (requestId == null) {
            if (message is ErrorMessage && message.code == ErrorCode.UNSUPPORTED_PROTOCOL) {
                announcer.announce(Notice.Error(message.code, question = false))
            }
            return@post
        }
        // PROTO-8: messages of other requests are ignored
        val request = current?.takeIf { it.id == requestId } ?: return@post
        when (message) {
            is Accepted -> request.metrics.accepted(loop.nanoTime())
            is Transcript -> transcript(message)
            is AnswerStart -> answerStart(request, message)
            is AnswerText -> answerText(request, message)
            is AnswerEnd -> answerEnd(request, message)
            is PassiveClear -> {
                finishCurrent(Outcome.PASSIVE_CLEAR)
                dispatch(Event.PassiveClear, Trigger(loop.nanoTime()))
            }
            is ErrorMessage -> fail(Notice.Error(message.code, request.question))
            is Welcome -> Unit
        }
    }

    override fun onFrame(frame: Frame) = loop.post {
        if (frame.kind != FrameKind.AUDIO_OUT) return@post
        val request = current?.takeIf { it.id == frame.requestId && it.audioFormat != null && !it.ended } ?: return@post
        request.pcm.write(frame.payload, 0, frame.payload.size)
        playback.audio(playbackId, frame.payload)
    }

    private fun transcript(message: Transcript) {
        val command = when (message.intent) {
            Intent.REPEAT -> Event.Command.REPEAT
            Intent.STOP -> Event.Command.STOP
            Intent.PASSIVE_ON -> Event.Command.PASSIVE_ON
            Intent.PASSIVE_OFF -> Event.Command.PASSIVE_OFF
            else -> return
        }
        // The command is done by the app; the empty answer.end of the request is not needed
        finishCurrent(Outcome.CANCELLED)
        dispatch(Event.AppCommand(command), Trigger(loop.nanoTime()))
    }

    private fun answerStart(request: Request, message: AnswerStart) {
        val event = when (machine.state) {
            State.PASSIVE -> if (message.kind == Answer.Kind.WARNING) Event.HazardFound else null
            State.ANALYZING -> Event.DescriptionReady
            State.ASKING -> Event.AnswerReady
            else -> null
        } ?: return
        if (dispatch(event, Trigger(loop.nanoTime())).ignored) return
        deadline?.cancel()
        request.answerKind = message.kind
        request.audioFormat = message.audio
        playbackId++
        if (message.kind == Answer.Kind.WARNING) signals.signal(Earcon.HAZARD, loop.nanoTime(), vibrate = true)
        mutableScreen.update { it.copy(answerText = "") }
        playback.begin(playbackId, message.audio?.sampleRate)
    }

    private fun answerText(request: Request, message: AnswerText) {
        if (request.answerKind == null || request.ended) return
        request.sentences += message.text
        mutableScreen.update { it.copy(answerText = request.sentences.joinToString(" ")) }
        playback.text(playbackId, message.text)
    }

    private fun answerEnd(request: Request, message: AnswerEnd) {
        val kind = request.answerKind
        if (kind == null) {
            finishCurrent(Outcome.CANCELLED)
            return
        }
        request.ended = true
        message.timings.forEach { (stage, ms) -> journal.record(stage, ms) }
        val audio = request.audioFormat?.let { Answer.Audio(it.sampleRate, it.channels, request.pcm.toByteArray()) }
        if (message.text.isNotEmpty()) {
            lastAnswer = Answer(request.id.toString(), kind, message.text, audio)
            mutableScreen.update { it.copy(answerText = message.text) }
        }
        playback.end(playbackId)
    }

    // endregion

    // region Playback (A5, A10)

    override fun onFirstAudio(id: Long, nanos: Long) = loop.post {
        if (id == playbackId) current?.metrics?.firstAudioStarted(nanos)
    }

    override fun onPlaybackFinished(id: Long, underruns: Int) = loop.post {
        if (id != playbackId) return@post
        val request = current
        if (request != null) {
            if (!request.ended) return@post
            request.metrics.playbackUnderruns(underruns)
            finishCurrent(Outcome.PLAYED)
        }
        dispatch(Event.PlaybackFinished, Trigger(loop.nanoTime()))
    }

    // endregion

    // region Passive mode (MOB-14)

    private fun onPassiveTimer() {
        if (passiveTimerActive()) dispatch(Event.PassiveTimer, Trigger(loop.nanoTime()))
    }

    private fun passiveTimerActive(): Boolean =
        machine.passiveTimerActive && started && !paused && permissionsReady && session.connected

    // endregion

    // region Automaton

    private fun dispatch(event: Event, trigger: Trigger): AssistantStateMachine.Result {
        val result = machine.handle(event)
        for (action in result.actions) perform(action, event, result, trigger)
        trigger.takeSnapshot()?.discard()
        refresh()
        return result
    }

    private fun perform(action: Action, event: Event, result: AssistantStateMachine.Result, trigger: Trigger) {
        when (action) {
            Action.CONFIRM_GESTURE -> signals.signal(earconOf(event), trigger.eventNanos, vibrate = true)
            Action.SIGNAL_UNAVAILABLE -> signals.signal(Earcon.UNAVAILABLE, trigger.eventNanos, vibrate = true)
            Action.TAKE_PHOTO -> when (event) {
                Event.PassiveTimer -> describe(DescribeMode.PASSIVE, null, trigger)
                // The snapshot of a question went with ask.start
                Event.QuestionAsked -> Unit
                else -> describe(DescribeMode.ON_DEMAND, trigger.takeSnapshot(), trigger)
            }
            Action.CANCEL_PASSIVE -> cancelCurrent()
            Action.INTERRUPT -> {
                playback.stop()
                playbackId++
                cancelCurrent()
            }
            Action.MIC_ON -> startWindow()
            Action.MIC_ON_HOLD -> if (trigger.accessibility) startWindow() else startHold(trigger.takeSnapshot())
            Action.RECORD_HOLD -> if (!trigger.accessibility) {
                if (current?.question == true) cancelCurrent()
                startHold(trigger.takeSnapshot())
            }
            Action.MIC_OFF -> micOff(event)
            Action.REPEAT_LAST -> repeatLast()
            Action.REPORT_ERROR -> reportError(trigger.notice ?: Notice.NoConnection)
            Action.SET_PASSIVE_MODE -> {
                val enabled = result.passiveMode
                scope.launch { passiveSetting.set(enabled) }
                announcer.announce(Notice.PassiveMode(enabled))
            }
            // Activities of the states and the work of the gateway
            Action.FIND_HAZARDS,
            Action.ANALYZE_SCENE,
            Action.COMPOSE_DESCRIPTION,
            Action.SPEAK,
            Action.ANALYZE_WITH_QUESTION,
            -> Unit
        }
    }

    private fun earconOf(event: Event): Earcon = when (event) {
        Event.HoldStarted -> Earcon.LISTEN_START
        Event.HoldEndedWithoutSpeech -> Earcon.LISTEN_END
        Event.DoubleTap -> Earcon.DOUBLE_TAP
        else -> Earcon.TAP
    }

    private fun micOff(event: Event) {
        val wasOn = micOn
        micOn(false)
        if (event == Event.QuestionAsked || event == Event.ListenTimeout) {
            if (wasOn) signals.signal(Earcon.LISTEN_END, loop.nanoTime(), vibrate = false)
        } else {
            endpointer.stop()
            // The question being recorded is dropped
            if (current?.question == true) cancelCurrent()
        }
    }

    private fun describe(mode: DescribeMode, pending: PendingSnapshot?, trigger: Trigger) {
        val passive = mode == DescribeMode.PASSIVE
        val request = newRequest(if (passive) RequestKind.DESCRIBE_PASSIVE else RequestKind.DESCRIBE_ON_DEMAND)
        if (!passive) {
            request.metrics.touchDown(trigger.eventNanos)
            lastTapRequestId = request.id
        }
        val snapshot = (pending ?: camera.capture(config.imageMaxSide, config.jpegQuality)).await(SNAPSHOT_TIMEOUT_MS)
        if (snapshot == null) {
            if (passive) {
                finishCurrent(Outcome.CANCELLED)
                loop.post { dispatch(Event.PassiveClear, Trigger(loop.nanoTime())) }
            } else {
                failLater(Notice.CameraUnavailable)
            }
            return
        }
        request.metrics
            .captureRequested(snapshot.requestedNanos)
            .frameCaptured(snapshot.capturedNanos)
            .jpegEncoded(snapshot.encodedNanos)
        val describe = Describe(request.id, mode, Focus.ALL, ImageInfo(snapshot.width, snapshot.height, snapshot.jpeg.size))
        val sent = session.send(describe) && session.send(Frame(FrameKind.IMAGE_JPEG, request.id, 0, snapshot.jpeg))
        if (!sent) {
            failLater(Notice.NoConnection)
            return
        }
        request.metrics.inputSent(loop.nanoTime())
        watchDeadline(request)
    }

    private fun repeatLast() {
        playback.stop()
        val id = ++playbackId
        val answer = lastAnswer
        if (answer == null) {
            loop.post { onPlaybackFinished(id, 0) }
            return
        }
        mutableScreen.update { it.copy(answerText = answer.text) }
        playback.replay(id, answer)
    }

    private fun reportError(notice: Notice) {
        playback.stop()
        playbackId++
        micOn(false)
        endpointer.stop()
        signals.signal(Earcon.ERROR, loop.nanoTime(), vibrate = true)
        announcer.announce(notice)
        finishCurrent(Outcome.ERROR)
    }

    /** E13 with the phrase of [notice]. */
    private fun fail(notice: Notice) {
        val result = dispatch(Event.Failure, Trigger(loop.nanoTime(), notice = notice))
        if (!result.ignored) return
        // S1 and S5 have no E13 in KM-2, but a question being recorded in S5 can still fail
        if (current == null && !micOn) return
        finishCurrent(Outcome.ERROR)
        if (machine.state == State.LISTENING) dispatch(Event.ListenTimeout, Trigger(loop.nanoTime()))
        signals.signal(Earcon.ERROR, loop.nanoTime(), vibrate = true)
        announcer.announce(notice)
    }

    /** E13 after the current task: a failure found while performing a transition. */
    private fun failLater(notice: Notice) {
        val request = current
        loop.post { if (current === request) fail(notice) }
    }

    /** The user hears an answer or an error not later than the deadline of the request (NFR-4). */
    private fun watchDeadline(request: Request) {
        deadline?.cancel()
        deadline = loop.schedule(REQUEST_DEADLINE_MS) {
            if (current === request && request.answerKind == null) fail(Notice.Error(ErrorCode.UPSTREAM_TIMEOUT, request.question))
        }
    }

    private fun newRequest(kind: RequestKind): Request {
        if (current != null) finishCurrent(Outcome.CANCELLED)
        val request = Request(kind, ClientMetricsBuilder(UUID.randomUUID(), kind))
        current = request
        return request
    }

    private fun cancelCurrent() {
        val request = current ?: return
        if (!request.ended) session.send(Cancel(request.id))
        finishCurrent(Outcome.CANCELLED)
    }

    /** Ends the current request; `client.metrics` goes out after an answer or an error (MOB-13). */
    private fun finishCurrent(outcome: Outcome) {
        val request = current ?: return
        current = null
        deadline?.cancel()
        deadline = null
        val metrics = request.metrics.finish(outcome, network()) ?: return
        session.send(metrics)
        with(metrics.metrics) {
            listOf(
                "capture_ms" to captureMs,
                "encode_ms" to encodeMs,
                "trigger_to_send_ms" to triggerToSendMs,
                "trigger_to_accepted_ms" to triggerToAcceptedMs,
                "trigger_to_first_audio_ms" to triggerToFirstAudioMs,
                "playback_underruns" to playbackUnderruns?.toLong(),
            ).forEach { (name, value) -> value?.let { journal.record(name, it.toDouble()) } }
        }
    }

    private fun interruptAll() {
        holdTimer?.cancel()
        discardTouchSnapshot()
        playback.stop()
        playbackId++
        micOn(false)
        endpointer.stop()
        cancelCurrent()
        machine = AssistantStateMachine(machine.passiveMode)
    }

    private fun refresh() {
        val status = when {
            !session.connected -> Status.OFFLINE
            else -> when (machine.state) {
                State.IDLE, State.PASSIVE -> Status.IDLE
                State.ANALYZING, State.ASKING -> Status.LOOKING
                State.SPEAKING, State.ANSWERING -> Status.SPEAKING
                State.LISTENING -> Status.LISTENING
            }
        }
        mutableScreen.update { it.copy(status = status, passiveMode = machine.passiveMode) }
        passiveTimer.update(passiveTimerActive())
    }

    private fun nowMs(): Long = loop.nanoTime() / NANOS_PER_MILLI

    // endregion

    companion object {
        /** Sample rate of the microphone, Hz (MOB-6). */
        const val MIC_SAMPLE_RATE = 16_000

        /** Longest wait for a snapshot, ms. */
        const val SNAPSHOT_TIMEOUT_MS = 1_000L

        /**
         * Without `answer.start` this long after the input was sent the request fails with
         * «Сервер не ответил вовремя»: the gateway deadline (8 s, NFR-4) and a margin for the network.
         */
        const val REQUEST_DEADLINE_MS = 10_000L

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L

        /** Settings before the first `welcome`: the defaults of the gateway (PROTO-5). */
        val DEFAULT_CONFIG = ClientConfig(
            imageMaxSide = 640,
            jpegQuality = 80,
            passiveIntervalMs = 3_000,
            listenWindowMs = 5_000,
            maxQuestionMs = 15_000,
            audioIn = AudioFormat(AudioEncoding.PCM_S16LE, MIC_SAMPLE_RATE, 1),
            audioOut = AudioFormat(AudioEncoding.PCM_S16LE, 24_000, 1),
        )
    }
}
