package com.torve.desktop.transfer

import com.torve.desktop.security.JvmTransferCryptoEngine
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.transfer.ConsumedNonceStore
import com.torve.domain.transfer.SecretCategory
import com.torve.domain.transfer.SecretsTransferApplier
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferApplyResult
import com.torve.presentation.transfer.ReceiverState
import com.torve.presentation.transfer.SecretsTransferReceiverViewModel
import com.torve.presentation.transfer.SecretsTransferSenderViewModel
import com.torve.presentation.transfer.SenderStatus
import com.torve.presentation.transfer.TransferImportResult
import com.torve.presentation.transfer.TransferSecretCatalog
import com.torve.presentation.transfer.TransferSessionCodec
import com.torve.presentation.transfer.TransferSessionParseResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretsTransferPasteFlowTest {

    @Test
    fun sessionCodecRoundTripsAndRejectsMalformedInput() = runBlocking {
        val receiver = receiverVm(now = { 1_000L })
        receiver.start()
        val active = assertIs<ReceiverState.Active>(receiver.state.value)

        val decoded = assertIs<TransferSessionParseResult.Success>(
            TransferSessionCodec.decode(active.sessionString),
        )
        assertEquals(active.handshake, decoded.handshake)
        assertEquals(TransferSessionParseResult.Empty, TransferSessionCodec.decode(" "))
        assertEquals(TransferSessionParseResult.BadPrefix, TransferSessionCodec.decode("not-a-torve-code"))
        assertEquals(TransferSessionParseResult.BadBase64, TransferSessionCodec.decode("${SecretsTransferReceiverViewModel.QR_PREFIX}***"))

        receiver.cancel()
        Unit
    }

    @Test
    fun senderOnlyExportsEnabledCategoriesAndReceiverAppliesThem() = runBlocking {
        val senderStore = FakeTransferSecretStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "rd-token")
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "plex-token")
        }
        val receiverStore = FakeTransferSecretStore()
        val nonces = ConsumedNonceStore(FakeTransferPrefs(), nowMs = { 1_000L })
        val protocol = SecretsTransferProtocol(JvmTransferCryptoEngine(), nowMs = { 1_000L })
        val receiver = receiverVm(protocol, receiverStore, nonces, now = { 1_000L })
        receiver.start()

        val sender = senderVm(protocol, senderStore, now = { 1_000L })
        sender.updateReceiverSessionString(assertIs<ReceiverState.Active>(receiver.state.value).sessionString)
        TransferSecretCatalog.specs.forEach { sender.setCategoryEnabled(it.category, false) }
        sender.setCategoryEnabled(SecretCategory.DEBRID, true)
        sender.generateEnvelope()

        val ready = assertIs<SenderStatus.Ready>(sender.state.value.status)
        assertEquals(1, ready.secretCount)
        assertEquals(listOf(SecretCategory.DEBRID), ready.includedCategories)

        receiver.updateEnvelopeText(ready.envelopeJson)
        val imported = assertIs<TransferImportResult.Success>(receiver.acceptEnvelopeJson())

        assertEquals(1, imported.applyResult.applied)
        assertEquals("rd-token", receiverStore.get(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID))
        assertNull(receiverStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN))
        assertIs<ReceiverState.Imported>(receiver.state.value)
        Unit
    }

    @Test
    fun senderReportsExpiredHandshake() = runBlocking {
        val receiver = receiverVm(now = { 1_000L }, ttlMs = 10L)
        receiver.start()
        val sender = senderVm(now = { 2_000L })

        sender.updateReceiverSessionString(assertIs<ReceiverState.Active>(receiver.state.value).sessionString)
        sender.generateEnvelope()

        val error = assertIs<SenderStatus.Error>(sender.state.value.status)
        assertTrue(error.message.contains("expired", ignoreCase = true))
        receiver.cancel()
        Unit
    }

    @Test
    fun receiverShowsRollbackFailedStateWhenApplyRollbackFails() = runBlocking {
        val senderStore = FakeTransferSecretStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "new-rd")
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "new-plex")
        }
        val receiverStore = FakeTransferSecretStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "old-rd")
        }
        receiverStore.failPutOn = { key, _ ->
            key == IntegrationSecretKey.PLEX_ACCESS_TOKEN ||
                (key == IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID && receiverStore.putCount > 2)
        }
        val nonces = ConsumedNonceStore(FakeTransferPrefs(), nowMs = { 1_000L })
        val protocol = SecretsTransferProtocol(JvmTransferCryptoEngine(), nowMs = { 1_000L })
        val receiver = receiverVm(protocol, receiverStore, nonces, now = { 1_000L })
        receiver.start()

        val sender = senderVm(protocol, senderStore, now = { 1_000L })
        sender.updateReceiverSessionString(assertIs<ReceiverState.Active>(receiver.state.value).sessionString)
        TransferSecretCatalog.specs.forEach { sender.setCategoryEnabled(it.category, false) }
        sender.setCategoryEnabled(SecretCategory.DEBRID, true)
        sender.setCategoryEnabled(SecretCategory.PLEX_JELLYFIN, true)
        sender.generateEnvelope()
        val envelope = assertIs<SenderStatus.Ready>(sender.state.value.status).envelopeJson

        receiver.updateEnvelopeText(envelope)
        val result = assertIs<TransferImportResult.ApplyFailure>(receiver.acceptEnvelopeJson())
        val storeFailure = assertIs<TransferApplyResult.StoreFailure>(result.result)

        assertTrue(storeFailure.rollbackAttempted)
        assertEquals(false, storeFailure.rollbackSucceeded)
        assertEquals("new-rd", receiverStore.get(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID))
        assertNull(receiverStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN))
        val active = assertIs<ReceiverState.Active>(receiver.state.value)
        assertIs<TransferImportResult.ApplyFailure>(active.importResult)
        Unit
    }

    private fun senderVm(
        protocol: SecretsTransferProtocol = SecretsTransferProtocol(JvmTransferCryptoEngine(), nowMs = { 1_000L }),
        store: FakeTransferSecretStore = FakeTransferSecretStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "rd")
        },
        prefs: FakeTransferPrefs = FakeTransferPrefs(),
        now: () -> Long = { 1_000L },
    ): SecretsTransferSenderViewModel =
        SecretsTransferSenderViewModel(
            protocol = protocol,
            secretStore = store,
            deviceIdProvider = FakeTransferDeviceIdProvider(),
            prefsRepo = prefs,
            nowMs = now,
        )

    private fun receiverVm(
        protocol: SecretsTransferProtocol = SecretsTransferProtocol(JvmTransferCryptoEngine(), nowMs = { 1_000L }),
        store: FakeTransferSecretStore = FakeTransferSecretStore(),
        nonces: ConsumedNonceStore = ConsumedNonceStore(FakeTransferPrefs(), nowMs = { 1_000L }),
        prefs: FakeTransferPrefs = FakeTransferPrefs(),
        now: () -> Long = { 1_000L },
        ttlMs: Long = SecretsTransferReceiverViewModel.DEFAULT_TTL_MS,
    ): SecretsTransferReceiverViewModel =
        SecretsTransferReceiverViewModel(
            protocol = protocol,
            applier = SecretsTransferApplier(store, nonces, prefs),
            nonceStore = nonces,
            ttlMs = ttlMs,
            nowMs = now,
        )
}
