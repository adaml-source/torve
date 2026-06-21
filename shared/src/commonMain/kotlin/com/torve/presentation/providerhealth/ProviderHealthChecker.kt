package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthEntry

/**
 * One pluggable check. Implementations call into existing test-connection
 * APIs (DebridClient.verifyApiKey, LibraryOverlayService.testConnection,
 * StremioAddonClient.getManifest, etc.) and return a fully-populated
 * [ProviderHealthEntry] including status, message, and nextAction.
 *
 * MUST NOT include secret values in the returned message — `lastCheckedAt`
 * is filled in by [ProviderHealthCoordinator] so individual checkers don't
 * have to import a clock.
 */
interface ProviderHealthChecker {
    /** Stable id of the row this check produces. */
    val providerKey: String

    /**
     * Run the check. Implementations should never throw — wrap real
     * failures into a RED [ProviderHealthEntry] with an actionable
     * message.
     */
    suspend fun check(): ProviderHealthEntry
}
