package com.torve.android.ui.transfer

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.torve.presentation.transfer.TransferQrBytes

/**
 * Decodes a [TransferQrBytes] PNG byte array into a Compose
 * [ImageBitmap] for the credential-transfer receive surfaces.
 *
 * The shared QR generator owns the actual rendering — this helper is
 * just the platform-specific PNG-to-bitmap step. Returns null on empty
 * input so a missing/unsealed payload silently produces no image
 * rather than crashing the receive screen.
 */
object AndroidTransferQrRenderer {

    fun render(payload: String): ImageBitmap? {
        if (payload.isEmpty()) return null
        val pngBytes = TransferQrBytes.renderPngBytes(payload)
        if (pngBytes.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return null
        return bitmap.asImageBitmap()
    }
}
