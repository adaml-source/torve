package com.torve.data.profile

import com.torve.data.PersonalDeviceFixture
import com.torve.domain.model.*
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FamilyCataloguePropagationTest {
    @Test fun remoteSourceApprovalAndRevocationReachTheChildsActualPreferenceNamespace() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) { install(ContentNegotiation) { json() } }
        PersonalDeviceFixture(http).use { d ->
            d.signIn(); d.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "child", "Child", 0, 0, null, null, 2)
            d.preferences.setString("metadata_tmdb_catalogue_enabled", "false")
            var config = FamilyConfiguration(true, PersonalDeviceFixture.ACCOUNT, mapOf("child" to KidsProfileRules(allowedSources = setOf("catalogue"))), 1)
            val remote = object : FamilyPolicyRemote {
                override suspend fun fetch() = FamilyPolicySnapshot(config, 1)
                override suspend fun update(configuration: FamilyConfiguration, expectedVersion: Long, pin: String?, grant: String?): FamilyParentProof = error("Not used")
                override suspend fun authorize(pin: String): FamilyParentProof = error("Not used")
                override suspend fun requestRecovery(): FamilyRecoveryDestination = error("Not used")
                override suspend fun confirmRecovery(code: String, pin: String): FamilyParentProof = error("Not used")
                override suspend fun connections(profile: String, grant: String) = emptyList<String>()
                override suspend fun copyConnections(source: String, target: String, services: List<String>, replace: Boolean, grant: String) {}
            }
            val familyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
            val family = FamilyControls(d.preferences, d.secure, d.ids, SettingsRefreshNotifier(), lookup = { emptyList() }, remote = remote, scope = familyScope)
            withContext(Dispatchers.Default) { family.initialize() }
            assertEquals("false", d.preferences.getString("metadata_tmdb_catalogue_enabled"))
            d.select("child"); family.updateSession()
            assertTrue(family.state.value.isKids, "access=${family.state.value}; active=${d.ids.currentProfileId()}")
            assertEquals("true", d.preferences.getString("metadata_tmdb_catalogue_enabled"))
            config = config.copy(kids = mapOf("child" to KidsProfileRules()), version = 2)
            withContext(Dispatchers.Default) { family.refreshRemote() }
            assertEquals("false", d.preferences.getString("metadata_tmdb_catalogue_enabled"))
            assertFalse(family.allowsSource("catalogue"))
            } finally { familyScope.cancel() }
        }
    }
}
