package com.torve.presentation.transfer

actual object TransferQrBytes {
    // iOS renders credential-transfer QR codes in Swift/CoreImage. The
    // qrcode-kotlin renderer is kept on Android/desktop only because it
    // does not publish iOS artifacts.
    actual fun renderPngBytes(payload: String): ByteArray = ByteArray(0)
}
