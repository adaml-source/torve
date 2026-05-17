package com.torve.presentation.subscription

import com.torve.data.auth.AuthClient
import com.torve.data.auth.AuthResult
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.entitlement.EntitlementApi
import com.torve.data.subscription.RebateCodeApi
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionViewModelTest {
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
    fun needsVerificationReflectsBackendAccessState() = runTest(dispatcher) {
        val vmScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val vm = buildViewModel(
                subscriptionRepo = FakeSubscriptionRepository(
                    backendResult = BackendPremiumResult.DeviceBlocked(
                        reason = null,
                        needsVerification = true,
                    ),
                ),
                coroutineScope = vmScope,
            )

            advanceUntilIdle()

            assertTrue(vm.state.value.needsVerification)
            assertTrue(vm.state.value.hasEntitlement)
            assertFalse(vm.state.value.isPro)
            assertFalse(vm.state.value.deviceCapReached)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun sendVerificationEmailCallsBackendAndSurfacesConfirmationMessage() = runTest(dispatcher) {
        var resendCalls = 0
        var capturedEmail = ""
        val authClient = buildAuthClient()
        val vmScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val vm = buildViewModel(
                authClient = authClient,
                coroutineScope = vmScope,
                resendVerificationEmail = { email ->
                    resendCalls += 1
                    capturedEmail = email
                    AuthResult(success = true)
                },
            )
            advanceUntilIdle()

            vm.sendVerificationEmail()
            vm.sendVerificationEmail()
            advanceUntilIdle()

            assertEquals(1, resendCalls)
            assertEquals("user@torve.app", capturedEmail)
            assertEquals("Verification email sent!", vm.state.value.verificationEmailMessage)
            assertFalse(vm.state.value.isSendingVerificationEmail)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun purchaseGateBlocksMonthlyWhenBackendEntitlementAlreadyExists() = runTest(dispatcher) {
        val repo = FakeSubscriptionRepository(
            backendResult = BackendPremiumResult.Active,
            activeSubscription = testSubscription(
                tier = SubscriptionTier.MONTHLY,
                platform = "stripe",
            ),
        )
        val vmScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val vm = buildViewModel(subscriptionRepo = repo, coroutineScope = vmScope)
            advanceUntilIdle()

            var allowed = false
            vm.requireAccountForPurchase("Google Play", SubscriptionTier.MONTHLY) {
                allowed = true
            }
            advanceUntilIdle()

            assertFalse(allowed)
            assertEquals("Premium already active", vm.state.value.purchaseStatus?.title)
            assertTrue(repo.refreshDetailedCalls >= 1)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun purchaseGateBlocksLifetimeUpgradeAcrossDifferentStores() = runTest(dispatcher) {
        val repo = FakeSubscriptionRepository(
            backendResult = BackendPremiumResult.Active,
            activeSubscription = testSubscription(
                tier = SubscriptionTier.MONTHLY,
                platform = "stripe",
            ),
        )
        val vmScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val vm = buildViewModel(subscriptionRepo = repo, coroutineScope = vmScope)
            advanceUntilIdle()

            var allowed = false
            vm.requireAccountForPurchase("Google Play", SubscriptionTier.LIFETIME) {
                allowed = true
            }
            advanceUntilIdle()

            assertFalse(allowed)
            assertTrue(vm.state.value.purchaseStatus?.message.orEmpty().contains("Stripe"))
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun purchaseGateAllowsLifetimeUpgradeWithinSameStore() = runTest(dispatcher) {
        val repo = FakeSubscriptionRepository(
            backendResult = BackendPremiumResult.Active,
            activeSubscription = testSubscription(
                tier = SubscriptionTier.MONTHLY,
                platform = "google_play",
            ),
        )
        val vmScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val vm = buildViewModel(subscriptionRepo = repo, coroutineScope = vmScope)
            advanceUntilIdle()

            var allowed = false
            vm.requireAccountForPurchase("Google Play", SubscriptionTier.LIFETIME) {
                allowed = true
            }
            advanceUntilIdle()

            assertTrue(allowed)
            assertEquals(null, vm.state.value.purchaseStatus)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun googlePurchaseSuccessWaitsForBackendAccessStateBeforeUnlockingPremium() = runTest(dispatcher) {
        val repo = FakeSubscriptionRepository(
            backendResult = BackendPremiumResult.NoEntitlement,
            activeSubscription = null,
        )
        val entitlementApi = EntitlementApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    assertEquals("/me/purchases/google-play/verify", request.url.encodedPath)
                    respond(
                        content = """{"verified":true,"entitlement_granted":true,"premium_access":true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
            baseUrlProvider = { "https://api.torve.app" },
        )
        val vmScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val vm = buildViewModel(
                subscriptionRepo = repo,
                entitlementApi = entitlementApi,
                coroutineScope = vmScope,
            )
            advanceUntilIdle()

            vm.verifyGooglePurchase(
                productId = "torve.monthly",
                purchaseToken = "purchase-token",
                platform = "google_play_mobile",
            )
            advanceUntilIdle()

            assertEquals(0, repo.backendGrantCalls)
            assertFalse(vm.state.value.isPro)
            assertFalse(vm.state.value.hasEntitlement)
            assertEquals(PurchaseVerificationState.PENDING, vm.state.value.purchaseVerificationState)
        } finally {
            vmScope.cancel()
        }
    }

    private fun buildViewModel(
        subscriptionRepo: FakeSubscriptionRepository = FakeSubscriptionRepository(),
        authClient: AuthClient = buildAuthClient(),
        entitlementApi: EntitlementApi? = null,
        coroutineScope: CoroutineScope? = null,
        resendVerificationEmail: suspend (String) -> AuthResult = authClient::resendVerification,
    ): SubscriptionViewModel {
        val unusedClient = HttpClient(MockEngine { error("Unexpected request ${it.url}") })
        return SubscriptionViewModel(
            subscriptionRepo = subscriptionRepo,
            rebateCodeApi = RebateCodeApi(unusedClient),
            deviceIdProvider = FakeDeviceIdProvider(),
            authClient = authClient,
            entitlementApi = entitlementApi ?: EntitlementApi(
                httpClient = unusedClient,
                baseUrlProvider = { "https://api.torve.app" },
            ),
            prefsRepo = FakePreferencesRepository(),
            coroutineScope = coroutineScope,
            resendVerificationEmail = resendVerificationEmail,
        )
    }

    private fun buildAuthClient(
        onResend: (HttpRequestData) -> Unit = {},
    ): AuthClient {
        val secureStorage = FakeSecureStorage(
            mutableMapOf(
                AuthClient.KEY_AUTH_ACCESS_TOKEN to "access-token",
                AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh-token",
                "auth_token_expires_at" to "4102444800000",
            ),
        )
        val settings = FakeDeviceLocalSettingsRepository(
            mutableMapOf(
                AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                AuthClient.KEY_AUTH_USER_ID to "user-1",
                AuthClient.KEY_AUTH_IS_VERIFIED to "false",
            ),
        )
        val httpClient = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/auth/resend-verification") {
                    onResend(request)
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    error("Unexpected request ${request.url}")
                }
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
        return AuthClient(
            localSettingsRepository = settings,
            secureStorage = secureStorage,
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    device_id = "device-1",
                    installation_id = "install-1",
                    device_name = "Pixel",
                    device_type = "phone",
                    platform = "android",
                )
            },
        )
    }
}

private fun testSubscription(
    tier: SubscriptionTier = SubscriptionTier.LIFETIME,
    platform: String = "backend",
): Subscription = Subscription(
    id = "sub-1",
    tier = tier,
    purchaseToken = "backend_entitlement",
    expiresAt = null,
    isActive = true,
    platform = platform,
    purchasedAt = 1L,
)

private class FakeSubscriptionRepository(
    var backendResult: BackendPremiumResult = BackendPremiumResult.NoEntitlement,
    var activeSubscription: Subscription? = testSubscription(),
) : SubscriptionRepository {
    var refreshDetailedCalls: Int = 0
    var backendGrantCalls: Int = 0

    override suspend fun getActiveSubscription(): Subscription? {
        return activeSubscription
    }

    override suspend fun isPro(): Boolean = backendResult == BackendPremiumResult.Active

    override suspend fun hasAccess(feature: PremiumFeature): Boolean = isPro()

    override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) = Unit

    override suspend fun ensureFreeTier() = Unit

    override suspend fun restorePurchase(purchaseToken: String): Subscription? = getActiveSubscription()

    override suspend fun refreshFromBackend(): Boolean = backendResult == BackendPremiumResult.Active

    override suspend fun refreshFromBackendDetailed(): BackendPremiumResult {
        refreshDetailedCalls += 1
        return backendResult
    }

    override suspend fun onBackendEntitlementGranted(isPremium: Boolean) {
        backendGrantCalls += 1
    }
}

private class FakeDeviceIdProvider : DeviceIdProvider {
    override fun getDeviceId(): String = "install-1"
}

private class FakePreferencesRepository : PreferencesRepository {
    private val values = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class FakeDeviceLocalSettingsRepository(
    private val values: MutableMap<String, String>,
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
    private val values: MutableMap<String, String>,
) : SecureStorage {
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
