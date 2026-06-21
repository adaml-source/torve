package com.torve.data.providerhealth

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrefsBackedProviderHealthRepositoryTest {

    private class FakePrefs : PreferencesRepository {
        val store = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = store[key]
        override suspend fun setString(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
    }

    @Test
    fun `upsert then load round-trips entries`() = runTest {
        val prefs = FakePrefs()
        val repo = PrefsBackedProviderHealthRepository(prefs)
        val entry = ProviderHealthEntry(
            category = ProviderHealthCategory.DEBRID,
            providerKey = "debrid:real_debrid",
            label = "Real-Debrid",
            status = ProviderHealthStatus.GREEN,
            lastCheckedAt = 1_700_000_000_000L,
            message = "Connected.",
        )
        repo.upsert(entry)
        assertEquals(listOf(entry), repo.entries.value)

        // Fresh repo reading the same prefs should rehydrate.
        val rehydrated = PrefsBackedProviderHealthRepository(prefs)
        rehydrated.load()
        assertEquals(listOf(entry), rehydrated.entries.value)
    }

    @Test
    fun `upsert merges by category and providerKey`() = runTest {
        val repo = PrefsBackedProviderHealthRepository(FakePrefs())
        val first = ProviderHealthEntry(
            ProviderHealthCategory.DEBRID, "debrid:real_debrid",
            "Real-Debrid", ProviderHealthStatus.GREEN,
        )
        val second = first.copy(status = ProviderHealthStatus.RED, message = "401")
        repo.upsert(first)
        repo.upsert(second)
        assertEquals(1, repo.entries.value.size)
        assertEquals(ProviderHealthStatus.RED, repo.entries.value.single().status)
    }

    @Test
    fun `upsert does not collide across categories`() = runTest {
        val repo = PrefsBackedProviderHealthRepository(FakePrefs())
        val a = ProviderHealthEntry(
            ProviderHealthCategory.DEBRID, "x",
            "A", ProviderHealthStatus.GREEN,
        )
        val b = ProviderHealthEntry(
            ProviderHealthCategory.IPTV, "x",
            "B", ProviderHealthStatus.YELLOW,
        )
        repo.upsert(a); repo.upsert(b)
        assertEquals(2, repo.entries.value.size)
    }

    @Test
    fun `remove deletes only matching entry`() = runTest {
        val repo = PrefsBackedProviderHealthRepository(FakePrefs())
        repo.upsert(
            ProviderHealthEntry(
                ProviderHealthCategory.DEBRID, "a", "A", ProviderHealthStatus.GREEN,
            ),
        )
        repo.upsert(
            ProviderHealthEntry(
                ProviderHealthCategory.DEBRID, "b", "B", ProviderHealthStatus.GREEN,
            ),
        )
        repo.remove(ProviderHealthCategory.DEBRID, "a")
        assertEquals(listOf("b"), repo.entries.value.map { it.providerKey })
    }

    @Test
    fun `clear empties the cache`() = runTest {
        val prefs = FakePrefs()
        val repo = PrefsBackedProviderHealthRepository(prefs)
        repo.upsert(
            ProviderHealthEntry(
                ProviderHealthCategory.DEBRID, "x", "X", ProviderHealthStatus.GREEN,
            ),
        )
        repo.clear()
        assertTrue(repo.entries.value.isEmpty())
        assertTrue(prefs.store.isEmpty() || prefs.store[PrefsBackedProviderHealthRepository.KEY] == null)
    }

    @Test
    fun `load tolerates corrupt json`() = runTest {
        val prefs = FakePrefs().apply { store[PrefsBackedProviderHealthRepository.KEY] = "not json" }
        val repo = PrefsBackedProviderHealthRepository(prefs)
        repo.load()
        assertTrue(repo.entries.value.isEmpty())
    }
}
