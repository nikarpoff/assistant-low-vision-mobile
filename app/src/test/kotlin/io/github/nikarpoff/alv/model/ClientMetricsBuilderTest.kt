package io.github.nikarpoff.alv.model

import io.github.nikarpoff.alv.model.ClientMetricsBuilder.Outcome
import io.github.nikarpoff.alv.model.ClientMetricsBuilder.RequestKind
import io.github.nikarpoff.alv.model.protocol.ClientMetricValues
import io.github.nikarpoff.alv.model.protocol.Network
import io.github.nikarpoff.alv.model.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ClientMetricsBuilderTest {
    private val ms = 1_000_000L
    private val requestId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    /** Every moment of a request; the trigger moments differ so that the chosen one is visible. */
    private fun builder(kind: RequestKind) = ClientMetricsBuilder(requestId, kind)
        .touchDown(1_000 * ms)
        .captureRequested(1_005 * ms)
        .frameCaptured(1_039 * ms)
        .jpegEncoded(1_060 * ms)
        .speechEnded(1_100 * ms)
        .inputSent(1_200 * ms)
        .accepted(1_300 * ms)
        .firstAudioStarted(2_500 * ms)
        .playbackUnderruns(2)

    @Test
    fun answerOnTapHasAllMetricsFromTouch() {
        val message = builder(RequestKind.DESCRIBE_ON_DEMAND).finish(Outcome.PLAYED, Network.WIFI)

        assertNotNull(message)
        assertEquals(requestId, message!!.requestId)
        assertEquals(Network.WIFI, message.network)
        assertEquals(
            ClientMetricValues(
                captureMs = 34,
                encodeMs = 21,
                triggerToSendMs = 200,
                triggerToAcceptedMs = 300,
                triggerToFirstAudioMs = 1_500,
                playbackUnderruns = 2,
            ),
            message.metrics,
        )
    }

    @Test
    fun askTriggerIsEndOfSpeech() {
        val message = builder(RequestKind.ASK).finish(Outcome.PLAYED, Network.CELLULAR)!!

        assertEquals(1_100 * ms, ClientMetricsBuilder(requestId, RequestKind.ASK).speechEnded(1_100 * ms).trigger)
        assertEquals(100L, message.metrics.triggerToSendMs)
        assertEquals(200L, message.metrics.triggerToAcceptedMs)
        assertEquals(1_400L, message.metrics.triggerToFirstAudioMs)
        assertEquals(34L, message.metrics.captureMs)
        assertEquals(21L, message.metrics.encodeMs)
    }

    @Test
    fun passiveTriggerIsSnapshotTaken() {
        val message = builder(RequestKind.DESCRIBE_PASSIVE).finish(Outcome.PLAYED, Network.OTHER)!!

        assertEquals(161L, message.metrics.triggerToSendMs)
        assertEquals(261L, message.metrics.triggerToAcceptedMs)
        assertEquals(1_461L, message.metrics.triggerToFirstAudioMs)
    }

    @Test
    fun afterErrorMessageHasWhatWasMeasured() {
        val builder = ClientMetricsBuilder(requestId, RequestKind.DESCRIBE_ON_DEMAND)
            .touchDown(1_000 * ms)
            .captureRequested(1_002 * ms)
            .frameCaptured(1_030 * ms)
            .jpegEncoded(1_050 * ms)
            .inputSent(1_070 * ms)
            .accepted(1_150 * ms)

        val message = builder.finish(Outcome.ERROR, Network.WIFI)

        assertEquals(
            ClientMetricValues(
                captureMs = 28,
                encodeMs = 20,
                triggerToSendMs = 70,
                triggerToAcceptedMs = 150,
                triggerToFirstAudioMs = null,
                playbackUnderruns = 0,
            ),
            message!!.metrics,
        )
        assertEquals(
            """{"type":"client.metrics","request_id":"123e4567-e89b-12d3-a456-426614174000",""" +
                """"metrics":{"capture_ms":28,"encode_ms":20,"trigger_to_send_ms":70,"trigger_to_accepted_ms":150,""" +
                """"playback_underruns":0},"network":"wifi"}""",
            ProtocolCodec.encodeMessage(message),
        )
    }

    @Test
    fun noMessageAfterCancel() {
        for (kind in RequestKind.entries) {
            assertNull(kind.name, builder(kind).finish(Outcome.CANCELLED, Network.WIFI))
        }
    }

    @Test
    fun noMessageAfterPassiveClear() {
        assertNull(builder(RequestKind.DESCRIBE_PASSIVE).finish(Outcome.PASSIVE_CLEAR, Network.WIFI))
    }

    @Test
    fun messageIsBuiltOnce() {
        val builder = builder(RequestKind.DESCRIBE_ON_DEMAND)

        assertNotNull(builder.finish(Outcome.PLAYED, Network.WIFI))
        assertNull(builder.finish(Outcome.ERROR, Network.WIFI))
    }

    @Test
    fun cancelledRequestNeverSendsLater() {
        val builder = builder(RequestKind.ASK)

        assertNull(builder.finish(Outcome.CANCELLED, Network.WIFI))
        assertNull(builder.finish(Outcome.PLAYED, Network.WIFI))
    }

    @Test
    fun millisecondsAreRounded() {
        val message = ClientMetricsBuilder(requestId, RequestKind.DESCRIBE_ON_DEMAND)
            .touchDown(0)
            .inputSent(70 * ms + 600_000)
            .accepted(158 * ms + 400_000)
            .finish(Outcome.ERROR, Network.WIFI)!!

        assertEquals(71L, message.metrics.triggerToSendMs)
        assertEquals(158L, message.metrics.triggerToAcceptedMs)
        assertNull(message.metrics.captureMs)
    }
}
