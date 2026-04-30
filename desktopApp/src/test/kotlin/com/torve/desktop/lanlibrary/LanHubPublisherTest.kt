package com.torve.desktop.lanlibrary

import org.junit.Test
import kotlin.test.assertNotNull
import java.net.NetworkInterface

/**
 * Pins `LanHubPublisher.pickLanHostFromInterfaces` — the only piece
 * the publisher executes outside its long-running observers. Lifecycle
 * coverage (toggle-on starts heartbeats, sign-out tears them down) is
 * pinned indirectly by the shared-side `LanHubRegistryApiTest` plus
 * the `Prompt9LanBindAndCleanupTest` which exercises the controller's
 * bind state machine.
 */
class LanHubPublisherTest {

    @Test
    fun `pickLanHostFromInterfaces returns a non-loopback IPv4 when available`() {
        val hasCandidate = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { iface ->
                iface.isUp && !iface.isLoopback &&
                    iface.inetAddresses.toList().any { addr ->
                        !addr.isLoopbackAddress && !addr.isLinkLocalAddress &&
                            addr.hostAddress?.contains(':') == false
                    }
            }
        }.getOrDefault(false)
        if (!hasCandidate) return  // Bare CI without network — skip.

        val host = LanHubPublisher.pickLanHostFromInterfaces()
        assertNotNull(host, "expected a non-loopback IPv4 host to be selected")
        val parts = host.split('.')
        assert(parts.size == 4) { "expected dotted-quad host, got $host" }
        assert(host != "127.0.0.1") { "host picker must not return loopback" }
    }
}
