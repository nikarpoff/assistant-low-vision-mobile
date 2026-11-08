package io.github.nikarpoff.alv.controller

import io.github.nikarpoff.alv.controller.FrameScaler.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameScalerTest {
    @Test
    fun landscapeLongSideIsReducedToMaxSide() {
        assertEquals(Size(640, 480), FrameScaler.scale(1920, 1440, rotationDegrees = 0, imageMaxSide = 640))
    }

    @Test
    fun rotationBy90SwapsSidesBeforeScaling() {
        // A sensor frame held upright: the snapshot is portrait
        assertEquals(Size(480, 640), FrameScaler.scale(1920, 1440, rotationDegrees = 90, imageMaxSide = 640))
        assertEquals(Size(480, 640), FrameScaler.scale(1920, 1440, rotationDegrees = 270, imageMaxSide = 640))
    }

    @Test
    fun rotationBy180KeepsSides() {
        assertEquals(Size(640, 480), FrameScaler.scale(1920, 1440, rotationDegrees = 180, imageMaxSide = 640))
    }

    @Test
    fun proportionsAreKeptWithRounding() {
        // 16:9: 1280 x 720 → 640 x 360; 1000 x 333 → 640 x 213.12 → 213
        assertEquals(Size(360, 640), FrameScaler.scale(1280, 720, rotationDegrees = 90, imageMaxSide = 640))
        assertEquals(Size(640, 213), FrameScaler.scale(1000, 333, rotationDegrees = 0, imageMaxSide = 640))
    }

    @Test
    fun maxSideFollowsServerConfig() {
        assertEquals(Size(1024, 768), FrameScaler.scale(4000, 3000, rotationDegrees = 0, imageMaxSide = 1024))
    }

    @Test
    fun smallFrameIsNotEnlarged() {
        assertEquals(Size(480, 320), FrameScaler.scale(320, 480, rotationDegrees = 90, imageMaxSide = 640))
        assertEquals(Size(640, 480), FrameScaler.scale(640, 480, rotationDegrees = 0, imageMaxSide = 640))
    }

    @Test
    fun thinFrameKeepsAtLeastOnePixel() {
        assertEquals(Size(640, 1), FrameScaler.scale(10_000, 2, rotationDegrees = 0, imageMaxSide = 640))
    }

    @Test
    fun invalidInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { FrameScaler.scale(0, 480, 0, 640) }
        assertThrows(IllegalArgumentException::class.java) { FrameScaler.scale(640, 480, 45, 640) }
        assertThrows(IllegalArgumentException::class.java) { FrameScaler.scale(640, 480, 0, 0) }
    }
}
