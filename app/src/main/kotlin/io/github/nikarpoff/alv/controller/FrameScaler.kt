package io.github.nikarpoff.alv.controller

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Size of the snapshot sent to the gateway (MOB-5, PROTO-13).
 *
 * The camera frame is first turned into the normal orientation, then its long side
 * is reduced to `image_max_side` from `welcome`, keeping the proportions. A smaller
 * frame is never enlarged.
 */
object FrameScaler {
    /** Width and height of a frame, px. */
    data class Size(val width: Int, val height: Int)

    /**
     * Size of the snapshot for a camera frame.
     *
     * @param width width of the frame from the sensor, px
     * @param height height of the frame from the sensor, px
     * @param rotationDegrees clockwise rotation to the normal orientation: 0, 90, 180 or 270
     * @param imageMaxSide `image_max_side` from `welcome`
     */
    fun scale(width: Int, height: Int, rotationDegrees: Int, imageMaxSide: Int): Size {
        require(width > 0 && height > 0) { "frame size must be positive: ${width}x$height" }
        require(imageMaxSide > 0) { "image_max_side must be positive: $imageMaxSide" }
        require(rotationDegrees in ROTATIONS) { "rotation must be one of $ROTATIONS: $rotationDegrees" }

        val upright = if (rotationDegrees % 180 == 0) Size(width, height) else Size(height, width)
        val longSide = max(upright.width, upright.height)
        if (longSide <= imageMaxSide) return upright

        val factor = imageMaxSide.toDouble() / longSide
        return Size(
            width = (upright.width * factor).roundToInt().coerceIn(1, imageMaxSide),
            height = (upright.height * factor).roundToInt().coerceIn(1, imageMaxSide),
        )
    }

    private val ROTATIONS = setOf(0, 90, 180, 270)
}
