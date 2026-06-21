package com.torve.presentation.beta

import com.torve.data.auth.AuthClient
import com.torve.domain.beta.BetaApplicationStatus
import com.torve.domain.beta.BetaBlockedReason
import com.torve.domain.beta.BetaProgramError
import com.torve.domain.beta.BetaProgramException
import com.torve.domain.beta.BetaProgramStatus
import com.torve.domain.repository.BetaProgramRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class BetaProgramViewModel(
    private val repository: BetaProgramRepository,
    private val authClient: AuthClient,
    coroutineScope: CoroutineScope? = null,
) {
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(BetaProgramUiState())
    val state: StateFlow<BetaProgramUiState> = _state.asStateFlow()

    fun onOpenBetaProgram() {
        load(initial = true)
    }

    fun onRetry() {
        load(initial = true)
    }

    fun onRefreshStatus() {
        scope.launch {
            _state.update { it.copy(isRefreshing = true, errorMessage = null) }
            runCatching { repository.refreshBetaStatus() }
                .onSuccess {
                    val status = repository.getBetaStatus()
                    _state.value = status.toUiState(
                        current = _state.value,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isRefreshing = false,
                            errorMessage = mapError(error),
                        )
                    }
                }
        }
    }

    fun onGenerateCode() {
        val current = _state.value
        if (!current.isSignedIn) {
            _state.update { it.copy(errorMessage = mapBetaError(BetaProgramError.AuthRequired)) }
            return
        }
        if (!current.isEmailVerified || current.isEmailVerificationRequired) {
            _state.update {
                it.copy(
                    showGenerateCode = false,
                    errorMessage = mapBetaError(BetaProgramError.EmailNotVerified),
                )
            }
            return
        }
        if (!current.canApply) {
            _state.update {
                it.copy(
                    showGenerateCode = false,
                    errorMessage = mapBlockedReason(current.blockedReason),
                )
            }
            return
        }
        scope.launch {
            _state.update {
                it.copy(
                    isGeneratingCode = true,
                    errorMessage = null,
                    copySuccess = false,
                )
            }
            runCatching { repository.generateDiscordBetaLinkCode() }
                .onSuccess { code ->
                    runCatching { repository.refreshAccessStateAfterBetaChange() }
                    val status = runCatching { repository.getBetaStatus() }.getOrNull()
                    _state.value = (status?.toUiState(_state.value) ?: _state.value).copy(
                        isGeneratingCode = false,
                        generatedCode = code.code,
                        generatedCodeExpiresAt = code.expiresAt,
                        discordInviteUrl = code.discordInviteUrl ?: status?.discordInviteUrl ?: _state.value.discordInviteUrl,
                        openDiscordAvailable = !(code.discordInviteUrl ?: status?.discordInviteUrl ?: _state.value.discordInviteUrl).isNullOrBlank(),
                        showCopyCode = code.code.isNotBlank(),
                        showOpenDiscord = !(code.discordInviteUrl ?: status?.discordInviteUrl ?: _state.value.discordInviteUrl).isNullOrBlank(),
                        primaryBadge = "Code Generated",
                        primaryActionLabel = "Copy Code",
                        secondaryActionLabel = if (!(code.discordInviteUrl ?: status?.discordInviteUrl ?: _state.value.discordInviteUrl).isNullOrBlank()) {
                            "Open Discord"
                        } else {
                            null
                        },
                        body = BetaProgramCopy.DISCORD_INSTRUCTION,
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isGeneratingCode = false,
                            errorMessage = mapError(error),
                        )
                    }
                }
        }
    }

    fun onCopyCode() {
        _state.update { it.copy(copySuccess = true) }
    }

    fun consumeCopySuccess() {
        _state.update { it.copy(copySuccess = false) }
    }

    fun onOpenDiscord() {
        // Platform UI owns actually opening the URL.
    }

    fun onVerifyEmail() {
        scope.launch {
            authClient.checkVerificationStatus()
            load(initial = false)
        }
    }

    fun onResendVerificationEmail() {
        scope.launch {
            val email = authClient.getCurrentUser()?.email
            if (email.isNullOrBlank()) {
                _state.update { it.copy(errorMessage = "Please sign in again.") }
                return@launch
            }
            val result = authClient.resendVerification(email)
            _state.update {
                it.copy(
                    errorMessage = if (result.success) {
                        null
                    } else {
                        result.error ?: "Something went wrong. Try again later."
                    },
                )
            }
        }
    }

    fun onDismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun load(initial: Boolean) {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = initial,
                    errorMessage = null,
                )
            }
            val localUser = authClient.getAuthenticatedUser()
            if (localUser == null) {
                _state.value = BetaProgramStatus.SIGNED_OUT.toUiState(_state.value)
                return@launch
            }
            runCatching { repository.getBetaStatus() }
                .onSuccess { status ->
                    _state.value = status.toUiState(
                        current = _state.value,
                        isLoading = false,
                        localEmailVerified = localUser.isVerified,
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = mapError(error),
                        )
                    }
                }
        }
    }
}

fun BetaProgramStatus.toSettingsCardState(): BetaProgramSettingsCardState {
    val safeExpiry = safeBetaAccessExpiry()
    return when {
        !signedIn -> BetaProgramSettingsCardState(
            subtitle = "Sign in to apply for the Torve Beta Program.",
            badge = "Sign in",
        )
        isEmailVerificationRequired -> BetaProgramSettingsCardState(
            subtitle = "Verify your email to apply.",
            badge = "Verify email",
        )
        betaAccessActive -> BetaProgramSettingsCardState(
            subtitle = safeExpiry?.let { "Free beta access active until ${it.formatBetaDate()}." } ?: "Beta access is active.",
            badge = "Beta active",
        )
        applicationStatus == BetaApplicationStatus.SUBMITTED -> BetaProgramSettingsCardState(
            subtitle = "Your application is waiting for review.",
            badge = "Pending",
        )
        blockedReason == BetaBlockedReason.BETA_SIGNUP_CLOSED -> BetaProgramSettingsCardState(
            subtitle = "Beta applications are currently closed.",
            badge = "Closed",
        )
        blockedReason == BetaBlockedReason.BETA_ACCESS_ENDED -> BetaProgramSettingsCardState(
            subtitle = "Free beta access ended. Beta tester access may still be available.",
            badge = "Beta ended",
        )
        else -> BetaProgramSettingsCardState()
    }
}

fun BetaProgramStatus.toUiState(
    current: BetaProgramUiState,
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    localEmailVerified: Boolean? = null,
): BetaProgramUiState {
    val emailVerified = localEmailVerified ?: !isEmailVerificationRequired
    val safeExpiry = safeBetaAccessExpiry()
    val badge: String
    val body: String
    val primary: String
    val secondary: String?
    val showGenerate: Boolean
    val showVerify: Boolean
    val showRefresh: Boolean
    when {
        !signedIn -> {
            badge = "Sign in required"
            body = "Sign in to apply for the Torve Beta Program."
            primary = "Sign In"
            secondary = null
            showGenerate = false
            showVerify = false
            showRefresh = false
        }
        isEmailVerificationRequired || !emailVerified -> {
            badge = "Email verification required"
            body = "Verify your Torve account email before applying for beta access."
            primary = "Verify Email"
            secondary = "Resend Verification Email"
            showGenerate = false
            showVerify = true
            showRefresh = false
        }
        betaAccessActive -> {
            badge = "Beta Active"
            body = "Free beta access is active until ${safeExpiry?.formatBetaDate() ?: "July 31, 2026"}. Beta builds may be unstable."
            primary = "View Status"
            secondary = if (!discordInviteUrl.isNullOrBlank()) "Open Discord" else null
            showGenerate = false
            showVerify = false
            showRefresh = true
        }
        applicationStatus == BetaApplicationStatus.SUBMITTED -> {
            badge = "Application Pending"
            body = "Your beta application is waiting for review."
            primary = "Refresh Status"
            secondary = null
            showGenerate = false
            showVerify = false
            showRefresh = true
        }
        applicationStatus == BetaApplicationStatus.REJECTED -> {
            badge = "Not Approved"
            body = "Your beta application was not approved right now."
            primary = if (canApply) "Generate New Code" else "Refresh Status"
            secondary = null
            showGenerate = canApply
            showVerify = false
            showRefresh = !canApply
        }
        applicationStatus == BetaApplicationStatus.EXPIRED -> {
            badge = "Expired"
            body = "Your free beta access has expired."
            primary = if (canApply) "Apply Again" else "Refresh Status"
            secondary = null
            showGenerate = canApply
            showVerify = false
            showRefresh = !canApply
        }
        blockedReason == BetaBlockedReason.BETA_ACCESS_ENDED && !canApply -> {
            badge = "Beta Access Ended"
            body = "The beta access period has ended. Discord beta tester access may still be available when applications are open."
            primary = "Refresh Status"
            secondary = null
            showGenerate = false
            showVerify = false
            showRefresh = true
        }
        blockedReason == BetaBlockedReason.BETA_SIGNUP_CLOSED && !canApply -> {
            badge = "Applications Closed"
            body = "Beta applications are currently closed. Approved testers can continue using the Discord beta area."
            primary = "Refresh Status"
            secondary = null
            showGenerate = false
            showVerify = false
            showRefresh = true
        }
        else -> {
            badge = "Beta applications open"
            body = "Apply for Discord beta tester access. Free beta access only runs until July 31, 2026."
            primary = "Generate Discord Link Code"
            secondary = "Learn More"
            showGenerate = canApply
            showVerify = false
            showRefresh = !canApply
        }
    }
    return current.copy(
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        applicationStatus = applicationStatus,
        betaGrantStatus = betaAccess.status,
        betaAccessActive = betaAccessActive,
        betaAccessExpiresAt = safeExpiry,
        daysRemaining = daysRemaining,
        canApply = canApply,
        blockedReason = blockedReason,
        isEmailVerificationRequired = isEmailVerificationRequired || !emailVerified,
        signupCloseAt = signupCloseAt,
        freeAccessEndAt = freeAccessEndAt,
        discordInviteUrl = discordInviteUrl,
        openDiscordAvailable = !discordInviteUrl.isNullOrBlank(),
        isSignedIn = signedIn,
        isEmailVerified = emailVerified,
        primaryBadge = badge,
        body = body,
        primaryActionLabel = primary,
        secondaryActionLabel = secondary,
        showGenerateCode = showGenerate,
        showVerifyEmail = showVerify,
        showRefresh = showRefresh,
        showOpenDiscord = !discordInviteUrl.isNullOrBlank(),
        lastUpdatedAt = Clock.System.now().toEpochMilliseconds(),
        errorMessage = null,
    )
}

private fun BetaProgramStatus.safeBetaAccessExpiry(): String? {
    val rawExpiry = betaAccessExpiresAt?.trim()?.takeIf { it.isNotEmpty() }
    val cap = (freeAccessEndAt ?: BetaProgramStatus.DEFAULT_FREE_ACCESS_END_AT)
        .trim()
        .takeIf { it.isNotEmpty() }
        ?: BetaProgramStatus.DEFAULT_FREE_ACCESS_END_AT
    val expiryInstant = rawExpiry?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val capInstant = runCatching { Instant.parse(cap) }.getOrNull()
    return when {
        expiryInstant != null && capInstant != null && expiryInstant > capInstant -> cap
        rawExpiry != null -> rawExpiry
        else -> null
    }
}

fun mapBetaError(error: BetaProgramError): String = when (error) {
    BetaProgramError.EmailNotVerified -> "Verify your email address before applying for beta access."
    BetaProgramError.SignupClosed -> "Beta applications are currently closed."
    BetaProgramError.AccessEnded -> "The beta access period has ended. Discord beta tester access can still be available when applications are open."
    BetaProgramError.RateLimited -> "Please wait before requesting another code."
    BetaProgramError.AuthRequired -> "Please sign in again."
    BetaProgramError.BetaUnavailable -> "Beta applications are currently closed."
    BetaProgramError.AlreadyActive -> "A beta application or beta access is already active for this account. Refresh status to continue."
    BetaProgramError.Network -> "Could not reach Torve. Try again shortly."
    BetaProgramError.Unknown -> "Something went wrong. Try again later."
}

private fun mapError(error: Throwable): String {
    val beta = (error as? BetaProgramException)?.error ?: BetaProgramError.Network
    return mapBetaError(beta)
}

private fun mapBlockedReason(reason: BetaBlockedReason): String = when (reason) {
    BetaBlockedReason.EMAIL_NOT_VERIFIED -> mapBetaError(BetaProgramError.EmailNotVerified)
    BetaBlockedReason.BETA_SIGNUP_CLOSED -> mapBetaError(BetaProgramError.SignupClosed)
    BetaBlockedReason.BETA_ACCESS_ENDED -> mapBetaError(BetaProgramError.AccessEnded)
    BetaBlockedReason.ALREADY_ACTIVE -> mapBetaError(BetaProgramError.AlreadyActive)
    BetaBlockedReason.BETA_UNAVAILABLE -> mapBetaError(BetaProgramError.BetaUnavailable)
    BetaBlockedReason.RATE_LIMITED -> mapBetaError(BetaProgramError.RateLimited)
    BetaBlockedReason.AUTH_REQUIRED -> mapBetaError(BetaProgramError.AuthRequired)
    BetaBlockedReason.NONE,
    BetaBlockedReason.UNKNOWN,
    -> mapBetaError(BetaProgramError.BetaUnavailable)
}

@Suppress("UNUSED_PARAMETER")
fun shouldShowBetaProgramSettingsEntry(
    state: BetaProgramUiState,
    hasPremiumAccess: Boolean,
    nowEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
): Boolean {
    return true
}

fun String.formatBetaDate(): String {
    val raw = trim()
    if (raw.isBlank()) return raw
    return runCatching {
        val date = kotlinx.datetime.Instant.parse(raw)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
        val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        "$month ${date.dayOfMonth}, ${date.year}"
    }.getOrDefault(raw.take(10))
}
