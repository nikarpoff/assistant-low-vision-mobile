package io.github.nikarpoff.alv.model.protocol

import io.github.nikarpoff.alv.model.ErrorCode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Frame or message that breaks the protocol.
 *
 * @property code error code the gateway would answer with: `bad_request` for every decoding failure
 */
class ProtocolException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Codec of the `alv.v1` protocol: binary frames (PROTO-3) and JSON messages (PROTO-4, PROTO-5).
 *
 * It passes the same reference vectors as the gateway (`vectors.json`, AC-2).
 */
object ProtocolCodec {
    private const val OFFSET_VERSION = 0
    private const val OFFSET_KIND = 1
    private const val OFFSET_REQUEST_ID = 2
    private const val OFFSET_SEQ = 18

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        classDiscriminator = "type"
        namingStrategy = JsonNamingStrategy.SnakeCase
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /** Frame as bytes: the 22-byte header and the payload. */
    fun encodeFrame(frame: Frame): ByteArray {
        val buffer = ByteBuffer.allocate(FRAME_HEADER_SIZE + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(FRAME_VERSION.toByte())
        buffer.put(frame.kind.code.toByte())
        buffer.putLong(frame.requestId.mostSignificantBits)
        buffer.putLong(frame.requestId.leastSignificantBits)
        buffer.putInt(frame.seq.toInt())
        buffer.put(frame.payload)
        return buffer.array()
    }

    /**
     * Parses a binary frame.
     *
     * @throws ProtocolException `bad_request` for a header shorter than 22 bytes, an unknown version or kind
     */
    fun decodeFrame(data: ByteArray): Frame {
        if (data.size < FRAME_HEADER_SIZE) {
            throw ProtocolException(ErrorCode.BAD_REQUEST, "frame header needs $FRAME_HEADER_SIZE bytes, got ${data.size}")
        }
        val version = data[OFFSET_VERSION].toInt() and 0xFF
        if (version != FRAME_VERSION) {
            throw ProtocolException(ErrorCode.BAD_REQUEST, "unknown frame version 0x${hex(version)}")
        }
        val code = data[OFFSET_KIND].toInt() and 0xFF
        val kind = FrameKind.fromCode(code)
            ?: throw ProtocolException(ErrorCode.BAD_REQUEST, "unknown frame kind 0x${hex(code)}")

        val header = ByteBuffer.wrap(data, OFFSET_REQUEST_ID, FRAME_HEADER_SIZE - OFFSET_REQUEST_ID).order(ByteOrder.BIG_ENDIAN)
        val requestId = UUID(header.getLong(), header.getLong())
        check(header.position() == OFFSET_SEQ)
        val seq = header.getInt().toLong() and FRAME_MAX_SEQ

        return Frame(kind, requestId, seq, data.copyOfRange(FRAME_HEADER_SIZE, data.size))
    }

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0')

    /** Message as JSON text with the `type` field first; optional fields that are `null` are not written. */
    fun encodeMessage(message: Message): String = json.encodeToString(Message.serializer(), message)

    /**
     * Parses a message of either side.
     *
     * @throws ProtocolException `bad_request` for text that is not a JSON object, a missing or unknown `type`,
     * a missing field or a value out of its enumeration
     */
    fun decodeMessage(text: String): Message =
        try {
            json.decodeFromString(Message.serializer(), text)
        } catch (e: SerializationException) {
            throw ProtocolException(ErrorCode.BAD_REQUEST, "invalid message: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException(ErrorCode.BAD_REQUEST, "invalid message: ${e.message}", e)
        }

    /**
     * Parses a message of the gateway (PROTO-5).
     *
     * @throws ProtocolException `bad_request` as [decodeMessage] does, and for a message only the app sends
     */
    fun decodeServerMessage(text: String): ServerMessage =
        when (val message = decodeMessage(text)) {
            is ServerMessage -> message
            is ClientMessage -> throw ProtocolException(ErrorCode.BAD_REQUEST, "unexpected app message ${message::class.simpleName}")
        }
}
