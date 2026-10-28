package com.torve.presentation.apple

import com.torve.data.PersonalDeviceFixture
import com.torve.data.mdblist.*
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppleMdbListJourneyTest {
    @Test fun connectDiscoverSaveDisableAndRemoveListsUsingProfileScopedStorage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = HttpClient(MockEngine { request ->
            val list = """{"id":12,"name":"Curated universe","items":24}"""
            val body = when (request.url.encodedPath) {
                "/lists/12" -> list
                "/lists/search" -> {
                    assertEquals("universe", request.url.parameters["query"])
                    "[$list]"
                }
                "/lists/user", "/lists/top" -> "[$list]"
                else -> error("Unexpected service request")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            PersonalDeviceFixture(client).use { device ->
                device.signIn()
                device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "other", "Other", 1, 0, null, null, 2)
                val notifier = SettingsRefreshNotifier()
                val api = MdbListApi(client)
                val repo = MdbListRepository(api, device.preferences, notifier)
                val vm = MdbListViewModel(api, repo, device.preferences, device.secrets, device.ids, notifier)
                try {
                    advanceUntilIdle()
                    vm.saveApiKey("fixture-key")
                    advanceUntilIdle()
                    vm.state.first { it.error != null || it.topLists.isNotEmpty() }
                    assertNull(vm.state.value.error)
                    assertEquals(1, vm.state.value.ownLists.size)
                    assertEquals(1, vm.state.value.topLists.size)
                    assertNull(device.preferences.getString("mdblist_api_key"))
                    assertEquals("fixture-key", device.secrets.get(IntegrationSecretKey.MDBLIST_API_KEY))
                    vm.setSearchQuery("universe"); vm.search()
                    advanceUntilIdle()
                    vm.state.first { it.error != null || it.searchResults.isNotEmpty() }
                    assertEquals(12, vm.state.value.searchResults.single().id)
                    vm.addList(12, "Universe")
                    advanceUntilIdle()
                    vm.state.first { it.error != null || it.savedLists.isNotEmpty() }
                    assertTrue(vm.state.value.savedLists.single().enabled)
                    vm.toggleList(12, false)
                    advanceUntilIdle()
                    vm.state.first { !it.isSaving }
                    assertFalse(repo.getSavedLists().single().enabled)
                    device.select("other"); vm.reload()
                    advanceUntilIdle()
                    assertTrue(vm.state.value.savedLists.isEmpty())
                    assertEquals("", vm.state.value.apiKey)
                    device.select("default"); vm.reload()
                    advanceUntilIdle()
                    vm.state.first { it.error != null || it.savedLists.isNotEmpty() }
                    vm.removeList(12)
                    advanceUntilIdle()
                    vm.state.first { !it.isSaving }
                    assertTrue(repo.getSavedLists().isEmpty())
                    assertNull(vm.state.value.error)

                    // A TV remote action may be queued just as a household profile changes.
                    vm.addList(12, "Universe")
                    device.select("other")
                    advanceUntilIdle()
                    assertTrue(repo.getSavedLists().isEmpty())
                    device.select("default")
                    assertTrue(repo.getSavedLists().isEmpty())
                    vm.saveApiKey("replacement-key")
                    device.select("other")
                    advanceUntilIdle()
                    assertNull(device.secrets.get(IntegrationSecretKey.MDBLIST_API_KEY))
                    device.select("default")
                    assertEquals("fixture-key", device.secrets.get(IntegrationSecretKey.MDBLIST_API_KEY))
                } finally { vm.close() }
            }
        } finally { Dispatchers.resetMain() }
    }
}
