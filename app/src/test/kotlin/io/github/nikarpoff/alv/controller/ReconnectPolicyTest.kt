package io.github.nikarpoff.alv.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {
    /** Random source that always returns [value] from `nextDouble`. */
    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = value
    }

    private fun delays(policy: ReconnectPolicy, count: Int) = List(count) { policy.nextDelayMs() }

    @Test
    fun pausesGrowFromHalfSecondTo8Seconds() {
        // 0.5 in nextDouble means no spread
        val policy = ReconnectPolicy(FixedRandom(0.5))

        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), delays(policy, 7))
    }

    @Test
    fun pausesStayAt8SecondsForLongOutage() {
        val policy = ReconnectPolicy(FixedRandom(0.5))

        assertTrue(delays(policy, 100).drop(4).all { it == 8_000L })
    }

    @Test
    fun spreadIsMinus20Percent() {
        val policy = ReconnectPolicy(FixedRandom(0.0))

        assertEquals(listOf(400L, 800L, 1_600L, 3_200L, 6_400L), delays(policy, 5))
    }

    @Test
    fun spreadIsPlus20PercentButNotOver8Seconds() {
        val policy = ReconnectPolicy(FixedRandom(1.0))

        assertEquals(listOf(600L, 1_200L, 2_400L, 4_800L, 8_000L), delays(policy, 5))
    }

    @Test
    fun randomPausesStayWithinSpread() {
        val policy = ReconnectPolicy(Random(42))
        val nominal = listOf(500L, 1_000L, 2_000L, 4_000L) + List(20) { 8_000L }

        val actual = delays(policy, nominal.size)

        nominal.zip(actual).forEach { (expected, delay) ->
            assertTrue("$delay is not within 20 % of $expected", delay in (expected * 8 / 10)..(expected * 12 / 10))
            assertTrue("$delay is longer than 8 s", delay <= 8_000L)
        }
        assertTrue("pauses have no spread", actual.drop(4).distinct().size > 1)
    }

    @Test
    fun successStartsOver() {
        val policy = ReconnectPolicy(FixedRandom(0.5))
        delays(policy, 6)

        policy.onConnected()

        assertEquals(listOf(500L, 1_000L), delays(policy, 2))
    }
}
