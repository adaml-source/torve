package com.torve.presentation.beta

import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.beta.BetaAccessState
import com.torve.domain.beta.BetaApplicationStatus
import com.torve.domain.beta.BetaBlockedReason
import com.torve.domain.beta.BetaEligibilityState
import com.torve.domain.beta.BetaGrantStatus
import com.torve.domain.beta.BetaProgramError
import com.torve.domain.beta.BetaProgramException
import com.torve.domain.beta.BetaProgramStatus
import com.torve.domain.beta.DiscordBetaLinkCode
import com.torve.domain.repository.BetaProgramRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetaProgramViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun signedOutStateDoesNotCallRepository() = runTest(dispatcher) {
        val repository = FakeBetaProgramRepository()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val viewModel = BetaProgramViewModel(
                repository = repository,
                authClient = authClient(signedIn = false),
                coroutineScope = scope,
            )

            viewModel.onOpenBetaProgram()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isSignedIn)
            assertEquals("Sign in required", viewModel.state.value.primaryBadge)
            assertEquals(0, repository.getStatusCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun emailUnverifiedUserCannotGenerateCode() = runTest(dispatcher) {
        val repository = FakeBetaProgramRepository(status = BetaFixtures.eligible)
        val viewModel = buildViewModel(repository, verified = false)

        viewModel.onOpenBetaProgram()
        advanceUntilIdle()
        viewModel.onGenerateCode()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isEmailVerificationRequired)
        assertEquals("Verify your email address before applying for beta access.", viewModel.state.value.errorMessage)
        assertEquals(0, repository.generateCalls)
    }

    @Test
    fun verifiedEligibleUserCanGenerateAndCopyCode() = runTest(dispatcher) {
        val repository = FakeBetaProgramRepository(status = BetaFixtures.eligible)
        val viewModel = buildViewModel(repository)

        viewModel.onOpenBetaProgram()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.showGenerateCode)

        viewModel.onGenerateCode()
        advanceUntilIdle()

        assertEquals(1, repository.generateCalls)
        assertEquals("BETA-ABC123", viewModel.state.value.generatedCode)
        assertTrue(viewModel.state.value.showCopyCode)

        viewModel.onCopyCode()

        assertTrue(viewModel.state.value.copySuccess)
    }

    @Test
    fun pendingActiveRejectedExpiredClosedAndEndedStatesRender() {
        assertEquals("Application Pending", BetaFixtures.pending.toUiState(BetaProgramUiState()).primaryBadge)
        assertEquals("Beta Active", BetaFixtures.active.toUiState(BetaProgramUiState()).primaryBadge)
        assertEquals("Not Approved", BetaFixtures.rejected.toUiState(BetaProgramUiState()).primaryBadge)
        assertEquals("Expired", BetaFixtures.expired.toUiState(BetaProgramUiState()).primaryBadge)
        assertEquals("Applications Closed", BetaFixtures.signupClosed.toUiState(BetaProgramUiState()).primaryBadge)
        assertEquals("Beta Access Ended", BetaFixtures.accessEnded.toUiState(BetaProgramUiState()).primaryBadge)
    }

    @Test
    fun directGenerateIsBlockedOnlyWhenBackendSaysCannotApply() = runTest(dispatcher) {
        val closedRepository = FakeBetaProgramRepository(status = BetaFixtures.signupClosed)
        val closedViewModel = buildViewModel(closedRepository)
        closedViewModel.onOpenBetaProgram()
        advanceUntilIdle()
        closedViewModel.onGenerateCode()
        advanceUntilIdle()

        assertEquals(0, closedRepository.generateCalls)
        assertEquals("Beta applications are currently closed.", closedViewModel.state.value.errorMessage)

        val endedRepository = FakeBetaProgramRepository(status = BetaFixtures.accessEnded)
        val endedViewModel = buildViewModel(endedRepository)
        endedViewModel.onOpenBetaProgram()
        advanceUntilIdle()
        endedViewModel.onGenerateCode()
        advanceUntilIdle()

        assertEquals(0, endedRepository.generateCalls)
        assertEquals(
            "The beta access period has ended. Discord beta tester access can still be available when applications are open.",
            endedViewModel.state.value.errorMessage,
        )
    }

    @Test
    fun postBetaAccessWindowCanStillGenerateWhenBackendAllowsBetaTesterApply() = runTest(dispatcher) {
        val repository = FakeBetaProgramRepository(status = BetaFixtures.postBetaAccessTesterEligible)
        val viewModel = buildViewModel(repository)

        viewModel.onOpenBetaProgram()
        advanceUntilIdle()
        viewModel.onGenerateCode()
        advanceUntilIdle()

        assertEquals(1, repository.generateCalls)
        assertEquals("BETA-ABC123", viewModel.state.value.generatedCode)
    }

    @Test
    fun refreshStatusUpdatesDisplayedState() = runTest(dispatcher) {
        val repository = FakeBetaProgramRepository(status = BetaFixtures.eligible)
        val viewModel = buildViewModel(repository)

        viewModel.onOpenBetaProgram()
        advanceUntilIdle()
        repository.status = BetaFixtures.pending
        viewModel.onRefreshStatus()
        advanceUntilIdle()

        assertEquals("Application Pending", viewModel.state.value.primaryBadge)
        assertEquals(1, repository.refreshCalls)
    }

    @Test
    fun networkErrorUsesFriendlyMessageWithoutRawBackendDetail() = runTest(dispatcher) {
        val repository = FakeBetaProgramRepository(
            getError = BetaProgramException(BetaProgramError.Network, "raw backend detail"),
        )
        val viewModel = buildViewModel(repository)

        viewModel.onOpenBetaProgram()
        advanceUntilIdle()

        assertEquals("Could not reach Torve. Try again shortly.", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.errorMessage.orEmpty().contains("raw backend detail"))
    }

    @Test
    fun premiumActiveTesterCopyDoesNotImplyFreeGrant() {
        assertTrue(BetaProgramCopy.PREMIUM_TESTER_APPLICATION.contains("still apply"))
        assertTrue(BetaProgramCopy.FREE_PREMIUM_NON_PREMIUM_ONLY.contains("approved testers"))
        assertFalse(BetaProgramCopy.FREE_PREMIUM_NON_PREMIUM_ONLY.contains("Subscription"))
    }

    @Test
    fun campaignDateVisibilityAndExpiryAreCapped() {
        val openState = BetaProgramUiState(
            isSignedIn = true,
            signupCloseAt = "2026-07-01T21:59:59Z",
        )

        assertTrue(
            shouldShowBetaProgramSettingsEntry(
                state = openState,
                hasPremiumAccess = true,
                nowEpochMs = kotlinx.datetime.Instant.parse("2026-07-01T21:59:58Z").toEpochMilliseconds(),
            ),
        )
        assertTrue(
            shouldShowBetaProgramSettingsEntry(
                state = openState,
                hasPremiumAccess = false,
                nowEpochMs = kotlinx.datetime.Instant.parse("2026-07-01T22:00:00Z").toEpochMilliseconds(),
            ),
        )

        val activePastCap = BetaProgramStatus(
            signedIn = true,
            betaAccess = BetaAccessState(
                active = true,
                expiresAt = "2026-09-01T00:00:00Z",
                status = BetaGrantStatus.ACTIVE,
            ),
            freeAccessEndAt = "2026-07-31T21:59:59Z",
        ).toUiState(BetaProgramUiState(), localEmailVerified = true)

        assertEquals("2026-07-31T21:59:59Z", activePastCap.betaAccessExpiresAt)
        assertTrue(activePastCap.body.contains("Jul 31, 2026"))
    }

    @Test
    fun requiredUserFacingCopyIsStable() {
        assertEquals(
            "Free beta access ends July 31, 2026. Beta tester opt-in can continue after that date.",
            BetaProgramCopy.DEADLINE,
        )
        assertEquals("Beta applications require a verified Torve account email.", BetaProgramCopy.EMAIL_VERIFICATION)
        assertEquals(
            "Open the Torve Discord, go to #beta-info, press Apply for Beta, and paste this code.",
            BetaProgramCopy.DISCORD_INSTRUCTION,
        )
        assertEquals(
            "Do not share credentials, playlist links, provider names, tokens, passwords, or private account details in Discord.",
            BetaProgramCopy.SAFETY,
        )
    }

    private fun buildViewModel(
        repository: FakeBetaProgramRepository,
        verified: Boolean = true,
    ): BetaProgramViewModel {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return BetaProgramViewModel(
            repository = repository,
            authClient = authClient(signedIn = true, verified = verified),
            coroutineScope = scope,
        )
    }

}

private object BetaFixtures {
    val eligible = BetaProgramStatus(
        signedIn = true,
        eligibility = BetaEligibilityState(
            canApply = true,
            blockedReason = BetaBlockedReason.NONE,
        ),
        discordInviteUrl = "https://discord.example/invite",
    )

    val pending = BetaProgramStatus(
        signedIn = true,
        applicationStatus = BetaApplicationStatus.SUBMITTED,
        eligibility = BetaEligibilityState(canApply = false),
    )

    val active = BetaProgramStatus(
        signedIn = true,
        applicationStatus = BetaApplicationStatus.APPROVED,
        betaAccess = BetaAccessState(
            active = true,
            source = "discord_beta",
            expiresAt = "2026-07-31T21:59:59Z",
            status = BetaGrantStatus.ACTIVE,
        ),
        eligibility = BetaEligibilityState(
            canApply = false,
            blockedReason = BetaBlockedReason.ALREADY_ACTIVE,
        ),
    )

    val rejected = BetaProgramStatus(
        signedIn = true,
        applicationStatus = BetaApplicationStatus.REJECTED,
        eligibility = BetaEligibilityState(
            canApply = true,
            blockedReason = BetaBlockedReason.NONE,
        ),
    )

    val expired = BetaProgramStatus(
        signedIn = true,
        applicationStatus = BetaApplicationStatus.EXPIRED,
        eligibility = BetaEligibilityState(
            canApply = true,
            blockedReason = BetaBlockedReason.NONE,
        ),
    )

    val signupClosed = BetaProgramStatus(
        signedIn = true,
        eligibility = BetaEligibilityState(
            canApply = false,
            blockedReason = BetaBlockedReason.BETA_SIGNUP_CLOSED,
        ),
    )

    val accessEnded = BetaProgramStatus(
        signedIn = true,
        eligibility = BetaEligibilityState(
            canApply = false,
            blockedReason = BetaBlockedReason.BETA_ACCESS_ENDED,
        ),
    )

    val postBetaAccessTesterEligible = BetaProgramStatus(
        signedIn = true,
        eligibility = BetaEligibilityState(
            canApply = true,
            blockedReason = BetaBlockedReason.NONE,
        ),
        signupCloseAt = "2026-07-01T21:59:59Z",
        freeAccessEndAt = "2026-07-31T21:59:59Z",
    )
}

private class FakeBetaProgramRepository(
    var status: BetaProgramStatus = BetaFixtures.eligible,
    private val getError: Throwable? = null,
    private val generateError: Throwable? = null,
) : BetaProgramRepository {
    var getStatusCalls = 0
    var generateCalls = 0
    var refreshCalls = 0
    private val flow = MutableStateFlow(status)

    override suspend fun generateDiscordBetaLinkCode(): DiscordBetaLinkCode {
        generateCalls += 1
        generateError?.let { throw it }
        return DiscordBetaLinkCode(
            code = "BETA-ABC123",
            expiresAt = "2026-06-01T12:00:00Z",
            discordInviteUrl = "https://discord.example/invite",
        )
    }

    override suspend fun getBetaStatus(): BetaProgramStatus {
        getStatusCalls += 1
        getError?.let { throw it }
        flow.value = status
        return status
    }

    override fun observeBetaStatus(): Flow<BetaProgramStatus> = flow

    override suspend fun refreshBetaStatus() {
        refreshCalls += 1
        flow.value = status
    }

    override suspend fun refreshAccessStateAfterBetaChange() = Unit
}

private fun authClient(
    signedIn: Boolean,
    verified: Boolean = true,
): AuthClient {
    val localValues = if (signedIn) {
        mutableMapOf(
            AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1",
            AuthClient.KEY_AUTH_IS_VERIFIED to verified.toString(),
        )
    } else {
        mutableMapOf()
    }
    val secureValues = if (signedIn) {
        mutableMapOf(
            AuthClient.KEY_AUTH_ACCESS_TOKEN to "access-token",
            AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh-token",
        )
    } else {
        mutableMapOf()
    }
    return AuthClient(
        localSettingsRepository = FakeDeviceLocalSettingsRepository(localValues),
        secureStorage = FakeSecureStorage(secureValues),
        httpClient = HttpClient(MockEngine { respond("{}") }),
        baseUrlProvider = { "https://api.test" },
        deviceRegistrationProvider = {
            DeviceRegistrationDto(
                installation_id = "installation-1",
                device_name = "Test",
                device_type = "desktop",
                platform = "test",
            )
        },
    )
}

private class FakeDeviceLocalSettingsRepository(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : DeviceLocalSettingsRepository {
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class FakeSecureStorage(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : SecureStorage {
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
