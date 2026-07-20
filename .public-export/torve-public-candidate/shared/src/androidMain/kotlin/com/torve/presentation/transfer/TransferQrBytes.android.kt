package com.torve.presentation.transfer

import qrcode.QRCode

actual object TransferQrBytes {
    actual fun renderPngBytes(payload: String): ByteArray {
        if (payload.isEmpty()) return ByteArray(0)
        return QRCode.ofSquares()
            .withSize(QR_MODULE_PX)
            .build(payload)
            .renderToBytes()
    }

    private const val QR_MODULE_PX = 8
}
