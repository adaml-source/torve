package com.torve.presentation.setup

import com.torve.data.providerhealth.PrefsBackedProviderHealthRepository
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.providerhealth.ProviderHealthChecker
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupIntentsViewModelTest {

    private class FakePrefs : PreferencesRepository {
        val store = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = store[key]
        override suspend fun setString(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
    }

    private class FakeChecker(
        override val providerKey: String,
        private val entry: ProviderHealthEntry,
    ) : ProviderHealthChecker {
        override suspend fun check(): ProviderHealthEntry = entry
    }

    @Test
    fun `summarize returns UNCONFIGURED when no rows match an intent`() {
        val out = SetupIntentsViewModel.summarize(SetupIntent.IPTV, emptyList())
        assertEquals(ProviderHealthStatus.UNCONFIGURED, out.status)
        assertEquals(SetupIntent.IPTV.tagline, out.primaryMessage)
        assertTrue(out.entries.isEmpty())
    }

    @Test
    fun `summarize picks worst status across rows for the intent`() {
        val rows = listOf(
            ProviderHealthEntry(
                ProviderHealthCategory.DEBRID, "debrid:real_debrid",
                "Real-Debrid", ProviderHealthStatus.GREEN,
                message = "Connected.",
            ),
            ProviderHealthEntry(
                ProviderHealthCategory.DEBRID, "debrid:torbox",
                "TorBox", ProviderHealthStatus.RED,
                message = "401 unauthorized",
            ),
        )
        val out = SetupIntentsViewModel.summarize(SetupIntent.DEBRID, rows)
        assertEquals(ProviderHealthStatus.RED, out.status)
        assertEquals("401 unauthorized", out.primaryMessage)
        assertEquals(2, out.entries.size)
    }

    @Test
    fun `runCheck emits the checker result through the coordinator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = PrefsBackedProviderHealthRepository(FakePrefs())
        val checker = FakeChecker(
            providerKey = "debrid:real_debrid",
            entry = ProviderHealthEntry(
                ProviderHealthCategory.DEBRID, "debrid:real_debrid",
                "Real-Debrid", ProviderHealthStatus.GREEN, message = "ok",
            ),
        )
        val coord = ProviderHealthCoordinator(
            repository = repo,
            initialCheckers = listOf(checker),
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            nowMs = { 42L },
        )
        coord.runCheck("debrid:real_debrid")?.join()
        val rows = repo.entries.first { it.isNotEmpty() }
        assertEquals(1, rows.size)
        assertEquals(ProviderHealthStatus.GREEN, rows.single().status)
        assertEquals(42L, rows.single().lastCheckedAt)
    }

    @Test
    fun `runCheck wraps thrown exceptions as RED with actionable message`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = PrefsBackedProviderHealthRepository(FakePrefs())
        // Seed an existing row so the coordinator knows the category.
        repo.upsert(
            ProviderHealthEntry(
                ProviderHealthCategory.DEBRID, "debrid:flaky",
                "Flaky", ProviderHealthStatus.UNKNOWN,
            ),
        )
        val flaky = object : ProviderHealthChecker {
            override val providerKey = "debrid:flaky"
            override suspend fun check(): ProviderHealthEntry =
                throw RuntimeException("boom")
        }
        val coord = ProviderHealthCoordinator(
            repository = repo,
            initialCheckers = listOf(flaky),
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            nowMs = { 99L },
        )
        coord.runCheck("debrid:flaky")?.join()
        val row = repo.entries.value.single()
        assertEquals(ProviderHealthStatus.RED, row.status)
        assertEquals(99L, row.lastCheckedAt)
        assertTrue(row.message?.contains("boom") == true, "expected 'boom' in message: ${row.message}")
    }
}
