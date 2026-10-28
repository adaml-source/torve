package com.torve.data.trakt

import com.torve.data.PersonalDeviceFixture
import com.torve.data.auth.profileDataUserId
import com.torve.data.metadata.*
import com.torve.data.profile.FamilyControls
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.domain.model.*
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SearchListsRepositoryTest {
    @Test fun savedAndPinnedListsAreProfileScopedAndPinsBecomeRealHomeSections() = runTest {
        var network = 0
        PersonalDeviceFixture(HttpClient(MockEngine { network++; respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })).use { device ->
            device.signIn()
            val family = FamilyControls(device.preferences, device.secure, device.ids, SettingsRefreshNotifier(), lookup = { emptyList() }, scope = backgroundScope)
            family.initialize()
            val client = TraktClient(device.http, Json { ignoreUnknownKeys = true })
            val api = TraktAuthorizedApi(client, TraktTokenStore(device.secrets, Json))
            val policy = object : ContentPolicyRepository {
                override val state = MutableStateFlow(ContentPolicyState.unrestricted())
                override suspend fun refresh() {}
                override suspend fun submitDob(dateOfBirth: String) {}
                override suspend fun enableSensitive(policyVersion: String) {}
                override suspend fun disableSensitive() {}
            }
            val repo = SearchListsRepository(client, api, device.preferences, MetadataRepositoryImpl(TmdbApiClient(device.http)),
                device.ids, family, policy, ContentPolicyFilter(family), SettingsRefreshNotifier())
            val other = CustomSection("existing", "Keep me")
            device.preferences.setString("custom_sections", Json.encodeToString(listOf(other)))
            val list = TraktBrowseList(name = "Mysteries", ids = TraktIds(trakt = 123), itemCount = 12)
            repo.save(list, true); repo.pin(list, true, own = false)
            assertEquals(listOf(list), repo.saved()); assertEquals(listOf(list), repo.pinned())
            val sections = Json.decodeFromString<List<CustomSection>>(device.preferences.getString("custom_sections")!!)
            assertEquals(other, sections.first()); assertEquals(123, sections.last().filters.traktListId)
            repo.pin(list, false, own = false)
            assertEquals(listOf(other), Json.decodeFromString<List<CustomSection>>(device.preferences.getString("custom_sections")!!))
            assertEquals(listOf(list), repo.saved())
            device.database.torveQueries.insertProfile(device.ids.currentUserId(), "kids", "Kids", 0, 0, null, null, 2)
            device.select("kids")
            assertTrue(repo.saved().isEmpty()); assertTrue(repo.pinned().isEmpty())
            device.select("default"); family.enable("2468", "2468")
            family.saveProfile("kids", true, KidsProfileRules(allowedSources = setOf("catalogue")))
            family.authorizeProfileSwitch("kids"); device.select("kids"); family.updateSession()
            val before = network
            assertFailsWith<IllegalStateException> { repo.browse("explore", "", 1) }
            assertEquals(before, network)
        }
    }
}
