package io.github.nikarpoff.alv.model

import io.github.nikarpoff.alv.model.protocol.AnswerEnd
import io.github.nikarpoff.alv.model.protocol.AnswerStart
import io.github.nikarpoff.alv.model.protocol.ClientMessage
import io.github.nikarpoff.alv.model.protocol.ClientMetricValues
import io.github.nikarpoff.alv.model.protocol.ClientMetrics
import io.github.nikarpoff.alv.model.protocol.DegradedReason
import io.github.nikarpoff.alv.model.protocol.ErrorMessage
import io.github.nikarpoff.alv.model.protocol.FRAME_HEADER_SIZE
import io.github.nikarpoff.alv.model.protocol.Frame
import io.github.nikarpoff.alv.model.protocol.FrameKind
import io.github.nikarpoff.alv.model.protocol.Hello
import io.github.nikarpoff.alv.model.protocol.Intent
import io.github.nikarpoff.alv.model.protocol.Network
import io.github.nikarpoff.alv.model.protocol.ProtocolCodec
import io.github.nikarpoff.alv.model.protocol.ProtocolException
import io.github.nikarpoff.alv.model.protocol.ServerMessage
import io.github.nikarpoff.alv.model.protocol.Welcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class ProtocolCodecTest {
    private val vectors: JsonObject = run {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("protocol/vectors.json")) { "vectors.json not found" }
        Json.parseToJsonElement(stream.use { it.readBytes().decodeToString() }).jsonObject
    }

    private fun section(name: String): List<JsonObject> = vectors.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun assertBadRequest(name: String, block: () -> Unit) {
        try {
            block()
            fail("$name: expected bad_request")
        } catch (e: ProtocolException) {
            assertEquals(name, ErrorCode.BAD_REQUEST, e.code)
        }
    }

    /** JSON trees are equal; numbers are compared by value, so 1830 equals 1830.0. */
    private fun assertJsonEquals(name: String, expected: JsonElement, actual: JsonElement) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                assertEquals("$name keys", expected.keys, actual.keys)
                expected.keys.forEach { assertJsonEquals("$name.$it", expected.getValue(it), actual.getValue(it)) }
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals("$name size", expected.size, actual.size)
                expected.indices.forEach { assertJsonEquals("$name[$it]", expected[it], actual[it]) }
            }
            expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString &&
                expected != JsonNull && actual != JsonNull &&
                expected.content.toDoubleOrNull() != null && actual.content.toDoubleOrNull() != null ->
                assertEquals(name, expected.content.toDouble(), actual.content.toDouble(), 0.0)
            else -> assertEquals(name, expected, actual)
        }
    }

    @Test
    fun framesEncodeToVectors() {
        val frames = section("frames")
        assertTrue(frames.isNotEmpty())
        for (vector in frames) {
            val name = vector.string("name")
            assertEquals(name, 1, vector.getValue("version").jsonPrimitive.int)
            val kind = checkNotNull(FrameKind.fromCode(vector.getValue("kind").jsonPrimitive.int)) { name }
            assertEquals(name, vector.string("kind_name"), kind.name)
            val frame = Frame(
                kind = kind,
                requestId = UUID.fromString(vector.string("request_id")),
                seq = vector.getValue("seq").jsonPrimitive.long,
                payload = hex(vector.string("payload_hex")),
            )

            assertEquals(name, vector.string("frame_hex"), hex(ProtocolCodec.encodeFrame(frame)))
        }
        assertEquals(FrameKind.entries.toSet(), frames.map { FrameKind.fromCode(it.getValue("kind").jsonPrimitive.int) }.toSet())
    }

    @Test
    fun framesDecodeFromVectors() {
        for (vector in section("frames")) {
            val name = vector.string("name")

            val frame = ProtocolCodec.decodeFrame(hex(vector.string("frame_hex")))

            assertEquals(name, vector.getValue("kind").jsonPrimitive.int, frame.kind.code)
            assertEquals(name, UUID.fromString(vector.string("request_id")), frame.requestId)
            assertEquals(name, vector.getValue("seq").jsonPrimitive.long, frame.seq)
            assertArrayEquals(name, hex(vector.string("payload_hex")), frame.payload)
        }
    }

    @Test
    fun proto3ReferenceVector() {
        val frame = Frame(FrameKind.IMAGE_JPEG, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), 0, byteArrayOf(-1, -40, -1))

        assertEquals("0101123e4567e89b12d3a45642661417400000000000ffd8ff", hex(ProtocolCodec.encodeFrame(frame)))
    }

    @Test
    fun invalidFramesAreBadRequest() {
        val invalid = section("invalid_frames")
        assertTrue(invalid.isNotEmpty())
        for (vector in invalid) {
            val name = vector.string("name")
            assertEquals(name, ErrorCode.BAD_REQUEST.wire, vector.string("error"))

            assertBadRequest(name) { ProtocolCodec.decodeFrame(hex(vector.string("frame_hex"))) }
        }
    }

    @Test
    fun emptyAndHeaderOnlyFrames() {
        assertBadRequest("empty") { ProtocolCodec.decodeFrame(ByteArray(0)) }
        assertBadRequest("21 bytes") { ProtocolCodec.decodeFrame(ByteArray(FRAME_HEADER_SIZE - 1) { 1 }) }

        val header = ByteArray(FRAME_HEADER_SIZE).also {
            it[0] = 1
            it[1] = 0x81.toByte()
            it.fill(0xFF.toByte(), 18, 22)
        }
        val frame = ProtocolCodec.decodeFrame(header)
        assertEquals(FrameKind.AUDIO_OUT, frame.kind)
        assertEquals(0xFFFF_FFFFL, frame.seq)
        assertEquals(0, frame.payload.size)
        assertArrayEquals(header, ProtocolCodec.encodeFrame(frame))
    }

    @Test
    fun clientMessagesRoundTrip() {
        val client = section("messages").filter { it.string("direction") == "client" }
        assertTrue(client.isNotEmpty())
        for (vector in client) {
            val name = vector.string("name")
            val expected = vector.getValue("message").jsonObject

            val message = ProtocolCodec.decodeMessage(expected.toString())
            val text = ProtocolCodec.encodeMessage(message)

            assertTrue(name, message is ClientMessage)
            assertTrue("$name: type first in $text", text.startsWith("{\"type\":\"${expected.string("type")}\""))
            assertJsonEquals(name, expected, Json.parseToJsonElement(text))
        }
    }

    @Test
    fun serverMessagesDecode() {
        val server = section("messages").filter { it.string("direction") == "server" }
        assertTrue(server.isNotEmpty())
        for (vector in server) {
            val name = vector.string("name")
            val expected = vector.getValue("message").jsonObject

            val message = ProtocolCodec.decodeServerMessage(expected.toString())

            val requestId = expected["request_id"]
            if (requestId == null || requestId == JsonNull) {
                assertNull(name, message.requestId)
            } else {
                assertEquals(name, UUID.fromString(requestId.jsonPrimitive.content), message.requestId)
            }
            assertJsonEquals(name, expected, Json.parseToJsonElement(ProtocolCodec.encodeMessage(message)))
        }
    }

    @Test
    fun vectorsCoverEveryMessageType() {
        val types = section("messages").map { it.getValue("message").jsonObject.string("type") }.toSet()

        assertEquals(
            setOf(
                "hello", "describe", "ask.start", "ask.end", "cancel", "client.metrics",
                "welcome", "accepted", "transcript", "answer.start", "answer.text", "answer.end", "passive.clear", "error",
            ),
            types,
        )
        val codes = section("messages").map { it.getValue("message").jsonObject }
            .filter { it.string("type") == "error" }
            .map { ErrorCode.fromWire(it.string("code")) }
            .toSet()
        assertEquals(ErrorCode.entries.toSet(), codes)
    }

    @Test
    fun invalidMessagesAreBadRequest() {
        val invalid = section("invalid_messages")
        assertTrue(invalid.isNotEmpty())
        for (vector in invalid) {
            val name = vector.string("name")
            val text = vector.getValue("message").toString()
            assertEquals(name, ErrorCode.BAD_REQUEST.wire, vector.string("error"))

            assertBadRequest(name) { ProtocolCodec.decodeMessage(text) }
            assertBadRequest(name) { ProtocolCodec.decodeServerMessage(text) }
        }
    }

    @Test
    fun brokenMessagesAreBadRequest() {
        val cases = mapOf(
            "not json" to "hello",
            "array" to "[]",
            "app message from the gateway" to
                """{"type":"cancel","request_id":"123e4567-e89b-12d3-a456-426614174000"}""",
            "missing field" to """{"type":"accepted"}""",
            "bad uuid" to """{"type":"accepted","request_id":"123"}""",
            "unknown error code" to
                """{"type":"error","request_id":null,"code":"teapot","message":"x","retryable":false}""",
            "unknown kind" to
                """{"type":"answer.start","request_id":"123e4567-e89b-12d3-a456-426614174000","kind":"poem","priority":"normal","audio":null}""",
            "unknown clear reason" to
                """{"type":"passive.clear","request_id":"123e4567-e89b-12d3-a456-426614174000","reason":"maybe"}""",
        )
        for ((name, text) in cases) {
            assertBadRequest(name) { ProtocolCodec.decodeServerMessage(text) }
        }
    }

    @Test
    fun serverMessageFields() {
        val start = ProtocolCodec.decodeServerMessage(
            """{"type":"answer.start","request_id":"123e4567-e89b-12d3-a456-426614174000","kind":"warning","priority":"high","audio":null}""",
        ) as AnswerStart
        assertEquals(Answer.Kind.WARNING, start.kind)
        assertNull(start.audio)

        val endVector = section("messages").first { it.string("name") == "answer.end" }.getValue("message").toString()
        val end = ProtocolCodec.decodeServerMessage(endVector) as AnswerEnd
        assertEquals(Intent.DESCRIBE, end.intent)
        assertEquals(DegradedReason.NONE, end.degradedReason)
        assertEquals(583.0, end.timings.getValue("llm_ttft_ms"), 0.0)
        val scene = end.toScene()
        assertEquals(end.text, scene.text)
        assertEquals(
            listOf(SceneObject("chair", "стул", SceneObject.Direction.LEFT, SceneObject.Distance.NEAR, false)),
            scene.objects,
        )

        val welcomeVector = section("messages").first { it.string("name") == "welcome" }.getValue("message").toString()
        val welcome = ProtocolCodec.decodeServerMessage(welcomeVector) as Welcome
        assertEquals(640, welcome.config.imageMaxSide)
        assertEquals(24000, welcome.config.audioOut.sampleRate)

        val error = ProtocolCodec.decodeServerMessage(
            section("messages").first { it.string("name") == "error.bad_request" }.getValue("message").toString(),
        ) as ErrorMessage
        assertNull(error.requestId)
        assertEquals(ErrorCode.BAD_REQUEST, error.code)
        assertFalse(error.retryable)
        assertTrue(error is ServerMessage)
    }

    @Test
    fun optionalFieldsAreNotWritten() {
        val hello = Hello(1, "5f0c3a52-8a0b-4d4e-9c61-3f1d2b7a9e10", "0.1.0", "ru-RU", "android-34", Network.WIFI)
        assertFalse(ProtocolCodec.encodeMessage(hello).contains("label"))
        assertTrue(ProtocolCodec.encodeMessage(hello.copy(label = "P07")).contains("\"label\":\"P07\""))

        val metrics = ClientMetrics(
            requestId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            metrics = ClientMetricValues(captureMs = 34, playbackUnderruns = 0),
            network = Network.CELLULAR,
        )
        assertEquals(
            """{"type":"client.metrics","request_id":"123e4567-e89b-12d3-a456-426614174000",""" +
                """"metrics":{"capture_ms":34,"playback_underruns":0},"network":"cellular"}""",
            ProtocolCodec.encodeMessage(metrics),
        )
    }
}
