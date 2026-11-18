package io.github.nikarpoff.alv.model.protocol

import java.util.UUID

/** Version of the binary frame header, the only one the protocol knows (PROTO-3). */
const val FRAME_VERSION: Int = 0x01

/** Size of the binary frame header in bytes: version, kind, request_id, seq (PROTO-3). */
const val FRAME_HEADER_SIZE: Int = 22

/** Largest `seq` of a frame: the field is uint32. */
const val FRAME_MAX_SEQ: Long = 0xFFFF_FFFFL

/**
 * Kind of a binary frame and its payload (PROTO-3).
 *
 * @property code value of the `kind` byte
 */
enum class FrameKind(val code: Int) {
    /** Whole JPEG snapshot in one frame, app to gateway. */
    IMAGE_JPEG(0x01),

    /** Chunk of the question, PCM s16le mono 16 kHz, app to gateway. */
    AUDIO_IN(0x02),

    /** Chunk of the answer, PCM s16le mono at the rate of `answer.start`, gateway to app. */
    AUDIO_OUT(0x81),
    ;

    companion object {
        /** Kind by its byte value, or `null` for a value the protocol does not know. */
        fun fromCode(code: Int): FrameKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Binary frame of the protocol (PROTO-3).
 *
 * @property kind kind of the payload
 * @property requestId request the frame belongs to, 16 bytes in network order (RFC 4122)
 * @property seq number of the chunk in the request, from 0, uint32
 * @property payload JPEG or PCM bytes
 */
class Frame(
    val kind: FrameKind,
    val requestId: UUID,
    val seq: Long,
    val payload: ByteArray,
) {
    init {
        require(seq in 0..FRAME_MAX_SEQ) { "seq $seq is out of uint32" }
    }

    override fun equals(other: Any?): Boolean =
        other is Frame &&
            kind == other.kind &&
            requestId == other.requestId &&
            seq == other.seq &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + requestId.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = "Frame(kind=$kind, requestId=$requestId, seq=$seq, payload=${payload.size} bytes)"
}
