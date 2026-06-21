package com.torve.data.channels

internal data class ChannelCatalogRecoveryPlan(
    val fallbackActiveGeneration: Long?,
    val staleGenerationToDelete: Long?,
    val clearStagedGeneration: Boolean,
)

internal fun planChannelCatalogRecovery(
    activeGeneration: Long?,
    stagedGeneration: Long?,
): ChannelCatalogRecoveryPlan {
    if (stagedGeneration == null) {
        return ChannelCatalogRecoveryPlan(
            fallbackActiveGeneration = activeGeneration,
            staleGenerationToDelete = null,
            clearStagedGeneration = false,
        )
    }

    if (activeGeneration == null) {
        return ChannelCatalogRecoveryPlan(
            fallbackActiveGeneration = null,
            staleGenerationToDelete = stagedGeneration,
            clearStagedGeneration = true,
        )
    }

    if (stagedGeneration == activeGeneration) {
        return ChannelCatalogRecoveryPlan(
            fallbackActiveGeneration = activeGeneration,
            staleGenerationToDelete = null,
            clearStagedGeneration = true,
        )
    }

    return ChannelCatalogRecoveryPlan(
        fallbackActiveGeneration = activeGeneration,
        staleGenerationToDelete = stagedGeneration,
        clearStagedGeneration = true,
    )
}

internal fun nextChannelSnapshotGeneration(
    updatedAt: Long,
    activeGeneration: Long?,
): Long {
    if (activeGeneration == null) return updatedAt
    return if (updatedAt > activeGeneration) updatedAt else activeGeneration + 1
}

internal fun shouldAcceptIncomingChannelSnapshot(
    existingChannelCount: Int,
    incomingChannelCount: Int,
): Boolean {
    if (incomingChannelCount > 0) return true
    return existingChannelCount == 0
}
