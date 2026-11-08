package io.github.nikarpoff.alv.controller

import io.github.nikarpoff.alv.controller.GestureClassifier.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureClassifierTest {
    // 2 px in one dp: distances in the tests are given in dp and doubled
    private val classifier = GestureClassifier(pxPerDp = DENSITY)

    private fun dp(value: Float) = value * DENSITY

    @Test
    fun tapIsReportedOnReleaseWithoutWaiting() {
        assertNull(classifier.onDown(dp(100f), dp(100f), 1_000))
        assertNull(classifier.onMove(dp(105f), dp(105f), 1_100))

        assertEquals(Gesture.TAP, classifier.onUp(dp(105f), dp(105f), 1_200))
    }

    @Test
    fun releaseAtHoldThresholdIsNotTap() {
        classifier.onDown(0f, 0f, 1_000)

        assertNull(classifier.onUp(0f, 0f, 1_000 + GestureClassifier.HOLD_MS))
    }

    @Test
    fun tapMovedBeyondSlopIsIgnored() {
        classifier.onDown(dp(100f), dp(100f), 1_000)
        assertNull(classifier.onMove(dp(124f), dp(100f), 1_050))

        assertNull(classifier.onUp(dp(124f), dp(100f), 1_100))
    }

    @Test
    fun tapJustInsideSlop() {
        classifier.onDown(dp(100f), dp(100f), 1_000)

        assertEquals(Gesture.TAP, classifier.onUp(dp(123.9f), dp(100f), 1_100))
    }

    @Test
    fun secondTouchWithin300MsIsDoubleTap() {
        classifier.onDown(0f, 0f, 1_000)
        assertEquals(Gesture.TAP, classifier.onUp(0f, 0f, 1_100))

        assertEquals(Gesture.DOUBLE_TAP, classifier.onDown(0f, 0f, 1_100 + GestureClassifier.DOUBLE_TAP_TIMEOUT_MS))
        // The rest of the second touch is consumed: no extra tap
        assertNull(classifier.onUp(0f, 0f, 1_450))
    }

    @Test
    fun secondTouchAfter300MsIsNewTap() {
        classifier.onDown(0f, 0f, 1_000)
        classifier.onUp(0f, 0f, 1_100)

        assertNull(classifier.onDown(0f, 0f, 1_100 + GestureClassifier.DOUBLE_TAP_TIMEOUT_MS + 1))
        assertEquals(Gesture.TAP, classifier.onUp(0f, 0f, 1_500))
    }

    @Test
    fun thirdTouchAfterDoubleTapIsNotDoubleTapAgain() {
        classifier.onDown(0f, 0f, 1_000)
        classifier.onUp(0f, 0f, 1_100)
        assertEquals(Gesture.DOUBLE_TAP, classifier.onDown(0f, 0f, 1_200))
        classifier.onUp(0f, 0f, 1_250)

        assertNull(classifier.onDown(0f, 0f, 1_300))
    }

    @Test
    fun holdStartsAt400MsAndEndsOnRelease() {
        classifier.onDown(0f, 0f, 1_000)
        assertEquals(1_000 + GestureClassifier.HOLD_MS, classifier.holdDeadlineMs)
        assertNull(classifier.onTimer(1_399))

        assertEquals(Gesture.HOLD_START, classifier.onTimer(1_400))
        assertNull(classifier.holdDeadlineMs)
        assertNull(classifier.onMove(dp(50f), dp(50f), 2_000))
        assertEquals(Gesture.HOLD_END, classifier.onUp(dp(50f), dp(50f), 3_000))
    }

    @Test
    fun holdIsRecognizedOnMoveAfter400Ms() {
        classifier.onDown(0f, 0f, 1_000)

        assertEquals(Gesture.HOLD_START, classifier.onMove(dp(2f), 0f, 1_450))
    }

    @Test
    fun holdIsNotStartedAfterMove() {
        classifier.onDown(0f, 0f, 1_000)
        classifier.onMove(dp(30f), 0f, 1_100)

        assertNull(classifier.onTimer(1_400))
        assertNull(classifier.onUp(dp(30f), 0f, 1_500))
    }

    @Test
    fun cancelEndsHold() {
        classifier.onDown(0f, 0f, 1_000)
        classifier.onTimer(1_400)

        assertEquals(Gesture.HOLD_END, classifier.onCancel())
    }

    @Test
    fun swipeUpBeyond120Dp() {
        classifier.onDown(dp(100f), dp(400f), 1_000)
        assertNull(classifier.onMove(dp(100f), dp(330f), 1_050))
        assertEquals(Gesture.SWIPE_UP, classifier.onMove(dp(100f), dp(279f), 1_100))

        assertNull(classifier.onUp(dp(100f), dp(200f), 1_200))
    }

    @Test
    fun swipeDownBeyond120Dp() {
        classifier.onDown(dp(100f), dp(100f), 1_000)

        assertEquals(Gesture.SWIPE_DOWN, classifier.onMove(dp(100f), dp(221f), 1_100))
    }

    @Test
    fun swipeOnReleaseWithoutMoveEvents() {
        classifier.onDown(dp(100f), dp(400f), 1_000)

        assertEquals(Gesture.SWIPE_UP, classifier.onUp(dp(100f), dp(250f), 1_150))
    }

    @Test
    fun verticalMoveOf120DpIsNotSwipe() {
        classifier.onDown(dp(100f), dp(400f), 1_000)

        assertNull(classifier.onUp(dp(100f), dp(280f), 1_150))
    }

    @Test
    fun swipeIsNotTapForDoubleTap() {
        classifier.onDown(dp(100f), dp(400f), 1_000)
        classifier.onUp(dp(100f), dp(200f), 1_100)

        assertNull(classifier.onDown(0f, 0f, 1_200))
    }

    private companion object {
        const val DENSITY = 2f
    }
}
