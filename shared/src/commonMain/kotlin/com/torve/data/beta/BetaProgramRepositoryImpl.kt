package com.torve.data.beta

import com.torve.data.auth.AuthClient
import com.torve.domain.beta.BetaAccessState
import com.torve.domain.beta.BetaApplicationStatus
import com.torve.domain.beta.BetaBlockedReason
import com.torve.domain.beta.BetaEligibilityState
import com.torve.domain.beta.BetaGrantStatus
import com.torve.domain.beta.BetaProgramException
import com.torve.domain.beta.BetaProgramStatus
import com.torve.domain.beta.DiscordBetaLinkCode
import com.torve.domain.repository.BetaProgramRepository
import com.torve.domain.repository.SubscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BetaProgramRepositoryImpl(
    private val api: BetaProgramApi,
    private val authClient: AuthClient,
    private val subscriptionRepository: SubscriptionRepository,
) : BetaProgramRepository {
    private val statusFlow = MutableStateFlow(BetaProgramStatus.SIGNED_OUT)

    override suspend fun generateDiscordBetaLinkCode(): DiscordBetaLinkCode {
        val user = authClient.getAuthenticatedUser()
            ?: throw BetaProgramException(com.torve.domain.beta.BetaProgramError.AuthRequired)
        if (!user.isVerified) {
            throw BetaProgramException(com.torve.domain.beta.BetaProgramError.EmailNotVerified)
        }
        val dto = api.generateDiscordBetaLinkCode()
        runCatching { refreshBetaStatus() }
        return dto.toDomain()
    }

    override suspend fun getBetaStatus(): BetaProgramStatus {
        val user = authClient.getAuthenticatedUser()
            ?: return BetaProgramStatus.SIGNED_OUT.also { statusFlow.value = it }
        val status = try {
            api.getBetaStatus().toDomain(signedIn = true)
        } catch (e: BetaProgramException) {
            if (!user.isVerified) {
                BetaProgramStatus(
                    signedIn = true,
                    eligibility = BetaEligibilityState(
                        canApply = false,
                        blockedReason = BetaBlockedReason.EMAIL_NOT_VERIFIED,
                        isEmailVerificationRequired = true,
                    ),
                )
            } else {
                throw e
            }
        }
        statusFlow.value = status
        return status
    }

    override fun observeBetaStatus(): Flow<BetaProgramStatus> = statusFlow.asStateFlow()

    override suspend fun refreshBetaStatus() {
        statusFlow.value = getBetaStatus()
    }

    override suspend fun refreshAccessStateAfterBetaChange() {
        subscriptionRepository.refreshFromBackendDetailed()
    }
}

fun DiscordBetaLinkCodeDto.toDomain(): DiscordBetaLinkCode {
    val resolvedCode = listOfNotNull(code, linkCode, discordLinkCode)
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    return DiscordBetaLinkCode(
        code = resolvedCode,
        expiresAt = expiresAt ?: expiresAtCamel,
        discordInviteUrl = discordInviteUrl?.takeIf { it.isNotBlank() },
    )
}

fun DiscordBetaStatusDto.toDomain(signedIn: Boolean): BetaProgramStatus {
    val application = BetaApplicationStatus.fromWire(betaApplicationStatus ?: applicationStatus)
    val accessDto = betaAccess
    val access = BetaAccessState(
        active = accessDto?.active ?: betaAccessActive,
        source = accessDto?.source,
        expiresAt = accessDto?.expiresAt ?: betaAccessExpiresAt,
        status = BetaGrantStatus.fromWire(accessDto?.status).let { parsed ->
            if (parsed == BetaGrantStatus.UNKNOWN && (accessDto?.active == true || betaAccessActive)) {
                BetaGrantStatus.ACTIVE
            } else {
                parsed
            }
        },
    )
    val blocked = BetaBlockedReason.fromWire(blockedReason)
    val canApplyResolved = canApply ?: deriveCanApply(
        applicationStatus = application,
        activeAccess = access.active,
        blockedReason = blocked,
    )
    return BetaProgramStatus(
        signedIn = signedIn,
        discordLinked = discordLinkedCamel ?: discordLinked,
        applicationStatus = application,
        betaAccess = access,
        daysRemaining = daysRemaining,
        eligibility = BetaEligibilityState(
            canApply = canApplyResolved,
            blockedReason = blocked,
            isEmailVerificationRequired = blocked == BetaBlockedReason.EMAIL_NOT_VERIFIED,
        ),
        signupCloseAt = betaSignupCloseAt ?: BetaProgramStatus.DEFAULT_SIGNUP_CLOSE_AT,
        freeAccessEndAt = betaFreeAccessEndAt ?: BetaProgramStatus.DEFAULT_FREE_ACCESS_END_AT,
        discordInviteUrl = discordInviteUrl?.takeIf { it.isNotBlank() },
    )
}

fun deriveCanApply(
    applicationStatus: BetaApplicationStatus,
    activeAccess: Boolean,
    blockedReason: BetaBlockedReason,
): Boolean {
    if (activeAccess) return false
    return when (blockedReason) {
        BetaBlockedReason.NONE,
        BetaBlockedReason.UNKNOWN,
        -> applicationStatus in setOf(
            BetaApplicationStatus.NONE,
            BetaApplicationStatus.REJECTED,
            BetaApplicationStatus.EXPIRED,
        )
        BetaBlockedReason.EMAIL_NOT_VERIFIED,
        BetaBlockedReason.BETA_SIGNUP_CLOSED,
        BetaBlockedReason.BETA_ACCESS_ENDED,
        BetaBlockedReason.ALREADY_ACTIVE,
        BetaBlockedReason.BETA_UNAVAILABLE,
        BetaBlockedReason.RATE_LIMITED,
        BetaBlockedReason.AUTH_REQUIRED,
        -> false
    }
}
