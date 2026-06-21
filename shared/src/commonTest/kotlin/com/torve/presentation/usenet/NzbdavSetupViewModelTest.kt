package com.torve.presentation.usenet

import com.torve.data.usenet.UsenetApiException
import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.NzbdavStatusResponseDto
import com.torve.data.usenet.model.NzbdavTestResponseDto
import com.torve.data.usenet.model.ResolvedStreamDto
import com.torve.data.usenet.model.UsenetCancelResponseDto
import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NzbdavSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    // ── status rendering ────────────────────────────────────────────────

    @Test
    fun statusRendersNotConfigured() = runTest(dispatcher) {
        val repo = FakeUsenetRepository(statusResponse = NzbdavStatusResponseDto(configured = false))
        val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
        advanceUntilIdle()
        assertIs<NzbdavStatus.NotConfigured>(vm.state.value.status)
    }

    @Test
    fun statusRendersConnected() = runTest(dispatcher) {
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(configured = true, isEnabled = true),
        )
        val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
        advanceUntilIdle()
        val status = assertIs<NzbdavStatus.Connected>(vm.state.value.status)
        assertFalse(status.degraded)
    }

    @Test
    fun statusRendersDegradedWithoutLeakingReason() = runTest(dispatcher) {
        // Backend sends a reason token; the VM must surface only the
        // neutral `degraded = true` flag. The raw token stays in the DTO
        // but must not be stored on the UI state anywhere.
        val leakyReason = "upstream_below_version_floor:v1.2.3"
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(
                configured = true,
                isEnabled = true,
                degraded = true,
                reason = leakyReason,
            ),
        )
        val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
        advanceUntilIdle()
        val status = assertIs<NzbdavStatus.Connected>(vm.state.value.status)
        assertTrue(status.degraded)
        // Token-leak guard: assert the reason token appears nowhere on UI state.
        val stateString = vm.state.value.toString()
        assertFalse(
            stateString.contains(leakyReason),
            "Backend reason token leaked into UI state: $stateString",
        )
    }

    @Test
    fun statusRendersConnectionFailedOnBackendError() = runTest(dispatcher) {
        val repo = FakeUsenetRepository(statusThrow = true)
        val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
        advanceUntilIdle()
        assertIs<NzbdavStatus.ConnectionFailed>(vm.state.value.status)
    }

    // ── test connection ─────────────────────────────────────────────────

    @Test
    fun testConnectionSuccess() = runTest(dispatcher) {
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(configured = false),
            testResponse = NzbdavTestResponseDto(ok = true, degraded = false, reason = null),
        )
        val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
        advanceUntilIdle()
        vm.updateBaseUrl("https://nzbdav.example.com")
        vm.updateApiKey("abc123")
        vm.test()
        advanceUntilIdle()
        assertIs<NzbdavTestResult.Ok>(vm.state.value.lastTestResult)
        assertFalse(vm.state.value.isTesting)
    }

    @Test
    fun testConnectionDegradedMapsToNeutralDegradedOk() = runTest(dispatcher) {
        val leakyReason = "upstream_below_version_floor"
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(configured = false),
            testResponse = NzbdavTestResponseDto(ok = true, degraded = true, reason = leakyReason),
        )
        val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
        advanceUntilIdle()
        vm.updateBaseUrl("https://u")
        vm.updateApiKey("k")
        vm.test()
        advanceUntilIdle()
        assertIs<NzbdavTestResult.DegradedOk>(vm.state.value.lastTestResult)
        assertFalse(vm.state.value.toString().contains(leakyReason))
    }

    @Test
    fun testConnectionFailureProducesNeutralCopyNoRawText() = runTest(dispatcher) {
        val leakyBody = "<html>500 Internal Server Error — detail: NzbDAV auth handshake timed out</html>"
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(configured = false),
            testException = UsenetApiException(
                errorCode = "integration_test_failed",
                message = "POST /integrations/nzbdav/test failed (500)",
                httpStatus = 500,
                body = leakyBody,
            ),
        )
        val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
        advanceUntilIdle()
        vm.updateBaseUrl("https://u")
        vm.updateApiKey("k")
        vm.test()
        advanceUntilIdle()
        assertIs<NzbdavTestResult.Failed>(vm.state.value.lastTestResult)
        val stateString = vm.state.value.toString()
        assertFalse(stateString.contains(leakyBody))
        assertFalse(stateString.contains("500"))
        assertFalse(stateString.contains("NzbDAV"))
    }

    @Test
    fun testConnectionBlanksProduceMissingFields() = runTest(dispatcher) {
        val repo = FakeUsenetRepository(statusResponse = NzbdavStatusResponseDto(configured = false))
        val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
        advanceUntilIdle()
        vm.test()
        assertIs<NzbdavTestResult.MissingFields>(vm.state.value.lastTestResult)
    }

    // ── save ────────────────────────────────────────────────────────────

    @Test
    fun savePersistsAndRefreshesStatus() = runTest(dispatcher) {
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(configured = false),
            savedStatus = NzbdavStatusResponseDto(configured = true, isEnabled = true),
        )
        val secrets = FakeSecretStore()
        val vm = NzbdavSetupViewModel(repo, secrets)
        advanceUntilIdle()
        vm.updateBaseUrl("https://u")
        vm.updateApiKey("k")
        vm.save()
        advanceUntilIdle()
        val status = assertIs<NzbdavStatus.Connected>(vm.state.value.status)
        assertFalse(status.degraded)
        assertIs<NzbdavTestResult.Saved>(vm.state.value.lastTestResult)
        assertFalse(vm.state.value.isSaving)
        // Local mirror was written via the repository impl we don't see
        // here (the test stub doesn't route through UsenetRepositoryImpl),
        // but the repo.saveNzbdavIntegration was called once.
        assertEquals(1, repo.saveCalls)
    }

    // ── remove ──────────────────────────────────────────────────────────

    @Test
    fun removeClearsConfigAndLocalMirror() = runTest(dispatcher) {
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(configured = true, isEnabled = true),
        )
        val secrets = FakeSecretStore().apply {
            store[IntegrationSecretKey.NZBDAV_BASE_URL] = "https://nzbdav.example.com"
            store[IntegrationSecretKey.NZBDAV_API_KEY] = "abc"
        }
        val vm = NzbdavSetupViewModel(repo, secrets)
        advanceUntilIdle()
        assertIs<NzbdavStatus.Connected>(vm.state.value.status)
        vm.remove()
        advanceUntilIdle()
        assertIs<NzbdavStatus.NotConfigured>(vm.state.value.status)
        assertIs<NzbdavTestResult.Removed>(vm.state.value.lastTestResult)
        assertEquals("", vm.state.value.baseUrl)
        assertEquals("", vm.state.value.apiKey)
        assertEquals(1, repo.deleteCalls)
        // The delete call itself is expected to clear the mirror via
        // UsenetRepositoryImpl. This VM test uses a fake repo that skips
        // that plumbing, so we only assert the repo method was invoked
        // — the VM-layer mirror-clear is proven indirectly by the
        // emptied baseUrl/apiKey form fields.
    }

    // ── token-leak guard across all flows ───────────────────────────────

    @Test
    fun noRawReasonLeaksAcrossAnyFlow() = runTest(dispatcher) {
        val leaks = listOf(
            "upstream_below_version_floor:v0.9",
            "Bearer xyzzy",
            "<html>stack trace</html>",
            "connection refused: 127.0.0.1:6789",
        )
        for (leak in leaks) {
            val repo = FakeUsenetRepository(
                statusResponse = NzbdavStatusResponseDto(
                    configured = true, isEnabled = true, degraded = true, reason = leak,
                ),
                testResponse = NzbdavTestResponseDto(ok = true, degraded = true, reason = leak),
                savedStatus = NzbdavStatusResponseDto(
                    configured = true, isEnabled = true, degraded = true, reason = leak,
                ),
            )
            val vm = NzbdavSetupViewModel(repo, FakeSecretStore())
            advanceUntilIdle()
            vm.updateBaseUrl("https://u"); vm.updateApiKey("k")
            vm.test(); advanceUntilIdle()
            vm.save(); advanceUntilIdle()
            val rendered = vm.state.value.toString()
            assertFalse(rendered.contains(leak), "Reason '$leak' leaked into UI state: $rendered")
        }
    }

    // ── prefill from local mirror ───────────────────────────────────────

    @Test
    fun prefillsFromLocalMirrorOnInit() = runTest(dispatcher) {
        val secrets = FakeSecretStore().apply {
            store[IntegrationSecretKey.NZBDAV_BASE_URL] = "https://nzbdav.example.com"
            store[IntegrationSecretKey.NZBDAV_API_KEY] = "prefilled-key"
        }
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(configured = true, isEnabled = true),
        )
        val vm = NzbdavSetupViewModel(repo, secrets)
        advanceUntilIdle()
        assertEquals("https://nzbdav.example.com", vm.state.value.baseUrl)
        assertEquals("prefilled-key", vm.state.value.apiKey)
    }

    @Test
    fun notConfiguredBackendReflectsEvenWithStaleMirror() = runTest(dispatcher) {
        val secrets = FakeSecretStore().apply {
            store[IntegrationSecretKey.NZBDAV_BASE_URL] = "https://nzbdav.example.com"
            store[IntegrationSecretKey.NZBDAV_API_KEY] = "stale"
        }
        val repo = FakeUsenetRepository(
            statusResponse = NzbdavStatusResponseDto(configured = false),
        )
        val vm = NzbdavSetupViewModel(repo, secrets)
        advanceUntilIdle()
        // Status MUST reflect backend (Not configured) even if local mirror has data.
        assertIs<NzbdavStatus.NotConfigured>(vm.state.value.status)
        // Mirror still visible in form fields — lets the user edit rather than retype.
        assertEquals("https://nzbdav.example.com", vm.state.value.baseUrl)
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private class FakeUsenetRepository(
        private val statusResponse: NzbdavStatusResponseDto =
            NzbdavStatusResponseDto(configured = false),
        private val testResponse: NzbdavTestResponseDto? = null,
        private val savedStatus: NzbdavStatusResponseDto? = null,
        private val statusThrow: Boolean = false,
        private val testException: Exception? = null,
    ) : UsenetRepository {
        var saveCalls = 0; private set
        var deleteCalls = 0; private set

        override suspend fun testNzbdavIntegration(baseUrl: String, apiKey: String): NzbdavTestResponseDto {
            testException?.let { throw it }
            return testResponse ?: NzbdavTestResponseDto(ok = true)
        }

        override suspend fun saveNzbdavIntegration(
            baseUrl: String, apiKey: String, enabled: Boolean, storageMode: IntegrationStorageMode,
        ): NzbdavStatusResponseDto {
            saveCalls += 1
            return savedStatus ?: statusResponse
        }

        override suspend fun getNzbdavStatus(): NzbdavStatusResponseDto {
            if (statusThrow) throw RuntimeException("network down")
            return statusResponse
        }

        override suspend fun deleteNzbdavIntegration() {
            deleteCalls += 1
        }

        override suspend fun warmUsenetCandidates(
            contentId: String, candidates: List<UsenetCandidateDto>, topN: Int?,
        ): UsenetWarmResponseDto = UsenetWarmResponseDto()

        override suspend fun resolveUsenetCandidate(
            contentId: String, candidate: UsenetCandidateDto,
        ): UsenetResolveResponseDto = UsenetResolveResponseDto(state = "warming")

        override suspend fun resolveBarNzb(nzbUrl: String, title: String): UsenetResolveResponseDto =
            UsenetResolveResponseDto(state = "failed", failureCode = "unmocked")

        override suspend fun getUsenetJobStatus(jobId: String): UsenetJobStatusResponseDto =
            UsenetJobStatusResponseDto(jobId = jobId, contentId = "x", state = "warming")

        override suspend fun cancelUsenetWarmJobs(
            contentId: String?, candidateId: String?, userSession: String?,
        ): UsenetCancelResponseDto = UsenetCancelResponseDto()

        override suspend fun getUsenetHandoff(token: String): ResolvedStreamDto =
            ResolvedStreamDto(url = "https://x")
    }

    private class FakeSecretStore : IntegrationSecretStore {
        val store = mutableMapOf<IntegrationSecretKey, String>()
        private val modes = mutableMapOf<IntegrationSecretKey, IntegrationStorageMode>()

        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
            store[key] = value
        }

        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = store[key]

        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
            store.remove(key)
        }

        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) {
            modes[key] = mode
        }

        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
            modes[key] ?: IntegrationStorageMode.DEVICE_ONLY

        override suspend fun clearAllSecrets() {
            store.clear(); modes.clear()
        }
    }
}
