package com.torve.data.profile

import com.torve.data.PersonalDeviceFixture
import com.torve.data.account.AccountSettingsApi
import com.torve.domain.integrations.*
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProfileConnectionCopierTest {
    private fun client() = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json")) }) { install(ContentNegotiation) { json() } }
    @Test fun selectedDeviceConnectionsCopyWithoutHistoryOrCredentialsReentry() = runTest {
        PersonalDeviceFixture(client()).use { d ->
            d.signIn();val q=d.database.torveQueries
            q.insertProfile(PersonalDeviceFixture.ACCOUNT,"child","Child",0,0,null,null,2)
            d.secrets.put(IntegrationSecretKey.DEBRID_API_KEY_TORBOX,"torbox-private-test-key")
            d.secrets.put(IntegrationSecretKey.JELLYFIN_API_KEY,"not-selected")
            val family=FamilyControls(d.preferences,d.secure,d.ids,SettingsRefreshNotifier(),lookup={ emptyList() },scope=backgroundScope)
            family.initialize()
            val copier=ProfileConnectionCopier(d.database,d.ids,d.secrets,family,d.auth,AccountSettingsApi(d.http,{"https://torve.test"}))
            assertTrue("DEBRID_API_KEY_TORBOX" in copier.choices("default"))
            copier.copy("default","child",listOf("DEBRID_API_KEY_TORBOX"),false,false)
            assertEquals("torbox-private-test-key",d.secrets.get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX))
            d.select("child")
            assertEquals("torbox-private-test-key",d.secrets.get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX))
            assertNull(d.secrets.get(IntegrationSecretKey.JELLYFIN_API_KEY))
            assertEquals(IntegrationStorageMode.DEVICE_ONLY,d.secrets.getStorageMode(IntegrationSecretKey.DEBRID_API_KEY_TORBOX))
            assertEquals("TORBOX",d.preferences.getString("debrid_provider"))
            assertTrue(q.getAllHistory(d.ids.currentDataUserId()).executeAsList().isEmpty())
        }
    }
    @Test fun destinationOverwriteNeedsExplicitChoice() = runTest {
        PersonalDeviceFixture(client()).use { d ->
            d.signIn();d.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT,"other","Other",0,0,null,null,2)
            d.secrets.put(IntegrationSecretKey.DEBRID_API_KEY_TORBOX,"source")
            d.select("other");d.secrets.put(IntegrationSecretKey.DEBRID_API_KEY_TORBOX,"keep-me");d.select("default")
            val family=FamilyControls(d.preferences,d.secure,d.ids,SettingsRefreshNotifier(),lookup={emptyList()},scope=backgroundScope);family.initialize()
            val copier=ProfileConnectionCopier(d.database,d.ids,d.secrets,family,d.auth,AccountSettingsApi(d.http,{"https://torve.test"}))
            assertFailsWith<IllegalStateException> { copier.copy("default","other",listOf("DEBRID_API_KEY_TORBOX"),false,false) }
            d.select("other");assertEquals("keep-me",d.secrets.get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX))
        }
    }
    @Test fun failedAccountTokenPublicationSurvivesAndRetriesFromSecureStorage() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            if (requests == 1) respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("{\"credentials\":{\"accessToken\":\"new-access\",\"refreshToken\":\"new-refresh\",\"expiresIn\":\"3600\",\"createdAt\":\"1\"}}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        PersonalDeviceFixture(http).use { d ->
            d.signIn()
            d.secrets.setStorageMode(IntegrationSecretKey.TRAKT_TOKENS, IntegrationStorageMode.ACCOUNT)
            d.secrets.put(IntegrationSecretKey.TRAKT_TOKENS, "{\"accessToken\":\"old-access\",\"refreshToken\":\"old-refresh\"}")
            val service = SharedTraktConnection(d.ids, d.auth, AccountSettingsApi(d.http, { "https://torve.test" }), d.secrets)
            val next = com.torve.data.trakt.TraktTokens("new-access", "new-refresh", 3600, 1)
            assertEquals(next, service.publish("old-refresh", next))
            assertNotNull(d.secrets.get(IntegrationSecretKey.TRAKT_TOKENS, "pending_rotation"))
            assertTrue(d.secrets.get(IntegrationSecretKey.TRAKT_TOKENS)!!.contains("new-refresh"))
            assertEquals(next, service.latest())
            assertNull(d.secrets.get(IntegrationSecretKey.TRAKT_TOKENS, "pending_rotation"))
            assertEquals(2, requests)
        }
    }
    @Test fun rotatingCopiedTraktCredentialsUpdatesOnlyProfilesUsingThatConnection() = runTest {
        PersonalDeviceFixture(client()).use { d ->
            d.signIn();val q=d.database.torveQueries
            q.insertProfile(PersonalDeviceFixture.ACCOUNT,"shared","Shared",0,0,null,null,2)
            q.insertProfile(PersonalDeviceFixture.ACCOUNT,"separate","Separate",0,0,null,null,3)
            val initial="{\"accessToken\":\"access-1\",\"refreshToken\":\"refresh-1\"}"
            d.secrets.put(IntegrationSecretKey.TRAKT_TOKENS,initial)
            d.select("shared");d.secrets.put(IntegrationSecretKey.TRAKT_TOKENS,initial)
            d.select("separate");d.secrets.put(IntegrationSecretKey.TRAKT_TOKENS,"{\"refreshToken\":\"different\"}")
            d.select("default")
            val replacement="{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\"}"
            d.secrets.propagateTraktRotation("refresh-1",replacement)
            assertEquals(replacement,d.secrets.get(IntegrationSecretKey.TRAKT_TOKENS))
            d.select("shared");assertEquals(replacement,d.secrets.get(IntegrationSecretKey.TRAKT_TOKENS))
            d.select("separate");assertEquals("{\"refreshToken\":\"different\"}",d.secrets.get(IntegrationSecretKey.TRAKT_TOKENS))
        }
    }
}
