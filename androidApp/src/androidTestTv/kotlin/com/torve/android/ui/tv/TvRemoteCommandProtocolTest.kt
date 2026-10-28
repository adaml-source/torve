package com.torve.android.ui.tv

import android.util.Base64
import com.torve.android.sync.lan.SecureTransferCrypto
import com.torve.android.sync.model.SyncRemoteCommandPayload
import com.torve.android.sync.model.TorveRemoteCommand
import org.junit.Assert.*
import org.junit.Test

class TvRemoteCommandProtocolTest {
    @Test fun authenticationBindsEveryCommandFieldAndBothDevices() {
        val secret = Base64.encodeToString(ByteArray(32) { (it + 1).toByte() }, Base64.NO_WRAP)
        val packet = SyncRemoteCommandPayload(TorveRemoteCommand.SEEK_FORWARD, "phone", 1_000_000, 10_000)
        val text = packet.transcript("event-1", "tv-142")
        val proof = SecureTransferCrypto.computeConfirmation(secret, "torve-remote-v1", text)
        assertTrue(SecureTransferCrypto.verifyConfirmation(secret, "torve-remote-v1", text, proof))
        val altered = listOf(packet.copy(command = TorveRemoteCommand.STOP).transcript("event-1", "tv-142"),
            packet.copy(seekMs = 30_000).transcript("event-1", "tv-142"),
            packet.copy(issuedAtMs = 999_999).transcript("event-1", "tv-142"),
            packet.copy(senderInstallationId = "another-phone").transcript("event-1", "tv-142"),
            packet.transcript("event-2", "tv-142"), packet.transcript("event-1", "tv-110"))
        altered.forEach { assertFalse(SecureTransferCrypto.verifyConfirmation(secret, "torve-remote-v1", it, proof)) }
    }
}
