package io.github.nikarpoff.alv.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * JPEG snapshot of the scene, in memory only (MOB-5, MOB-16).
 *
 * @property jpeg JPEG bytes
 * @property width width after the rotation and the scaling, px
 * @property height height after the rotation and the scaling, px
 * @property requestedNanos command to take the frame
 * @property capturedNanos frame is in memory
 * @property encodedNanos JPEG is ready
 */
class Snapshot(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val requestedNanos: Long,
    val capturedNanos: Long,
    val encodedNanos: Long,
)

/** Snapshot being taken. */
interface PendingSnapshot {
    /** Waits for the snapshot up to [timeoutMs]; `null` when the camera gave none. */
    fun await(timeoutMs: Long): Snapshot?

    /** The snapshot is not needed: a swipe or a dropped touch. */
    fun discard()
}

/** Camera of A1. */
interface Camera {
    /**
     * Starts taking the next frame of the running camera: rotated upright, long side reduced to
     * [maxSide], encoded to JPEG with [jpegQuality] (`image_max_side` and `jpeg_quality` of `welcome`).
     */
    fun capture(maxSide: Int, jpegQuality: Int): PendingSnapshot
}

/**
 * Back camera on CameraX (MOB-5): frame analysis runs all the time the app is on the screen,
 * so a snapshot is the next analysed frame, without a shutter. Frames nobody asked for are
 * closed at once; a frame and its JPEG live in memory only (MOB-16).
 *
 * @param clock monotonic clock, ns, the same as of the controller
 */
class CameraController(
    private val context: Context,
    private val clock: () -> Long = System::nanoTime,
) : Camera {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "alv-camera") }
    private val pending = ConcurrentLinkedQueue<Pending>()

    @Volatile
    private var bound = false

    private inner class Pending(val maxSide: Int, val quality: Int) : PendingSnapshot {
        val requestedNanos: Long = clock()
        private val done = CountDownLatch(1)

        @Volatile
        var discarded = false
            private set

        @Volatile
        private var result: Snapshot? = null

        fun complete(snapshot: Snapshot?) {
            result = snapshot
            done.countDown()
        }

        override fun await(timeoutMs: Long): Snapshot? {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            return result.also { if (it == null) discard() }
        }

        override fun discard() {
            discarded = true
            pending.remove(this)
            done.countDown()
        }
    }

    /** Binds frame analysis to [owner]: it runs while the owner is started. Needs the camera permission. */
    fun bind(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                        .build(),
                )
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                bound = true
            } catch (e: IllegalArgumentException) {
                bound = false
            } catch (e: IllegalStateException) {
                bound = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun capture(maxSide: Int, jpegQuality: Int): PendingSnapshot {
        val request = Pending(maxSide, jpegQuality)
        if (bound) pending.add(request) else request.complete(null)
        return request
    }

    /** Stops the analysis thread. */
    fun shutdown() {
        bound = false
        executor.shutdownNow()
        while (true) pending.poll()?.complete(null) ?: break
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            if (pending.isEmpty()) return
            val capturedNanos = clock()
            val frame = try {
                it.toBitmap()
            } catch (e: IllegalArgumentException) {
                return
            }
            val rotation = it.imageInfo.rotationDegrees
            while (true) {
                val request = pending.poll() ?: break
                if (request.discarded) continue
                request.complete(encode(frame, rotation, request, capturedNanos))
            }
            frame.recycle()
        }
    }

    private fun encode(frame: Bitmap, rotation: Int, request: Pending, capturedNanos: Long): Snapshot {
        val size = FrameScaler.scale(frame.width, frame.height, rotation, request.maxSide)
        val factor = max(size.width, size.height).toFloat() / max(frame.width, frame.height)
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(factor, factor)
        }
        val upright = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        val bytes = ByteArrayOutputStream(JPEG_BUFFER_BYTES)
        upright.compress(Bitmap.CompressFormat.JPEG, request.quality, bytes)
        val snapshot = Snapshot(bytes.toByteArray(), upright.width, upright.height, request.requestedNanos, capturedNanos, clock())
        if (upright !== frame) upright.recycle()
        return snapshot
    }

    private companion object {
        /** Analysis resolution: enough for `image_max_side` up to 1280 px, cheap to convert. */
        val ANALYSIS_SIZE = Size(1280, 960)

        const val JPEG_BUFFER_BYTES = 64 * 1024
    }
}
