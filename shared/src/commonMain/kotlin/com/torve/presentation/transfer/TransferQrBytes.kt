package com.torve.presentation.transfer

/**
 * Platform QR-PNG renderer for the credential-transfer receive surface.
 * Android and desktop provide PNG bytes through the shared Kotlin API.
 * iOS renders QR codes natively in Swift/CoreImage because the JVM/Android
 * QR renderer dependency does not publish iOS artifacts.
 *
 * No private-key material crosses this layer. Input is the public QR
 * string and output is purely visual.
 */
expect object TransferQrBytes {
    fun renderPngBytes(payload: String): ByteArray
}
