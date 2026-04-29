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

    private fun buildViewModel(
        subscriptionRepo: FakeSubscriptionRepository = FakeSubscriptionRepository(),
        authClient: AuthClient = buildAuthClient(),
        coroutineScope: CoroutineScope? = null,
        resendVerificationEmail: suspend (String) -> AuthResult = authClient::resendVerification,
    ): SubscriptionViewModel {
        val unusedClient = HttpClient(MockEngine { error("Unexpected request ${it.url}") })
        return SubscriptionViewModel(
            subscriptionRepo = subscriptionRepo,
            rebateCodeApi = RebateCodeApi(unusedClient),
            deviceIdProvider = FakeDeviceIdProvider(),
            authClient = authClient,
            entitlementApi = EntitlementApi(
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

private class FakeSubscriptionRepository(
    var backendResult: BackendPremiumResult = BackendPremiumResult.NoEntitlement,
) : SubscriptionRepository {
    override suspend fun getActiveSubscription(): Subscription? {
        return Subscription(
            id = "sub-1",
            tier = SubscriptionTier.LIFETIME,
            purchaseToken = "backend_entitlement",
            expiresAt = null,
            isActive = true,
            platform = "backend",
            purchasedAt = 1L,
        )
    }

    override suspend fun isPro(): Boolean = backendResult == BackendPremiumResult.Active

    override suspend fun hasAccess(feature: PremiumFeature): Boolean = isPro()

    override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) = Unit

    override suspend fun ensureFreeTier() = Unit

    override suspend fun restorePurchase(purchaseToken: String): Subscription? = getActiveSubscription()

    override suspend fun refreshFromBackend(): Boolean = backendResult == BackendPremiumResult.Active

    override suspend fun refreshFromBackendDetailed(): BackendPremiumResult = backendResult

    override suspend fun onBackendEntitlementGranted(isPremium: Boolean) = Unit
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
