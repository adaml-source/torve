package com.torve.desktop.transfer

import com.torve.domain.transfer.Base64Url
import com.torve.domain.transfer.ConsumedNonceStore
import com.torve.domain.transfer.SecretsTransferApplier
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferReceiverHandshake
import com.torve.desktop.security.JvmTransferCryptoEngine
import com.torve.presentation.transfer.ReceiverState
import com.torve.presentation.transfer.SecretsTransferReceiverViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretsTransferReceiverViewModelTest {

    @Test
    fun startGeneratesHandshakeSessionStringWithReceiverPublicKey() = runBlocking {
        val now = 1_000_000L
        val viewModel = newViewModel(now = { now })

        viewModel.start()

        val state = assertIs<ReceiverState.Active>(viewModel.state.value)
        assertEquals(now, state.createdAtEpochMs)
        assertEquals(now + SecretsTransferReceiverViewModel.DEFAULT_TTL_MS, state.expiresAtEpochMs)
        assertEquals(600L, state.remainingSeconds)
        assertTrue(state.sessionString.startsWith(SecretsTransferReceiverViewModel.QR_PREFIX))

        val decoded = decodeSession(state.sessionString)
        assertEquals(state.handshake, decoded)
        assertEquals(state.expiresAtEpochMs, decoded.expiresAtEpochMs)
        assertEquals(SecretsTransferReceiverViewModel.SESSION_ID_BYTES, Base64Url.decodeOrNull(decoded.sessionId)?.size)
        assertEquals(32, Base64Url.decodeOrNull(decoded.receiverEphemeralPublicKey)?.size)

        viewModel.cancel()
    }

    @Test
    fun startIsIdempotentWhileActive() = runBlocking {
        val viewModel = newViewModel()

        viewModel.start()
        val first = assertIs<ReceiverState.Active>(viewModel.state.value).sessionString
        viewModel.start()
        val second = assertIs<ReceiverState.Active>(viewModel.state.value).sessionString

        assertEquals(first, second)
        viewModel.cancel()
    }

    @Test
    fun cancelReturnsToIdleAndRestartGeneratesNewHandshake() = runBlocking {
        var now = 1_000L
        val viewModel = newViewModel(now = { now })

        viewModel.start()
        val first = assertIs<ReceiverState.Active>(viewModel.state.value).sessionString
        viewModel.cancel()
        assertIs<ReceiverState.Idle>(viewModel.state.value)

        now = 2_000L
        viewModel.restart()
        val second = assertIs<ReceiverState.Active>(viewModel.state.value).sessionString

        assertNotEquals(first, second)
        assertEquals(now, assertIs<ReceiverState.Active>(viewModel.state.value).createdAtEpochMs)
        viewModel.cancel()
    }

    @Test
    fun qrRendererReturnsPngBytesForSessionString() = runBlocking {
        val viewModel = newViewModel()
        viewModel.start()
        val state = assertIs<ReceiverState.Active>(viewModel.state.value)

        val pngBytes = TransferQrBitmap.renderPngBytes(state.sessionString)
        val width = pngDimension(pngBytes, offset = 16)
        val height = pngDimension(pngBytes, offset = 20)

        assertTrue(pngBytes.size > PNG_SIGNATURE.size)
        assertContentEquals(PNG_SIGNATURE, pngBytes.take(PNG_SIGNATURE.size).toByteArray())
        assertEquals(width, height)
        assertTrue(width >= 128, "QR bitmap should be large enough for desktop scanning")
        viewModel.cancel()
    }

    private fun newViewModel(now: () -> Long = { 1_000_000L }): SecretsTransferReceiverViewModel =
        SecretsTransferReceiverViewModel(
            protocol = SecretsTransferProtocol(JvmTransferCryptoEngine(), nowMs = now),
            applier = SecretsTransferApplier(
                secretStore = FakeTransferSecretStore(),
                nonceStore = ConsumedNonceStore(FakeTransferPrefs(), nowMs = now),
                prefsRepo = FakeTransferPrefs(),
            ),
            nonceStore = ConsumedNonceStore(FakeTransferPrefs(), nowMs = now),
            nowMs = now,
        )

    private fun decodeSession(sessionString: String): TransferReceiverHandshake {
        val encoded = sessionString.removePrefix(SecretsTransferReceiverViewModel.QR_PREFIX)
        val json = Base64Url.decodeOrNull(encoded)!!.decodeToString()
        return Json.decodeFromString(TransferReceiverHandshake.serializer(), json)
    }

    private fun pngDimension(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            'P'.code.toByte(),
            'N'.code.toByte(),
            'G'.code.toByte(),
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
