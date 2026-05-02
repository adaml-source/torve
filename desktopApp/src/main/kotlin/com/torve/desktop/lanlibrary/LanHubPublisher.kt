package com.torve.desktop.lanlibrary

import com.torve.data.auth.AuthClient
import com.torve.data.lanlibrary.LanHubRegistryApi
import com.torve.domain.lanlibrary.LanHubPublishRequest
import com.torve.domain.lanlibrary.MANIFEST_VERSION
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.NetworkInterface

/**
 * Publishes a [LanHub] entry to the backend registry whenever the
 * desktop's [LanServingController] is bound to a LAN interface AND
 * the user is signed in. Heartbeats every [HEARTBEAT_MS] so a stale
 * entry expires server-side; deletes the entry on stop / sign-out.
 *
 * Privacy:
 *   - Publishes only `(publisherId, deviceLabel, lanHost, lanPort,
 *     protocolVersion, publishedAtEpochMs)`. No filesystem paths, no
 *     manifest contents. The auth secret is fetched separately by
 *     consumers via [LanHubRegistryApi.fetchSecret].
 *   - `deviceLabel` defaults to the hostname; users can override later
 *     in Settings without breaking publisher_id continuity.
 */
class LanHubPublisher(
    private val controller: LanServingController,
    private val registry: LanHubRegistryApi,
    private val settings: SettingsViewModel,
    private val authClient: AuthClient,
    private val publisherId: String,
    private val deviceLabel: String = defaultDeviceLabel(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    @Volatile
    private var started: Boolean = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            settings.state
                .map { it.lanServingEnabled && it.lanServingBindToLan }
                .distinctUntilChanged()
                .collect { lanReady ->
                    if (lanReady) heartbeatLoop() else delete()
                }
        }
        scope.launch {
            authClient.authUserFlow
                .map { it != null }
                .distinctUntilChanged()
                .collect { signedIn -> if (!signedIn) delete() }
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            val port = controller.serverPort
            val secret = controller.serverSecret
            val lanHost = pickLanHost()
            // Skip the heartbeat when any precondition is missing -
            // the registry needs all four to function. Logging the
            // skip would leak intent; we just retry next interval.
            if (port > 0 && !secret.isNullOrBlank() && lanHost != null) {
                registry.publish(
                    LanHubPublishRequest(
                        publisherId = publisherId,
                        deviceLabel = deviceLabel,
                        lanHost = lanHost,
                        lanPort = port,
                        protocolVersion = MANIFEST_VERSION,
                        authSecret = secret,
                    ),
                )
            }
            delay(HEARTBEAT_MS)
        }
    }

    private suspend fun delete() {
        runCatching { registry.delete(publisherId) }
    }

    /**
     * Best-effort pick of a non-loopback IPv4 address. Returns null
     * when none can be enumerated - the publisher then skips the
     * heartbeat for this round, deferring to the next interval.
     */
    internal fun pickLanHost(): String? = pickLanHostFromInterfaces()

    companion object {
        const val HEARTBEAT_MS: Long = 5L * 60_000L

        /**
         * Top-level so tests can hit this without instantiating the
         * full publisher (which needs Auth + Registry + Settings).
         */
        fun pickLanHostFromInterfaces(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces()
                    ?.toList()
                    ?.asSequence()
                    ?.filter { it.isUp && !it.isLoopback }
                    ?.flatMap { it.inetAddresses.toList().asSequence() }
                    ?.firstOrNull { addr ->
                        !addr.isLoopbackAddress &&
                            !addr.isLinkLocalAddress &&
                            addr.hostAddress?.contains(':') == false  // IPv4 only
                    }?.hostAddress
            }.getOrNull()
        }

        private fun defaultDeviceLabel(): String =
            runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "Torve desktop"
    }
}
