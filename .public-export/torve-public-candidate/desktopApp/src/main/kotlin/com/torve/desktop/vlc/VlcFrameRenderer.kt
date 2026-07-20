package com.torve.desktop.vlc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders vlcj video frames into Compose [ImageBitmap] instances.
 *
 * Uses double buffering: VLC writes to one [BufferedImage] while Compose
 * reads the most recently completed frame as an [ImageBitmap].
 * The two never reference the same image simultaneously.
 */
class VlcFrameRenderer(
    private val onVideoDimensionsChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    @Volatile var videoWidth: Int = 0; private set
    @Volatile var videoHeight: Int = 0; private set

    private var writeImage: BufferedImage? = null
    private val readImage = AtomicReference<BufferedImage?>(null)

    @Volatile var frameCount: Long = 0; private set

    val bufferFormatCallback: BufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            videoWidth = sourceWidth
            videoHeight = sourceHeight
            writeImage = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_ARGB)
            readImage.set(BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_ARGB))
            onVideoDimensionsChanged(sourceWidth, sourceHeight)
            println("TORVE VLC FRAME | format: ${sourceWidth}x${sourceHeight}")
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {
            // Managed internally via BufferedImage
        }
    }

    val renderCallback: RenderCallback = RenderCallback { _: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat ->
        val w = bufferFormat.width
        val h = bufferFormat.height
        val img = writeImage ?: return@RenderCallback
        if (img.width != w || img.height != h) return@RenderCallback

        // Copy native pixel data (RV32 = BGRA) into the BufferedImage
        val pixelBuffer = nativeBuffers[0].asIntBuffer()
        val raster = (img.raster.dataBuffer as DataBufferInt).data
        pixelBuffer.get(raster, 0, raster.size.coerceAtMost(pixelBuffer.remaining()))

        // Swap: write image becomes read image, previous read becomes write
        val previousRead = readImage.getAndSet(img)
        writeImage = previousRead
        frameCount++
    }

    /**
     * Returns the latest completed video frame as a Compose [ImageBitmap],
     * or null if no frame is available yet.
     *
     * Safe to call from the Compose main thread.
     */
    fun latestFrame(): ImageBitmap? {
        val img = readImage.get() ?: return null
        return runCatching { img.toComposeImageBitmap() }.getOrNull()
    }

    fun release() {
        writeImage = null
        readImage.set(null)
        videoWidth = 0
        videoHeight = 0
    }
}
