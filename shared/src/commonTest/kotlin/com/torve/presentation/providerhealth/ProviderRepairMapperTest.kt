package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the (category, status, message) → repair-action matrix.
 *
 * Goals:
 *   - Transferable + UNCONFIGURED → Transfer leads, Reenter is fallback.
 *   - Transferable + RED-auth → Reenter leads, Transfer fallback.
 *   - Any provider-down RED → Diagnostics + Settings, **never** Transfer.
 *   - Non-transferable categories → never offer Transfer.
 *   - YELLOW companion-config gap → Transfer + Settings.
 */
class ProviderRepairMapperTest {

    @Test
    fun unconfiguredDebridOffersTransferThenReenter() {
        val actions = ProviderRepairMapper.actionsFor(entry(
            category = ProviderHealthCategory.DEBRID,
            status = ProviderHealthStatus.UNCONFIGURED,
            message = "Not connected. Add your Real-Debrid API key.",
        ))
        assertEquals(
            listOf(
                ProviderRepairAction.TransferFromAnotherDevice,
                ProviderRepairAction.ReenterCredentials,
            ),
            actions,
        )
    }

    @Test
    fun unconfiguredIptvNeverOffersTransferBecauseCatalogHasNoIptvKeys() {
        val actions = ProviderRepairMapper.actionsFor(entry(
            category = ProviderHealthCategory.IPTV,
            status = ProviderHealthStatus.UNCONFIGURED,
            message = "Add your IPTV provider URL.",
        ))
        assertEquals(
            listOf(ProviderRepairAction.ReenterCredentials),
            actions,
        )
        assertFalse(actions.contains(ProviderRepairAction.TransferFromAnotherDevice))
    }

    @Test
    fun unconfiguredAddonNeverOffersAnything() {
        // ADDON is non-credential. The mapper still allows reenter as
        // the universal fallback so the row isn't action-less.
        val actions = ProviderRepairMapper.actionsFor(entry(
            category = ProviderHealthCategory.ADDON,
            status = ProviderHealthStatus.UNCONFIGURED,
            message = "Install an addon.",
        ))
        assertEquals(listOf(ProviderRepairAction.ReenterCredentials), actions)
    }

    @Test
    fun redProviderUnreachableNeverSuggestsTransfer() {
        val variants = listOf(
            "Couldn't reach Real-Debrid: connection refused",
            "Server unreachable (502 bad gateway)",
            "Timed out connecting to api.real-debrid.com",
            "Network unavailable",
            "DNS resolution failed",
        )
        for (msg in variants) {
            val actions = ProviderRepairMapper.actionsFor(entry(
                category = ProviderHealthCategory.DEBRID,
                status = ProviderHealthStatus.RED,
                message = msg,
            ))
            assertEquals(
                listOf(
                    ProviderRepairAction.OpenDiagnostics,
                    ProviderRepairAction.OpenProviderSettings,
                ),
                actions,
                "Unreachable RED should never include Transfer; failed for msg='$msg'",
            )
            assertFalse(
                actions.contains(ProviderRepairAction.TransferFromAnotherDevice),
                "Transfer leaked into unreachable-RED for msg='$msg'",
            )
        }
    }

    @Test
    fun redAuthFailureLeadsWithReenterTransferAsFallback() {
        val authMessages = listOf(
            "401 unauthorized — re-enter API key",
            "Token expired",
            "Authentication rejected",
            "API key invalid",
            "Token revoked",
        )
        for (msg in authMessages) {
            val actions = ProviderRepairMapper.actionsFor(entry(
                category = ProviderHealthCategory.PLEX_JELLYFIN,
                status = ProviderHealthStatus.RED,
                message = msg,
            ))
            assertEquals(
                ProviderRepairAction.ReenterCredentials,
                actions.first(),
                "Auth-RED should lead with Reenter (msg='$msg', got=$actions)",
            )
            assertTrue(
                ProviderRepairAction.TransferFromAnotherDevice in actions,
                "Transfer should be available as fallback (msg='$msg')",
            )
        }
    }

    @Test
    fun redAuthFailureOnNonTransferableCategoryOffersOnlyReenter() {
        val actions = ProviderRepairMapper.actionsFor(entry(
            category = ProviderHealthCategory.IPTV,
            status = ProviderHealthStatus.RED,
            message = "401 unauthorized",
        ))
        assertEquals(listOf(ProviderRepairAction.ReenterCredentials), actions)
    }

    @Test
    fun yellowCompanionConfigGapOffersTransferThenSettings() {
        val actions = ProviderRepairMapper.actionsFor(entry(
            category = ProviderHealthCategory.PLEX_JELLYFIN,
            status = ProviderHealthStatus.YELLOW,
            message = "Plex token present but no server URL — set Plex server URL.",
        ))
        assertEquals(
            listOf(
                ProviderRepairAction.TransferFromAnotherDevice,
                ProviderRepairAction.OpenProviderSettings,
            ),
            actions,
        )
    }

    @Test
    fun yellowGenericOffersOnlyOpenSettings() {
        val actions = ProviderRepairMapper.actionsFor(entry(
            category = ProviderHealthCategory.DEBRID,
            status = ProviderHealthStatus.YELLOW,
            message = "Real-Debrid premium expires in 6 days.",
        ))
        assertEquals(listOf(ProviderRepairAction.OpenProviderSettings), actions)
    }

    @Test
    fun greenAndUnknownOfferNothing() {
        for (status in listOf(ProviderHealthStatus.GREEN, ProviderHealthStatus.UNKNOWN)) {
            val actions = ProviderRepairMapper.actionsFor(entry(
                category = ProviderHealthCategory.DEBRID,
                status = status,
                message = if (status == ProviderHealthStatus.GREEN) "Real-Debrid is connected." else null,
            ))
            assertEquals(emptyList(), actions, "status=$status should produce no actions")
        }
    }

    @Test
    fun usenetCategoriesAreTransferable() {
        for (cat in listOf(
            ProviderHealthCategory.USENET_PROVIDER,
            ProviderHealthCategory.USENET_INDEXER,
            ProviderHealthCategory.DOWNLOAD_CLIENT,
        )) {
            val actions = ProviderRepairMapper.actionsFor(entry(
                category = cat,
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "Not configured.",
            ))
            assertTrue(
                ProviderRepairAction.TransferFromAnotherDevice in actions,
                "$cat should offer Transfer; got $actions",
            )
        }
    }

    private fun entry(
        category: ProviderHealthCategory,
        status: ProviderHealthStatus,
        message: String? = null,
    ): ProviderHealthEntry = ProviderHealthEntry(
        category = category,
        providerKey = "test:${category.name.lowercase()}",
        label = category.name,
        status = status,
        message = message,
    )
}
