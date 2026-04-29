package com.torve.presentation.transfer

import qrcode.QRCode

/**
 * Cross-platform QR-PNG renderer for the credential-transfer receive
 * surface. Produces raw PNG bytes from a [TransferSessionCodec]-encoded
 * payload. Each platform decodes the bytes into its own bitmap type
 * (Skia [androidx.compose.ui.graphics.ImageBitmap] on desktop, Android
 * `Bitmap` via `BitmapFactory` on mobile/TV).
 *
 * No private-key material crosses this layer — input is the public
 * QR string and output is purely visual.
 */
object TransferQrBytes {

    /**
     * Render [payload] as a PNG byte array. Returns an empty array when
     * input is empty so callers can do a single zero-length check.
     *
     * Module size of 8 px gives scanners a comfortable target on
     * desktop and mobile screens without inflating the bitmap
     * unreasonably.
     */
    fun renderPngBytes(payload: String): ByteArray {
        if (payload.isEmpty()) return ByteArray(0)
        return QRCode.ofSquares()
            .withSize(QR_MODULE_PX)
            .build(payload)
            .renderToBytes()
    }

    private const val QR_MODULE_PX = 8
}
