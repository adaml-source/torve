package com.torve.desktop.transfer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.torve.presentation.transfer.TransferQrBytes
import org.jetbrains.skia.Image

/**
 * Desktop bridge that turns the cross-platform [TransferQrBytes] PNG
 * output into a Skia [ImageBitmap] for Compose Desktop.
 *
 * The pure-Kotlin byte generation lives in shared so mobile/TV can
 * reuse it; this object only handles Skia decoding.
 */
object TransferQrBitmap {

    /**
     * @return an ImageBitmap of a fully-rendered QR PNG, or null when
     * input is empty.
     */
    fun render(payload: String): ImageBitmap? {
        if (payload.isEmpty()) return null
        return Image.makeFromEncoded(renderPngBytes(payload)).toComposeImageBitmap()
    }

    /** Re-export of [TransferQrBytes.renderPngBytes] for existing callers/tests. */
    fun renderPngBytes(payload: String): ByteArray = TransferQrBytes.renderPngBytes(payload)
}
