package com.torve.domain.beta

enum class BetaApplicationStatus(val wireName: String) {
    NONE("none"),
    SUBMITTED("submitted"),
    APPROVED("approved"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    REVOKED("revoked"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(raw: String?): BetaApplicationStatus {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized } ?: UNKNOWN
        }
    }
}

enum class BetaGrantStatus(val wireName: String) {
    NONE("none"),
    ACTIVE("active"),
    EXPIRED("expired"),
    REVOKED("revoked"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(raw: String?): BetaGrantStatus {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized } ?: UNKNOWN
        }
    }
}

enum class BetaBlockedReason(val wireName: String) {
    NONE("none"),
    EMAIL_NOT_VERIFIED("email_not_verified"),
    BETA_SIGNUP_CLOSED("beta_signup_closed"),
    BETA_ACCESS_ENDED("beta_access_ended"),
    ALREADY_ACTIVE("already_active"),
    BETA_UNAVAILABLE("beta_unavailable"),
    RATE_LIMITED("rate_limited"),
    AUTH_REQUIRED("auth_required"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(raw: String?): BetaBlockedReason {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized } ?: UNKNOWN
        }
    }
}

data class DiscordBetaLinkCode(
    val code: String,
    val expiresAt: String? = null,
    val discordInviteUrl: String? = null,
)

data class BetaAccessState(
    val active: Boolean = false,
    val source: String? = null,
    val expiresAt: String? = null,
    val status: BetaGrantStatus = BetaGrantStatus.NONE,
) {
    companion object {
        val INACTIVE = BetaAccessState()
    }
}

data class BetaEligibilityState(
    val canApply: Boolean = false,
    val blockedReason: BetaBlockedReason = BetaBlockedReason.NONE,
    val isEmailVerificationRequired: Boolean = false,
)

data class BetaProgramStatus(
    val signedIn: Boolean = true,
    val discordLinked: Boolean = false,
    val applicationStatus: BetaApplicationStatus = BetaApplicationStatus.NONE,
    val betaAccess: BetaAccessState = BetaAccessState.INACTIVE,
    val daysRemaining: Int? = null,
    val eligibility: BetaEligibilityState = BetaEligibilityState(),
    val signupCloseAt: String? = DEFAULT_SIGNUP_CLOSE_AT,
    val freeAccessEndAt: String? = DEFAULT_FREE_ACCESS_END_AT,
    val discordInviteUrl: String? = null,
) {
    val betaAccessActive: Boolean get() = betaAccess.active
    val betaAccessExpiresAt: String? get() = betaAccess.expiresAt
    val canApply: Boolean get() = eligibility.canApply
    val blockedReason: BetaBlockedReason get() = eligibility.blockedReason
    val isEmailVerificationRequired: Boolean get() = eligibility.isEmailVerificationRequired

    companion object {
        const val DEFAULT_SIGNUP_CLOSE_AT = "2026-07-01T21:59:59Z"
        const val DEFAULT_FREE_ACCESS_END_AT = "2026-07-31T21:59:59Z"

        val SIGNED_OUT = BetaProgramStatus(
            signedIn = false,
            eligibility = BetaEligibilityState(
                canApply = false,
                blockedReason = BetaBlockedReason.AUTH_REQUIRED,
            ),
        )
    }
}

sealed interface BetaProgramError {
    data object EmailNotVerified : BetaProgramError
    data object SignupClosed : BetaProgramError
    data object AccessEnded : BetaProgramError
    data object RateLimited : BetaProgramError
    data object AuthRequired : BetaProgramError
    data object BetaUnavailable : BetaProgramError
    data object AlreadyActive : BetaProgramError
    data object Network : BetaProgramError
    data object Unknown : BetaProgramError
}

class BetaProgramException(
    val error: BetaProgramError,
    message: String? = null,
) : RuntimeException(message ?: error.toString())
