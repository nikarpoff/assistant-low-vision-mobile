@file:UseSerializers(
    UuidSerializer::class,
    ErrorCodeSerializer::class,
    AnswerKindSerializer::class,
    DirectionSerializer::class,
    DistanceSerializer::class,
)

package io.github.nikarpoff.alv.model.protocol

import io.github.nikarpoff.alv.model.Answer
import io.github.nikarpoff.alv.model.ErrorCode
import io.github.nikarpoff.alv.model.Scene
import io.github.nikarpoff.alv.model.SceneObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

/** Protocol version the app sends in `hello` (PROTO-10). */
const val PROTOCOL_VERSION: Int = 1

/** Locale the app sends in `hello` (PROTO-4). */
const val PROTOCOL_LOCALE: String = "ru-RU"

/**
 * JSON message of the protocol: a text frame with the `type` field (PROTO-3).
 *
 * Field names are snake_case on the wire; the codec maps them from the camelCase properties.
 */
@Serializable
sealed interface Message

/** Message the app sends (PROTO-4). */
@Serializable
sealed interface ClientMessage : Message

/** Message the gateway sends (PROTO-5). */
@Serializable
sealed interface ServerMessage : Message {
    /** Request the message belongs to, `null` when it belongs to no request (PROTO-8). */
    val requestId: UUID?
}

/** Network of the phone. */
@Serializable
enum class Network {
    @SerialName("wifi")
    WIFI,

    @SerialName("cellular")
    CELLULAR,

    @SerialName("other")
    OTHER,
}

/** What triggered `describe`. */
@Serializable
enum class DescribeMode {
    @SerialName("on_demand")
    ON_DEMAND,

    @SerialName("passive")
    PASSIVE,
}

/** Part of the scene to describe. */
@Serializable
enum class Focus {
    @SerialName("all")
    ALL,

    @SerialName("ahead")
    AHEAD,
}

/** What started the question. */
@Serializable
enum class AskTrigger {
    @SerialName("hold")
    HOLD,

    @SerialName("listen_window")
    LISTEN_WINDOW,
}

/** Intent of the question (PLT-4); [NONE] is the empty intent the gateway may write in `answer.end`. */
@Serializable
enum class Intent {
    @SerialName("describe")
    DESCRIBE,

    @SerialName("describe_ahead")
    DESCRIBE_AHEAD,

    @SerialName("repeat")
    REPEAT,

    @SerialName("stop")
    STOP,

    @SerialName("passive_on")
    PASSIVE_ON,

    @SerialName("passive_off")
    PASSIVE_OFF,

    @SerialName("question")
    QUESTION,

    @SerialName("")
    NONE,
}

/** Priority of the answer in `answer.start`. */
@Serializable
enum class Priority {
    @SerialName("normal")
    NORMAL,

    @SerialName("high")
    HIGH,
}

/** Reason of a degraded answer in `answer.end` (PLT-9); [NONE] is an answer that is not degraded. */
@Serializable
enum class DegradedReason {
    @SerialName("")
    NONE,

    @SerialName("llm_timeout")
    LLM_TIMEOUT,

    @SerialName("llm_unavailable")
    LLM_UNAVAILABLE,

    @SerialName("tts_unavailable")
    TTS_UNAVAILABLE,
}

/** Reason of `passive.clear`. */
@Serializable
enum class ClearReason {
    @SerialName("no_hazard")
    NO_HAZARD,

    @SerialName("cooldown")
    COOLDOWN,

    @SerialName("skipped")
    SKIPPED,

    @SerialName("stale")
    STALE,
}

/** Encoding of AUDIO_IN and AUDIO_OUT payloads. */
@Serializable
enum class AudioEncoding {
    @SerialName("pcm_s16le")
    PCM_S16LE,
}

/** First message after the connection is open. */
@Serializable
@SerialName("hello")
data class Hello(
    val protocol: Int,
    val deviceId: String,
    val appVersion: String,
    val locale: String,
    /** `android-<API>`. */
    val platform: String,
    val network: Network,
    /** Trial participant code (EXP-6); not sent when `null`. */
    val label: String? = null,
) : ClientMessage

/** Asks to describe the scene; the IMAGE_JPEG frame with the same request id follows it. */
@Serializable
@SerialName("describe")
data class Describe(
    val requestId: UUID,
    val mode: DescribeMode,
    val focus: Focus,
    val image: ImageInfo,
) : ClientMessage

/** Snapshot of `describe`: size in pixels and in bytes. */
@Serializable
data class ImageInfo(
    val width: Int,
    val height: Int,
    val bytes: Int,
)

/** Starts a question; an optional IMAGE_JPEG frame and AUDIO_IN frames follow it. */
@Serializable
@SerialName("ask.start")
data class AskStart(
    val requestId: UUID,
    val trigger: AskTrigger,
    val withImage: Boolean,
) : ClientMessage

/** Ends a question. */
@Serializable
@SerialName("ask.end")
data class AskEnd(
    val requestId: UUID,
    val audioMs: Long,
) : ClientMessage

/** Cancels a request: the user interrupted the answer or started a new request. */
@Serializable
@SerialName("cancel")
data class Cancel(
    val requestId: UUID,
) : ClientMessage

/** Phone metrics after an answer or an error (PROTO-4, PLT-14). */
@Serializable
@SerialName("client.metrics")
data class ClientMetrics(
    val requestId: UUID,
    val metrics: ClientMetricValues,
    val network: Network,
) : ClientMessage

/**
 * Phone metrics (PLT-14), milliseconds. A metric the request does not have,
 * such as the first audio after an error, is `null` and is not sent.
 */
@Serializable
data class ClientMetricValues(
    val captureMs: Long? = null,
    val encodeMs: Long? = null,
    val triggerToSendMs: Long? = null,
    val triggerToAcceptedMs: Long? = null,
    val triggerToFirstAudioMs: Long? = null,
    val playbackUnderruns: Int? = null,
)

/** Answer to `hello`: the session and the settings of the app (PROTO-13). */
@Serializable
@SerialName("welcome")
data class Welcome(
    val sessionId: UUID,
    val serverVersion: String,
    val configId: String,
    val config: ClientConfig,
) : ServerMessage {
    override val requestId: UUID? get() = null
}

/** Settings the app applies on every connection (PROTO-13). */
@Serializable
data class ClientConfig(
    val imageMaxSide: Int,
    val jpegQuality: Int,
    val passiveIntervalMs: Int,
    val listenWindowMs: Int,
    val maxQuestionMs: Int,
    val audioIn: AudioFormat,
    val audioOut: AudioFormat,
)

/** Format of audio payloads. */
@Serializable
data class AudioFormat(
    val format: AudioEncoding,
    val sampleRate: Int,
    val channels: Int,
)

/** Input of the request is received completely: the snapshot of `describe` or `ask.end`. */
@Serializable
@SerialName("accepted")
data class Accepted(
    override val requestId: UUID,
) : ServerMessage

/** The question is recognized. */
@Serializable
@SerialName("transcript")
data class Transcript(
    override val requestId: UUID,
    val text: String,
    val intent: Intent,
) : ServerMessage

/** The answer starts; [audio] is `null` when the answer has no sound (`tts_unavailable`). */
@Serializable
@SerialName("answer.start")
data class AnswerStart(
    override val requestId: UUID,
    val kind: Answer.Kind,
    val priority: Priority,
    val audio: AudioFormat?,
) : ServerMessage

/** Text of a sentence, sent before its audio. */
@Serializable
@SerialName("answer.text")
data class AnswerText(
    override val requestId: UUID,
    /** Number of the sentence, from 0. */
    val seq: Int,
    val text: String,
) : ServerMessage

/** The answer is over. [text] is empty for the commands the app executes (PROTO-7). */
@Serializable
@SerialName("answer.end")
data class AnswerEnd(
    override val requestId: UUID,
    val text: String,
    val intent: Intent,
    val hazard: Boolean,
    val degraded: Boolean,
    val degradedReason: DegradedReason,
    val objects: List<ObjectInfo>,
    /** Stage (PLT-14) to milliseconds, measured by the gateway. */
    val timings: Map<String, Double>,
) : ServerMessage {
    /** Scene of the answer (KM-1). */
    fun toScene(): Scene = Scene(text = text, objects = objects.map { it.toSceneObject() })
}

/** Element of `objects` in `answer.end`. */
@Serializable
data class ObjectInfo(
    val label: String,
    val labelRu: String,
    val direction: SceneObject.Direction,
    val distance: SceneObject.Distance,
    val hazard: Boolean,
) {
    /** Scene object of the Model (KM-1). */
    fun toSceneObject(): SceneObject = SceneObject(label, labelRu, direction, distance, hazard)
}

/** Passive request ended without a warning. */
@Serializable
@SerialName("passive.clear")
data class PassiveClear(
    override val requestId: UUID,
    val reason: ClearReason,
) : ServerMessage

/**
 * Request failed or a frame or message was rejected (PROTO-11).
 *
 * @property requestId request of the error, `null` when it belongs to no request
 * @property message technical text in English, never spoken
 */
@Serializable
@SerialName("error")
data class ErrorMessage(
    override val requestId: UUID?,
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean,
) : ServerMessage

/** UUID as its canonical text `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`; any other text is rejected. */
internal object UuidSerializer : KSerializer<UUID> {
    private val canonical = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("alv.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        val text = decoder.decodeString()
        if (!canonical.matches(text)) {
            throw SerializationException("invalid uuid \"$text\"")
        }
        return UUID.fromString(text)
    }
}

/** Enum of the Model with a protocol value, written as that value. */
internal abstract class WireEnumSerializer<E : Enum<E>>(
    name: String,
    private val entries: List<E>,
    private val wire: (E) -> String,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) {
        encoder.encodeString(wire(value))
    }

    override fun deserialize(decoder: Decoder): E {
        val text = decoder.decodeString()
        return entries.firstOrNull { wire(it) == text }
            ?: throw SerializationException("unknown ${descriptor.serialName} \"$text\"")
    }
}

internal object ErrorCodeSerializer :
    WireEnumSerializer<ErrorCode>("alv.ErrorCode", ErrorCode.entries, { it.wire })

internal object AnswerKindSerializer :
    WireEnumSerializer<Answer.Kind>("alv.AnswerKind", Answer.Kind.entries, { it.wire })

internal object DirectionSerializer :
    WireEnumSerializer<SceneObject.Direction>("alv.Direction", SceneObject.Direction.entries, { it.wire })

internal object DistanceSerializer :
    WireEnumSerializer<SceneObject.Distance>("alv.Distance", SceneObject.Distance.entries, { it.wire })
