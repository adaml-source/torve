package com.torve.data.profile

import com.torve.data.PersonalDeviceFixture
import com.torve.data.account.AccountSettingsApi
import com.torve.data.auth.profileDataUserId
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class HouseholdSyncServiceTest {
    @Test fun localSignOutWaitsForInFlightSyncWithoutDeletingCloudProfilesOrWebsites() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { first -> PersonalDeviceFixture(cloud.client()).use { second ->
            first.signIn(); second.signIn()
            val a = sync(first); val b = sync(second)
            val account = PersonalDeviceFixture.ACCOUNT
            first.database.torveQueries.insertProfile(account, "other", "Other", 1, 0, null, null, 2)
            val config = Json.encodeToString(com.torve.data.replays.ReplayConfiguration(true,
                listOf(com.torve.data.replays.ReplayWebsite("one", "League", "https://sports.example/"))))
            first.preferences.setString("web_replays_v1_settings", config)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            try {
                assertTrue(a.refresh(true))
                val saved = cloud.settings.toMap()
                cloud.beforeFetch = { started.complete(Unit); release.await() }
                val syncing = async { a.refresh(true) }
                started.await()
                var cleared = false
                val resetting = async(start = CoroutineStart.UNDISPATCHED) {
                    a.resetLocalAccountData {
                        first.auth.logout()
                        first.database.transaction {
                            first.database.torveQueries.deleteAllAccountPreferences()
                            first.database.torveQueries.deleteAllProfilesForAllUsers()
                        }
                        cleared = true
                    }
                }
                assertFalse(cleared)
                release.complete(Unit)
                assertTrue(syncing.await()); resetting.await()
                assertTrue(cleared); assertFalse(a.refresh(true))
                assertEquals(saved, cloud.settings)
                cloud.beforeFetch = null
                assertTrue(b.refresh(true))
                assertEquals(setOf("default", "other"), second.database.torveQueries.getAllProfiles(account).executeAsList().map { it.id }.toSet())
                assertEquals(config, second.preferences.getString("web_replays_v1_settings"))
            } finally { release.complete(Unit); a.clear(); b.clear() }
        } }
    }

    @Test fun replayWebsitesSyncAcrossDevicesWithoutSendingPrivateCachesOrBookmarks() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { first -> PersonalDeviceFixture(cloud.client()).use { second ->
            first.signIn(); second.signIn(); val a = sync(first); val b = sync(second)
            val config = com.torve.data.replays.ReplayConfiguration(true, listOf(com.torve.data.replays.ReplayWebsite("one", "League", "https://sports.example/")))
            val encoded = Json.encodeToString(config)
            try {
                first.preferences.setString("web_replays_v1_settings", encoded)
                first.preferences.setString("web_replays_v1_cache", "large-private-cache".repeat(200_000))
                first.preferences.setString("web_replays_v1_progress", "private-bookmark")
                assertTrue(a.refresh(true)); assertTrue(b.refresh(true))
                assertEquals(encoded, second.preferences.getString("web_replays_v1_settings"))
                assertNull(second.preferences.getString("web_replays_v1_cache")); assertNull(second.preferences.getString("web_replays_v1_progress"))
                assertTrue(cloud.settings.keys.any { it.endsWith(":navigation:web_replays_v1_settings") })
                assertTrue(cloud.settings.values.none { "large-private-cache" in it || "private-bookmark" in it })
                first.preferences.setString("web_replays_v1_settings", Json.encodeToString(config.copy(websites = emptyList())))
                assertTrue(a.refresh(true)); assertTrue(b.refresh(true))
                assertTrue(Json.decodeFromString<com.torve.data.replays.ReplayConfiguration>(second.preferences.getString("web_replays_v1_settings")!!).websites.isEmpty())
            } finally { a.clear(); b.clear() }
        } }
    }
    @Test fun copyingReplaySitesMergesDestinationAndSyncsItWithoutCopyingHistory() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { first -> PersonalDeviceFixture(cloud.client()).use { second ->
            first.signIn(); second.signIn(); val a = sync(first); val b = sync(second)
            val account = PersonalDeviceFixture.ACCOUNT; val person = profileDataUserId(account, "other")
            first.database.torveQueries.insertProfile(account, "other", "Other", 1, 0, null, null, 2)
            val source = com.torve.data.replays.ReplayConfiguration(true, listOf(com.torve.data.replays.ReplayWebsite("one", "League", "https://sports.example/")))
            val destination = source.copy(enabled = false, websites = listOf(com.torve.data.replays.ReplayWebsite("two", "Racing", "https://racing.example/")))
            first.database.torveQueries.setPreference(person, "web_replays_v1_settings", Json.encodeToString(destination))
            first.database.torveQueries.setPreference(person, "web_replays_v1_progress", "destination-bookmark")
            try {
                a.copyReplayWebsites("other", source); assertTrue(a.refresh(true)); assertTrue(b.refresh(true))
                val copied = Json.decodeFromString<com.torve.data.replays.ReplayConfiguration>(second.database.torveQueries.getPreference(person, "web_replays_v1_settings").executeAsOne())
                assertEquals(setOf("one", "two"), copied.websites.map { it.id }.toSet()); assertTrue(copied.enabled)
                assertEquals("destination-bookmark", first.database.torveQueries.getPreference(person, "web_replays_v1_progress").executeAsOne())
                assertEquals("default", first.ids.currentProfileId())
                assertFails { a.copyReplayWebsites("not-on-this-account", source) }
            } finally { a.clear(); b.clear() }
        } }
    }
    @Test fun restoreKeepsCurrentPinPolicyAndLanguageSurvivesAccountRefresh() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { device ->
            device.signIn(); val account = PersonalDeviceFixture.ACCOUNT
            val q = device.database.torveQueries
            q.insertProfile(account, "kids", "Kids", 1, 0, "1234", "PG", 2)
            q.setPreference(account, "app_language", "GERMAN")
            val service = sync(device)
            try {
                val backup = service.exportLibraryBackup(account)
                assertEquals("test-torve-session", device.auth.getValidAccessTokenForUser(account))
                assertNull(device.auth.getValidAccessTokenForUser("other-account"))
                q.insertProfile(account, "kids", "Kids", 1, 0, null, "PG", 2)
                cloud.settings["language"] = "en"
                service.restoreLibraryBackup(backup, account)
                assertNull(q.getProfile(account, "kids").executeAsOne().pin)
                assertEquals("de", cloud.settings["language"])
                val settings = com.torve.data.account.AccountSettingsRepositoryImpl(device.auth,
                    AccountSettingsApi(device.http, { "https://torve.test" }), device.preferences, SettingsRefreshNotifier())
                assertNull(settings.refreshIfStale(true).error)
                assertEquals("GERMAN", device.preferences.getString("app_language"))
            } finally { service.clear() }
        }
    }

    @Test fun missingProtectedProfileMustRecoverItsAccountPinBeforeAnyRestoreMutation() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { device ->
            device.signIn(); val account = PersonalDeviceFixture.ACCOUNT; val q = device.database.torveQueries
            q.insertProfile(account, "kids", "Kids", 1, 0, "1234", "PG", 2)
            q.setPreference(account, "theme_mode", "DARK")
            val service = sync(device)
            try {
                val backup = service.exportLibraryBackup(account)
                q.deleteProfile(account, "kids")
                assertFailsWith<IllegalStateException> { service.restoreLibraryBackup(backup, account) }
                assertTrue(cloud.patches.isEmpty())
                assertNull(q.getProfile(account, "kids").executeAsOneOrNull())
                cloud.pins["HOUSEHOLD_PIN:kids"] = "4567"
                service.restoreLibraryBackup(backup, account)
                assertEquals("4567", q.getProfile(account, "kids").executeAsOne().pin)
            } finally { service.clear() }
        }
    }

    @Test fun accountSwitchDuringTokenAcquisitionStopsRestoreBeforePatch() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { device ->
            device.signIn(); val account = PersonalDeviceFixture.ACCOUNT
            device.database.torveQueries.setPreference(account, "theme_mode", "DARK")
            val service = sync(device)
            try {
                val backup = service.exportLibraryBackup(account)
                val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                device.secure.beforeWrite = { key ->
                    if (key == "auth_access_token") { device.secure.beforeWrite = null; started.complete(Unit); release.await() }
                }
                val signIn = async { device.auth.login("other@example.test", "test-password") }
                started.await()
                val restore = async(start = CoroutineStart.UNDISPATCHED) { runCatching { service.restoreLibraryBackup(backup, account) } }
                // Wait until restore is queued behind the in-progress sign-in's auth mutex.
                while (!restore.isActive) kotlinx.coroutines.yield()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(100) }
                release.complete(Unit)
                assertTrue(signIn.await().success)
                assertNull(device.auth.getValidAccessTokenForUser(account))
                assertEquals("other-account-token", device.auth.getValidAccessTokenForUser("other-account"))
                // The token read must observe the new identity before it can be used.
                assertTrue(restore.await().isFailure)
                assertTrue(cloud.patches.isEmpty())
            } finally { device.secure.beforeWrite = null; service.clear() }
        }
    }

    @Test fun backupRestoresProfilesAndLibraryWithoutSourcesSecretsOrPinChanges() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { device ->
            device.signIn()
            val account = PersonalDeviceFixture.ACCOUNT
            device.database.torveQueries.insertProfile(account, "kids", "Kids", 1, 0, "1234", "PG", 2)
            episode(device, account, 1)
            episode(device, profileDataUserId(account, "kids"), 2)
            device.database.torveQueries.setPreference(account, "theme_mode", "DARK")
            device.database.torveQueries.setPreference(account, "panda_indexer_subkeys", "https://private.test/SECRET_CANARY")
            val service = sync(device)
            val backup = service.exportLibraryBackup(account)
            assertFalse("1234" in backup); assertFalse("SECRET_CANARY" in backup)
            device.database.torveQueries.clearAllHistory(account)
            episode(device, account, 3)
            service.restoreLibraryBackup(backup, account)
            assertEquals(listOf(1L), device.database.torveQueries.getAllHistory(account).executeAsList().map { it.episode_number })
            assertEquals("1234", device.database.torveQueries.getProfile(account, "kids").executeAsOne().pin)
            assertEquals(1, device.database.torveQueries.getAllHistory(profileDataUserId(account, "kids")).executeAsList().size)
            assertEquals("DARK", cloud.settings["theme_mode"])
            service.refresh(true)
            assertEquals(listOf(1L), device.database.torveQueries.getAllHistory(account).executeAsList().map { it.episode_number })
            assertFailsWith<IllegalStateException> { service.restoreLibraryBackup(backup, "another-account") }
            assertFailsWith<IllegalArgumentException> { service.restoreLibraryBackup(backup.replace("\"name\":\"Default\"", "\"name\":\"Default\",\"token\":\"evil\""), account) }
            service.clear()
        }
    }
    @Test fun savedListsAndHomePinsSyncWithOfflineRemovalAndKeepProfilesSeparate() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { first -> PersonalDeviceFixture(cloud.client()).use { second ->
            first.signIn(); second.signIn()
            val a = sync(first); val b = sync(second)
            val account = PersonalDeviceFixture.ACCOUNT
            try {
                first.database.torveQueries.setPreference(account, "search_saved_lists_v1", "[{saved:1}]")
                first.database.torveQueries.setPreference(account, "custom_sections", "[{pin:1}]")
                assertTrue(a.refresh(true)); assertTrue(b.refresh(true))
                assertEquals("[{saved:1}]", second.preferences.getString("search_saved_lists_v1"))
                assertEquals("[{pin:1}]", second.preferences.getString("custom_sections"))
                first.database.torveQueries.insertProfile(account, "child", "Child", 0, 0, null, null, 3)
                assertTrue(a.refresh(true)); assertTrue(b.refresh(true))
                second.select("child");assertNull(second.preferences.getString("search_saved_lists_v1"))
                second.select("default")
                cloud.rejectPatch = true
                second.preferences.remove("search_saved_lists_v1")
                assertFalse(b.refresh(true));assertTrue(b.state.value.pendingChanges > 0)
                cloud.rejectPatch = false
                assertTrue(b.refresh(true));assertTrue(a.refresh(true))
                assertNull(first.preferences.getString("search_saved_lists_v1"))
                assertEquals("[{pin:1}]", first.preferences.getString("custom_sections"))
            } finally { a.clear();b.clear() }
        } }
    }
    private class Cloud {
        val settings = linkedMapOf<String, String>()
        val pins = mutableMapOf<String, String>()
        val patches = mutableListOf<Map<String, String>>()
        var rejectPatch = false
        var beforeFetch: (suspend () -> Unit)? = null
        var fetches = 0
        fun client() = HttpClient(MockEngine { request ->
            fun body() = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            val path = request.url.encodedPath
            var status = HttpStatusCode.OK
            val response = if (path == "/auth/login") {
                buildJsonObject {
                    put("user", buildJsonObject { put("id", "other-account"); put("email", "other@example.test"); put("is_verified", true) })
                    put("tokens", buildJsonObject { put("access_token", "other-account-token"); put("refresh_token", "other-refresh-token") })
                }
            } else if (path == "/me/account-settings") {
                if (request.method == HttpMethod.Get) { fetches++; beforeFetch?.invoke() }
                if (request.method == HttpMethod.Patch) {
                    if (rejectPatch) status = HttpStatusCode.ServiceUnavailable
                    else {
                        val patch = body().getValue("settings").jsonObject.mapValues { it.value.jsonPrimitive.content }
                        settings.putAll(patch); patches += patch
                    }
                }
                buildJsonObject { put("settings", buildJsonObject { settings.forEach { (k,v) -> put(k,v) } }) }
            } else {
                val type = path.substringAfter("/me/integrations/").substringBefore("/credentials")
                if (request.method == HttpMethod.Put) pins[type] = body().getValue("credentials").jsonObject.getValue("pin").jsonPrimitive.content
                if (request.method == HttpMethod.Delete) pins.remove(type)
                if (path.endsWith("/credentials")) {
                    if (type !in pins) status = HttpStatusCode.NotFound
                    buildJsonObject { put("credentials", buildJsonObject { pins[type]?.let { put("pin", it) } }) }
                } else buildJsonObject { put("ok", true) }
            }
            respond(response.toString(), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) } }
    }
    private fun sync(device: PersonalDeviceFixture) = HouseholdSyncService(device.database, device.auth,
        AccountSettingsApi(device.http, { "https://torve.test" }), device.ids, SettingsRefreshNotifier(), automatic = false)
    private fun episode(device: PersonalDeviceFixture, person: String, ep: Int) {
        device.database.torveQueries.insertHistory(person, "gentlemen-s2e$ep", "236235", "series", "Episode $ep", null, null,
            1_791_324_000_000L + ep, 2_000, 2, ep.toLong(), "The Gentlemen")
    }

    @Test fun anInFlightProfileSyncCannotPublishSuccessToTheNextProfilesStatus() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "kids", "Kids", 1, 0, null, "PG", 2)
            val service = sync(device)
            try {
                assertTrue(service.refresh(true))
                assertNotNull(service.state.value.lastSyncedAt)
                cloud.beforeFetch = { device.select("kids") }
                assertTrue(service.refresh(true))
                assertEquals(device.ids.currentDataUserId(), service.state.value.personalDataUserId)
                assertNull(service.state.value.lastSyncedAt)
                assertFalse(service.state.value.syncing)
                cloud.beforeFetch = null
                assertTrue(service.refresh(true))
                assertNotNull(service.state.value.lastSyncedAt)
            } finally { service.clear() }
        }
    }

    @Test fun repeatedRefreshRequestsDoNotQueueAnotherTransferWhileOneIsPending() = runTest {
        val cloud = Cloud()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        cloud.beforeFetch = { started.complete(Unit); release.await() }
        PersonalDeviceFixture(cloud.client()).use { device ->
            device.signIn()
            val service = sync(device)
            try {
                service.requestRefresh(true)
                started.await()
                repeat(3) { service.requestRefresh(true) }
                assertEquals(1, cloud.fetches)
                release.complete(Unit)
                service.state.first { !it.syncing && it.lastSyncedAt != null }
                assertEquals(1, cloud.fetches)
            } finally { release.complete(Unit); service.clear() }
        }
    }

    @Test fun episodeCompletionAndUnwatchTombstonesReachAnotherIndependentDevice() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { first -> PersonalDeviceFixture(cloud.client()).use { second ->
            first.signIn(); second.signIn(); val a = sync(first); val b = sync(second)
            try {
                episode(first, PersonalDeviceFixture.ACCOUNT, 1); episode(first, PersonalDeviceFixture.ACCOUNT, 2)
                first.database.torveQueries.upsertProgress(PersonalDeviceFixture.ACCOUNT, "236235", "series", "The Gentlemen", null, null, 2_000, 2_000, 2, 2, "The Gentlemen", 1_791_324_000_002)
                assertTrue(a.refresh(true)); assertTrue(b.refresh(true))
                assertEquals(listOf(2L, 1L), second.database.torveQueries.getAllHistory(PersonalDeviceFixture.ACCOUNT).executeAsList().map { it.episode_number })
                assertEquals(2L, second.database.torveQueries.getAllProgress(PersonalDeviceFixture.ACCOUNT).executeAsList().single().episode_number)
                first.database.torveQueries.deleteHistory(PersonalDeviceFixture.ACCOUNT, "gentlemen-s2e2")
                first.database.torveQueries.deleteProgressByMediaId(PersonalDeviceFixture.ACCOUNT, "236235")
                assertTrue(a.refresh(true)); assertTrue(b.refresh(true)); assertTrue(a.refresh(true))
                assertEquals(listOf(1L), second.database.torveQueries.getAllHistory(PersonalDeviceFixture.ACCOUNT).executeAsList().map { it.episode_number })
                assertTrue(second.database.torveQueries.getAllProgress(PersonalDeviceFixture.ACCOUNT).executeAsList().isEmpty())
                assertTrue(cloud.settings.values.any { it.contains("\"deleted\":true") })
            } finally { a.clear(); b.clear() }
        } }
    }

    @Test fun profileDefinitionsSyncButTheActiveSelectionAndWatchStateStaySeparate() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { first -> PersonalDeviceFixture(cloud.client()).use { second ->
            first.signIn(); second.signIn(); val a = sync(first); val b = sync(second)
            try {
                first.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "kids", "Kids", 2, 0, null, "PG", 2)
                assertTrue(a.refresh(true)); assertTrue(b.refresh(true)); first.select("kids")
                episode(first, profileDataUserId(PersonalDeviceFixture.ACCOUNT, "kids"), 3)
                assertTrue(a.refresh(true)); assertTrue(b.refresh(true))
                assertEquals("default", second.ids.currentProfileId())
                assertTrue(second.database.torveQueries.getAllHistory(PersonalDeviceFixture.ACCOUNT).executeAsList().isEmpty())
                second.select("kids")
                assertEquals(3L, second.database.torveQueries.getAllHistory(second.ids.currentDataUserId()).executeAsList().single().episode_number)
                assertEquals("PG", second.database.torveQueries.getProfile(PersonalDeviceFixture.ACCOUNT, "kids").executeAsOne().max_content_rating)
            } finally { a.clear(); b.clear() }
        } }
    }

    @Test fun failedUploadsSurviveAServiceRestartAndAreRetriedWithoutLosingTheChange() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { device ->
            device.signIn(); episode(device, PersonalDeviceFixture.ACCOUNT, 3)
            cloud.rejectPatch = true
            val before = sync(device)
            assertFalse(before.refresh(true)); before.clear()
            assertTrue(HouseholdLedgerStore(device.database).read(PersonalDeviceFixture.ACCOUNT)!!.contains("gentlemen-s2e3"))
            cloud.rejectPatch = false
            val after = sync(device)
            try { assertTrue(after.refresh(true)); assertTrue(cloud.settings.keys.any { it.endsWith("gentlemen-s2e3") }) }
            finally { after.clear() }
        }
    }

    @Test fun pinChangesUseTheEncryptedVaultAndNeverEnterAccountSettings() = runTest {
        val cloud = Cloud()
        PersonalDeviceFixture(cloud.client()).use { device ->
            device.signIn(); val service = sync(device)
            try {
                assertTrue(service.refresh(true))
                device.database.torveQueries.updateProfilePin("4321", PersonalDeviceFixture.ACCOUNT, "default")
                service.queuePinUpdate("default"); assertTrue(service.refresh(true))
                assertEquals("4321", cloud.pins["HOUSEHOLD_PIN:default"])
                assertTrue(cloud.settings.values.none { it.contains("4321") })
                val profile = Json.decodeFromString<HouseholdRecord>(cloud.settings.getValue("household_v1:default:profile:default"))
                assertTrue(profile.data.getValue("protected").jsonPrimitive.boolean)
                val originalRevision = profile.changedAt
                device.database.torveQueries.updateProfilePin("5678", PersonalDeviceFixture.ACCOUNT, "default")
                service.queuePinUpdate("default"); assertTrue(service.refresh(true))
                assertEquals("5678", cloud.pins["HOUSEHOLD_PIN:default"])
                assertTrue(Json.decodeFromString<HouseholdRecord>(cloud.settings.getValue(profile.key)).changedAt > originalRevision)
            } finally { service.clear() }
        }
    }
}
