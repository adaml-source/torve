package com.torve.data.profile

import com.torve.data.PersonalDeviceFixture
import com.torve.data.account.AccountSettingsApi
import com.torve.data.trakt.*
import com.torve.data.trakt.api.*
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.integrations.*
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class CopiedTraktSessionTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun client(engine: MockEngine) = HttpClient(engine) { install(ContentNegotiation) { json(json) } }

    @Test fun settingsRefreshPropagatesToAdamAndHealthDoesNotRejectCopiedToken() = runTest {
        var refreshes = 0
        val http = client(MockEngine { request ->
            when {
                request.url.encodedPath == "/oauth/token" -> {
                    refreshes++
                    respond("""{"access_token":"fresh","refresh_token":"rotated","expires_in":3600,"created_at":100}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
                }
                request.headers[HttpHeaders.Authorization] == "Bearer fresh" ->
                    respond("""{"username":"adam"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
                else -> respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType,"application/json"))
            }
        })
        PersonalDeviceFixture(http).use { d ->
            d.signIn(); d.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT,"adam","Adam",0,0,null,null,2)
            val store = TraktTokenStore(d.secrets,json)
            store.write(TraktTokens("expired","old-refresh",3600,1))
            val family=FamilyControls(d.preferences,d.secure,d.ids,SettingsRefreshNotifier(),lookup={emptyList()},scope=backgroundScope)
            family.initialize()
            ProfileConnectionCopier(d.database,d.ids,d.secrets,family,d.auth,AccountSettingsApi(http,{"https://torve.test"}))
                .copy("default","adam",listOf("TRAKT_TOKENS"),false,false)
            val shared=SharedTraktConnection(d.ids,d.auth,AccountSettingsApi(http,{"https://torve.test"}),d.secrets)
            val api=TraktAuthorizedApi(TraktClient(http,json),store,personalScopeProvider={d.ids.currentDataUserId()},
                accountTokens=shared::latest,publishTokens=shared::publish)
            assertEquals("adam",api.getUser().username)
            d.select("adam")
            assertEquals("rotated",store.read()?.refreshToken)
            assertEquals(ConnectionProbeResult.CONNECTED,api.probeAccount())
            assertEquals(1,refreshes)
        }
    }

    @Test fun accountCopyUsesLatestCanonicalTokenAndPromotesSourceForFutureRotations() = runTest {
        val http=client(MockEngine { request ->
            val body=when {
                request.url.encodedPath=="/me/family" -> """{"configuration":{"enabled":false,"accountId":"household-test","version":0}}"""
                request.url.encodedPath.endsWith("/connections/default") -> """{"services":["TRAKT_TOKENS"]}"""
                request.url.encodedPath.endsWith("/credentials") -> """{"credentials":{"accessToken":"canonical-new","refreshToken":"canonical-refresh","expiresIn":"3600","createdAt":"100"}}"""
                else -> "{}"
            }
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        PersonalDeviceFixture(http).use { d ->
            d.signIn();d.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT,"adam","Adam",0,0,null,null,2)
            val store=TraktTokenStore(d.secrets,json);store.write(TraktTokens("stale-local","stale-refresh",0,0))
            val family=FamilyControls(d.preferences,d.secure,d.ids,SettingsRefreshNotifier(),lookup={emptyList()},
                remote=FamilyPolicyApi(http,d.auth,{"https://torve.test"}),scope=backgroundScope)
            withContext(Dispatchers.Default) {
                family.initialize()
                assertTrue(family.canManage(),family.state.value.toString())
                ProfileConnectionCopier(d.database,d.ids,d.secrets,family,d.auth,AccountSettingsApi(http,{"https://torve.test"}))
                    .copy("default","adam",listOf("TRAKT_TOKENS"),false,true)
            }
            assertEquals(IntegrationStorageMode.ACCOUNT,d.secrets.getStorageMode(IntegrationSecretKey.TRAKT_TOKENS))
            assertEquals("canonical-refresh",store.read()?.refreshToken)
            d.select("adam")
            assertEquals(IntegrationStorageMode.ACCOUNT,d.secrets.getStorageMode(IntegrationSecretKey.TRAKT_TOKENS))
            assertEquals("canonical-new",store.read()?.accessToken)
        }
    }

    @Test fun missingLocalCopyLoadsAccountConnectionWithoutReauthorizing() = runTest {
        val http=client(MockEngine { respond("""{"username":"restored"}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json")) })
        PersonalDeviceFixture(http).use { d ->
            d.signIn();val store=TraktTokenStore(d.secrets,json)
            val api=TraktAuthorizedApi(TraktClient(http,json),store,accountTokens={TraktTokens("account-access","account-refresh",1,1)})
            assertEquals("restored",api.getUser().username)
            assertEquals("account-refresh",store.read()?.refreshToken)
        }
    }

    @Test fun transientRefreshFailureIsNotReportedAsRejectedCredentials() = runTest {
        val http=client(MockEngine { request ->
            if(request.url.encodedPath=="/oauth/token") throw TraktNetworkException()
            respond("{}",HttpStatusCode.Unauthorized,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        PersonalDeviceFixture(http).use { d ->
            d.signIn();val store=TraktTokenStore(d.secrets,json);store.write(TraktTokens("expired","refresh",1,1))
            val api=TraktAuthorizedApi(TraktClient(http,json),store)
            assertFailsWith<TraktNetworkException> { api.getUser() }
            assertEquals(ConnectionProbeResult.UNREACHABLE,api.probeAccount())
            assertNotNull(store.read())
        }
    }

    @Test fun unauthorizedResponseAfterProfileSwitchCannotRefreshOrOverwriteNewProfile() = runTest {
        val requested=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var refreshes=0
        val http=client(MockEngine { request ->
            if(request.url.encodedPath=="/oauth/token") refreshes++
            requested.complete(Unit);release.await()
            respond("{}",HttpStatusCode.Unauthorized,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        PersonalDeviceFixture(http).use { d ->
            d.signIn();d.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT,"adam","Adam",0,0,null,null,2)
            val store=TraktTokenStore(d.secrets,json);store.write(TraktTokens("default-access","default-refresh",1,1))
            val api=TraktAuthorizedApi(TraktClient(http,json),store,personalScopeProvider={d.ids.currentDataUserId()})
            val checking=async { api.getUser() };requested.await()
            d.select("adam");store.write(TraktTokens("adam-access","adam-refresh",1,1));release.complete(Unit)
            assertFailsWith<CancellationException> { checking.await() }
            assertEquals("adam-refresh",store.read()?.refreshToken);assertEquals(0,refreshes)
        }
    }

    @Test fun omdbLegacyActivationCopiesSecurelyAndWorksInTheNewProfile() = runTest {
        val http=client(MockEngine { request ->
            assertEquals("activated-omdb-key",request.url.parameters["apikey"])
            respond("""{"Response":"True","imdbRating":"8.4","imdbVotes":"100","Metascore":"80"}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        PersonalDeviceFixture(http).use { d ->
            d.signIn();d.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT,"adam","Adam",0,0,null,null,2)
            d.preferences.setString("omdb_api_key","activated-omdb-key")
            d.preferences.setString("rating_display_prefs","{\"enabledProviders\":[\"IMDB\",\"ROTTEN_TOMATOES\"]}")
            val family=FamilyControls(d.preferences,d.secure,d.ids,SettingsRefreshNotifier(),lookup={emptyList()},scope=backgroundScope);family.initialize()
            val copier=ProfileConnectionCopier(d.database,d.ids,d.secrets,family,d.auth,AccountSettingsApi(http,{"https://torve.test"}))
            assertTrue("OMDB_API_KEY" in copier.choices("default"))
            assertNull(d.preferences.getString("omdb_api_key"))
            copier.copy("default","adam",listOf("OMDB_API_KEY"),false,false)
            d.select("adam")
            assertEquals("activated-omdb-key",d.secrets.get(IntegrationSecretKey.OMDB_API_KEY))
            assertNotNull(d.preferences.getString("rating_display_prefs"))
            val omdb=com.torve.data.ratings.OmdbClient(http,d.preferences,d.secrets)
            assertTrue(omdb.isConfigured());assertNotNull(omdb.fetchRatings("tt0111161"))
        }
    }

    @Test fun customTraktClientIdentityTravelsWithDeviceConnection() = runTest {
        val http=client(MockEngine { respond("{}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json")) })
        PersonalDeviceFixture(http).use { d ->
            d.signIn();d.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT,"adam","Adam",0,0,null,null,2)
            TraktTokenStore(d.secrets,json).write(TraktTokens("access","refresh",1,1))
            d.preferences.setString("trakt_client_id","source-client");d.secrets.put(IntegrationSecretKey.TRAKT_CLIENT_SECRET,"source-client-secret")
            val family=FamilyControls(d.preferences,d.secure,d.ids,SettingsRefreshNotifier(),lookup={emptyList()},scope=backgroundScope);family.initialize()
            ProfileConnectionCopier(d.database,d.ids,d.secrets,family,d.auth,AccountSettingsApi(http,{"https://torve.test"})).copy("default","adam",listOf("TRAKT_TOKENS"),false,false)
            d.select("adam");assertEquals("source-client",d.preferences.getString("trakt_client_id"))
            assertEquals("source-client-secret",d.secrets.get(IntegrationSecretKey.TRAKT_CLIENT_SECRET))
        }
    }

    @Test fun cancelledRefreshCannotBecomeCredentialRejection() = runTest {
        assertFailsWith<CancellationException> {
            executeWithTokenRefresh(TraktTokens("expired","refresh",1,1),{error("401 Unauthorized")},
                {throw CancellationException("Profile changed")},{true})
        }
    }
}
