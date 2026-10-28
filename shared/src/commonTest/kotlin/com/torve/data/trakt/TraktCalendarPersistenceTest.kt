package com.torve.data.trakt

import com.torve.data.trakt.api.*
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.integrations.*
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class TraktCalendarPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = CalendarPreferences()
    private val secrets = CalendarSecrets()
    private var person = "account:profile1"
    private var now = 1_000_000L
    private var calls = 0
    private var fail = false
    private val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(this@TraktCalendarPersistenceTest.now) }
    private val connection = PersistedTraktAuthScopeProvider(secrets) { "connection-$now" }
    private val tokens = TraktTokenStore(secrets, json)
    private suspend fun api(gate: CompletableDeferred<Unit>? = null, requested: CompletableDeferred<Unit>? = null): TraktAuthorizedApi {
        if (tokens.read() == null) tokens.write(TraktTokens("test-access", "test-refresh", 3600, 0))
        val http = HttpClient(MockEngine {
            calls++
            requested?.complete(Unit); gate?.await()
            respond(if (fail) "{}" else """[{"first_aired":"2026-10-18T20:00:00Z","episode":{"season":1,"number":2,"title":"Episode"},"show":{"title":"Saved show","ids":{"tmdb":12}}}]""",
                if (fail) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) { install(ContentNegotiation) { json(json) } }
        val client = TraktClient(httpClient = http, json = json, authScopeProvider = connection,
            diagnostics = TraktDiagnosticsLogger { _, _ -> })
        return TraktAuthorizedApi(client, tokens, clock = clock, authScopeProvider = connection,
            personalScopeProvider = { person }, calendarPreferences = prefs,
            diagnostics = TraktDiagnosticsLogger { _, _ -> })
    }

    @Test fun processRestartReadsFreshCalendarWithoutNetwork() = runTest {
        api().getCalendarCached(33)
        assertEquals(1, calls)
        val reopened = api()
        assertEquals("Saved show", assertNotNull(reopened.cachedCalendar(33)).episodes.single().showTitle)
        assertFalse(reopened.getCalendarCached(33).isStale)
        assertEquals(1, calls)
        assertTrue(prefs.values.values.none { "test-access" in it || "test-refresh" in it })
    }

    @Test fun staleCalendarAppearsBeforeSlowRefreshAndSurvivesOfflineFailure() = runTest {
        api().getCalendarCached(33)
        now += 16 * 60_000
        val gate = CompletableDeferred<Unit>(); val requested = CompletableDeferred<Unit>()
        val reopened = api(gate, requested)
        assertTrue(assertNotNull(reopened.cachedCalendar(33)).isStale)
        val refresh = async { reopened.getCalendarCached(33) }
        requested.await()
        assertEquals("Saved show", assertNotNull(reopened.cachedCalendar(33)).episodes.single().showTitle)
        fail = true; gate.complete(Unit)
        assertTrue(refresh.await().isStale)
    }

    @Test fun profileOrConnectionChangeNeverRestoresAnotherCalendar() = runTest {
        api().getCalendarCached(33)
        person = "account:profile2"; assertNull(api().cachedCalendar(33))
        person = "account:profile1"; assertNotNull(api().cachedCalendar(33))
        now++; connection.resetForAccountChange()
        assertNull(api().cachedCalendar(33))
    }

    @Test fun missingConnectionDoesNotExposeSavedCalendar() = runTest {
        val reopened = api()
        reopened.getCalendarCached(33)
        tokens.clear()
        assertNull(reopened.cachedCalendar(33))
        assertFailsWith<TraktAuthorizationRequiredException> { reopened.getCalendarCached(33) }
    }

    @Test fun changedProfileDuringRequestCannotPublishOrSaveItsResponse() = runTest {
        val gate = CompletableDeferred<Unit>(); val requested = CompletableDeferred<Unit>()
        val value = api(gate, requested)
        val refresh = async { value.getCalendarCached(33) }
        requested.await(); person = "other-account"; gate.complete(Unit)
        assertFailsWith<CancellationException> { refresh.await() }
        assertTrue(prefs.values.isEmpty())
        assertNull(value.cachedCalendar(33))
    }

    @Test fun expiredOrCorruptCalendarRequiresRefresh() = runTest {
        api().getCalendarCached(33)
        now += TraktCalendarSnapshotStore.MAX_AGE_MS + 1
        assertNull(api().cachedCalendar(33))
        prefs.values.keys.toList().forEach { prefs.values[it] = "broken" }
        assertNull(api().cachedCalendar(33))
        assertEquals(1, calls)
    }
}

private class CalendarPreferences : PreferencesRepository {
    val values = mutableMapOf<String, String>()
    override suspend fun getString(key: String) = values[key]
    override suspend fun setString(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}
private class CalendarSecrets : IntegrationSecretStore {
    private val values = mutableMapOf<Pair<IntegrationSecretKey, String?>, String>()
    override suspend fun get(key: IntegrationSecretKey, subKey: String?) = values[key to subKey]
    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) { values[key to subKey] = value }
    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) { values.remove(key to subKey) }
    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
    override suspend fun getStorageMode(key: IntegrationSecretKey) = IntegrationStorageMode.DEVICE_ONLY
    override suspend fun clearAllSecrets() { values.clear() }
}
