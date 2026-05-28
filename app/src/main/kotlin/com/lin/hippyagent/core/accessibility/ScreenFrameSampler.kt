package com.lin.hippyagent.core.accessibility

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.lin.hippyagent.core.pool.ByteArrayOutputStreamPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.coroutineContext

class ScreenFrameSampler(
    private val frameBuffer: VisionFrameBuffer,
    private val fps: Int = 1,
    private val jpegQuality: Int = 80
) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var samplingJob: Job? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val streamPool = ByteArrayOutputStreamPool(initialBufferSize = 128 * 1024, maxSize = 2)

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    fun start(mediaProjection: MediaProjection) {
        stop()
        Timber.i("ScreenFrameSampler starting, fps=%d, quality=%d", fps, jpegQuality)

        val metrics = DisplayMetrics()
        mediaProjection.createVirtualDisplay(
            "metrics_probe", 1, 1, 1,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, null
        ).let { vd ->
            vd.release()
        }

        samplingJob = scope.launch {
            try {
                startSamplingLoop(mediaProjection)
            } catch (e: CancellationException) {
                Timber.d("Sampling loop cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Sampling loop failed")
            } finally {
                releaseResources()
            }
        }
    }

    fun stop() {
        samplingJob?.cancel()
        samplingJob = null
        releaseResources()
        Timber.i("ScreenFrameSampler stopped")
    }

    private suspend fun startSamplingLoop(mediaProjection: MediaProjection) {
        val handler = Handler(Looper.getMainLooper())

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (mediaProjection.javaClass
            .getDeclaredMethod("getDisplay")
            .apply { isAccessible = true }
            .invoke(mediaProjection) as? android.view.Display)
        display?.getMetrics(metrics)

        screenWidth = metrics.widthPixels.let { if (it > 0) it else 1080 }
        screenHeight = metrics.heightPixels.let { if (it > 0) it else 1920 }
        screenDensity = metrics.densityDpi.let { if (it > 0) it else 320 }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenFrameSampler",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )

        val intervalMs = 1000L / fps

        while (coroutineContext.isActive) {
            val frameStart = System.currentTimeMillis()

            captureAndOfferFrame()

            val elapsed = System.currentTimeMillis() - frameStart
            val waitMs = intervalMs - elapsed
            if (waitMs > 0) delay(waitMs)
        }
    }

    private fun captureAndOfferFrame() {
        val reader = imageReader ?: return
        val image: Image? = reader.acquireLatestImage()
        if (image == null) {
            Timber.v("No image available from ImageReader")
            return
        }

        try {
            val buffer = image.planes[0].buffer
            val pixelStride = image.planes[0].pixelStride
            val rowStride = image.planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight,
                Bitmap.Config.RGB_565
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val stream = streamPool.acquire()
            val jpegBytes: ByteArray
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
                jpegBytes = stream.toByteArray()
            } finally {
                streamPool.release(stream)
                bitmap.recycle()
            }

            val frame = FrameData(
                timestamp = System.currentTimeMillis(),
                jpegBytes = jpegBytes,
                width = screenWidth,
                height = screenHeight
            )
            frameBuffer.offer(frame)
        } catch (e: Exception) {
            Timber.w(e, "Failed to capture frame")
        } finally {
            image.close()
        }
    }

    private fun releaseResources() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
    }
}
