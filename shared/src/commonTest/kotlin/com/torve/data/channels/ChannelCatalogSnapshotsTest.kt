package com.torve.data.channels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelCatalogSnapshotsTest {

    @Test
    fun crash_during_refresh_keeps_previous_active_generation() {
        val plan = planChannelCatalogRecovery(
            activeGeneration = 100L,
            stagedGeneration = 200L,
        )

        assertEquals(100L, plan.fallbackActiveGeneration)
        assertEquals(200L, plan.staleGenerationToDelete)
        assertTrue(plan.clearStagedGeneration)
    }

    @Test
    fun crash_after_staging_before_commit_discards_only_staged_generation() {
        val plan = planChannelCatalogRecovery(
            activeGeneration = 300L,
            stagedGeneration = 301L,
        )

        assertEquals(300L, plan.fallbackActiveGeneration)
        assertEquals(301L, plan.staleGenerationToDelete)
        assertTrue(plan.clearStagedGeneration)
    }

    @Test
    fun completed_commit_with_leftover_stage_marker_clears_marker_without_touching_active_snapshot() {
        val plan = planChannelCatalogRecovery(
            activeGeneration = 400L,
            stagedGeneration = 400L,
        )

        assertEquals(400L, plan.fallbackActiveGeneration)
        assertNull(plan.staleGenerationToDelete)
        assertTrue(plan.clearStagedGeneration)
    }

    @Test
    fun no_active_snapshot_after_crash_discards_incomplete_generation() {
        val plan = planChannelCatalogRecovery(
            activeGeneration = null,
            stagedGeneration = 500L,
        )

        assertNull(plan.fallbackActiveGeneration)
        assertEquals(500L, plan.staleGenerationToDelete)
        assertTrue(plan.clearStagedGeneration)
    }

    @Test
    fun no_staged_generation_requires_no_recovery() {
        val plan = planChannelCatalogRecovery(
            activeGeneration = 600L,
            stagedGeneration = null,
        )

        assertEquals(600L, plan.fallbackActiveGeneration)
        assertNull(plan.staleGenerationToDelete)
        assertFalse(plan.clearStagedGeneration)
    }

    @Test
    fun empty_replacement_is_rejected_when_existing_catalog_is_present() {
        assertFalse(
            shouldAcceptIncomingChannelSnapshot(
                existingChannelCount = 25,
                incomingChannelCount = 0,
            ),
        )
    }

    @Test
    fun empty_replacement_is_allowed_only_for_first_catalog_bootstrap() {
        assertTrue(
            shouldAcceptIncomingChannelSnapshot(
                existingChannelCount = 0,
                incomingChannelCount = 0,
            ),
        )
    }
}
