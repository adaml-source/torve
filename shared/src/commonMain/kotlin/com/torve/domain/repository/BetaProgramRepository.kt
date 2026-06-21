package com.torve.domain.repository

import com.torve.domain.beta.BetaProgramStatus
import com.torve.domain.beta.DiscordBetaLinkCode
import kotlinx.coroutines.flow.Flow

interface BetaProgramRepository {
    suspend fun generateDiscordBetaLinkCode(): DiscordBetaLinkCode
    suspend fun getBetaStatus(): BetaProgramStatus
    fun observeBetaStatus(): Flow<BetaProgramStatus>
    suspend fun refreshBetaStatus()
    suspend fun refreshAccessStateAfterBetaChange()
}
