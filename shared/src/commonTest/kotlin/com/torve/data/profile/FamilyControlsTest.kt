package com.torve.data.profile

import com.torve.domain.model.*
import com.torve.presentation.home.applyFamilyBoundary
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class FamilyControlsTest {
    private class MemorySettings : DeviceLocalSettingsRepository {
        val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private class MemorySecrets : SecureStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override suspend fun putString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private class Fixture(val scope: CoroutineScope) {
        val settings = MemorySettings(); val secrets = MemorySecrets()
        var clock = 1000L; var profile = "default"; var account = "parent-account"
        var evidence: List<AgeCertification> = listOf(AgeCertification("DE", "6"))
        var lookup: suspend (MediaItem) -> List<AgeCertification> = { evidence }
        var verifiedAccount: String? = null
        fun controller() = FamilyControls(settings, secrets, null, SettingsRefreshNotifier(), lookup = { lookup(it) },
            verifyAccount = { _, _ -> verifiedAccount }, scope = scope, now = { clock }, accountId = { account }, profileId = { profile })
        val family = controller()
        suspend fun kid() {
            family.initialize(); family.enable("2468", "2468")
            family.saveProfile("kids", true, KidsProfileRules(maximumAge = 12, ratingCountry = "DE", allowedSources = setOf("catalogue", "downloads", "extension:approved")))
            family.authorizeProfileSwitch("kids"); profile = "kids"; family.updateSession()
        }
        val item get() = MediaItem("10", tmdbId = 10, imdbId = "tt0010", type = MediaType.MOVIE, title = "Example", adult = false)
    }
    @Test fun externalLibraryRatingDoesNotAuthorizeTheSameCatalogueIdentity() = runTest {
        val f = Fixture(backgroundScope); f.kid()
        f.family.authorize("2468")
        f.family.saveProfile("kids", true, f.family.state.value.configuration.kids.getValue("kids").copy(allowedSources = setOf("catalogue", "jellyfin:child-library")))
        f.evidence = listOf(AgeCertification("DE", "18"))
        val childCopy = f.item.copy(ageCertifications = listOf(AgeCertification("DE", "6")))
        assertTrue(f.family.rememberExternal("jellyfin", "child-copy", childCopy, "jellyfin:child-library"))
        f.family.registerExternalPlayback("file://child-copy", "jellyfin", "child-copy")
        assertTrue(f.family.playbackAllowed("file://child-copy"))
        f.family.certify(f.item)
        assertFalse(f.family.allows(f.item, "catalogue"))
        assertTrue(f.family.externalAllowed("jellyfin", "child-copy"))
        assertTrue(f.family.playbackAllowed("file://child-copy"))
    }
    @Test fun adultHomeSnapshotsAreFilteredBeforeTheFirstChildFrame() = runTest {
        val f = Fixture(backgroundScope); f.kid(); f.family.certify(f.item)
        val adult = f.item.copy(id = "11", tmdbId = 11, imdbId = "tt0011", title = "Adult", adult = true)
        val rows = listOf(f.item, adult)
        val raw = com.torve.presentation.home.HomeUiState(shelves = listOf(CatalogShelf("old", "Old", rows)), heroItem = adult,
            continueWatching = listOf(WatchProgress("11", MediaType.MOVIE, "Adult")), watchlistItems = rows,
            customShelves = mapOf("old" to rows), addonShelves = listOf(CatalogShelf("source", "Source", listOf(adult))))
        val visible = raw.applyFamilyBoundary(com.torve.presentation.contentpolicy.ContentPolicyFilter(f.family))
        assertNull(visible.heroItem); assertTrue(visible.continueWatching.isEmpty()); assertTrue(visible.addonShelves.isEmpty())
        assertEquals(listOf(f.item), visible.watchlistItems); assertEquals(listOf(f.item), visible.shelves.single().items)
        assertEquals(listOf(f.item), visible.customShelves.getValue("old"))
    }
    @Test fun blockedCatalogueTitlesStayBlockedThroughImdbSourcesAndOfflineRestarts() = runTest {
        val f = Fixture(backgroundScope); f.kid()
        f.family.authorize("2468")
        f.family.saveProfile("kids", true, f.family.state.value.configuration.kids.getValue("kids").copy(blockedTitles = setOf("MOVIE:10")))
        f.lookup = { item -> f.family.rememberCanonicalIdentity(item, 10); f.evidence }
        val imdb = f.item.copy(id = "tt0010", tmdbId = null, familySourceKey = "extension:approved")
        assertTrue(f.family.prepare(listOf(imdb), "extension:approved").isEmpty())
        assertFalse(f.family.allows(imdb))
        val offline = f.controller(); offline.initialize()
        assertFalse(offline.allows(imdb))
        assertEquals("MOVIE:10", f.family.canonicalBlockKey(imdb))
    }
    @Test fun offByDefaultAndNoSettingsAreEnabledWithoutSetup() = runTest {
        val f = Fixture(backgroundScope); f.family.initialize()
        assertFalse(f.family.state.value.configuration.enabled)
        assertTrue(f.family.allows(f.item.copy(adult = true)))
        assertTrue(f.secrets.values.isEmpty())
        assertFailsWith<IllegalArgumentException> { f.family.enable("1234", "5678") }
        assertFalse(f.family.state.value.configuration.enabled)
    }
    @Test fun adultProfilesRelockOnRestartWhileKidsRemainRestrictedOffline() = runTest {
        val f = Fixture(backgroundScope); f.kid(); f.family.certify(f.item)
        val fresh = f.controller(); fresh.initialize()
        assertFalse(fresh.state.value.needsUnlock)
        assertTrue(fresh.allows(f.item))
        assertFalse(fresh.canManage())
        f.profile = "default"; fresh.updateSession()
        assertTrue(fresh.state.value.needsUnlock)
        assertFalse(fresh.allows(f.item))
        fresh.authorize("2468"); fresh.authorizeProfileSwitch("default"); fresh.updateSession()
        assertFalse(fresh.state.value.needsUnlock)
        fresh.background(); f.clock += 60_001; fresh.foreground()
        assertTrue(fresh.state.value.needsUnlock)
    }
    @Test fun unknownAndForgedCertificatesNeverAppearBeforeTrustedLookup() = runTest {
        val f = Fixture(backgroundScope); f.kid(); f.evidence = listOf(AgeCertification("DE", "18"))
        val forged = f.item.copy(ageCertifications = listOf(AgeCertification("DE", "0")))
        assertFalse(f.family.allows(forged))
        val verified = f.family.certify(forged)
        assertFalse(f.family.allows(verified))
        assertEquals("18", verified.ageCertifications.single().rating)
    }
    @Test fun childCannotChangeRulesDisableControlsOrEnterAdultProfile() = runTest {
        val f = Fixture(backgroundScope); f.kid()
        assertFailsWith<IllegalStateException> { f.family.requireParent() }
        assertFailsWith<IllegalStateException> { f.family.authorizeProfileSwitch("default") }
        assertFailsWith<IllegalStateException> { f.family.disable() }
        assertFailsWith<IllegalStateException> { f.family.saveProfile("kids", false, KidsProfileRules()) }
        assertTrue(f.family.state.value.configuration.enabled)
    }
    @Test fun pinLockoutSurvivesRestartAndDoesNotExposeTheSecretInPolicy() = runTest {
        val f = Fixture(backgroundScope); f.kid()
        repeat(5) { assertFailsWith<IllegalStateException> { f.family.authorize("0000") } }
        val fresh = f.controller(); fresh.initialize()
        assertFailsWith<IllegalStateException> { fresh.authorize("2468") }
        assertFalse(f.settings.values.values.any { "2468" in it })
        f.clock += 60_001; fresh.authorize("2468")
        assertTrue(fresh.canManage())
    }
    @Test fun unapprovedSourcesAndStalePlaybackGrantsAreRejected() = runTest {
        val f = Fixture(backgroundScope); f.kid(); val item = f.family.certify(f.item)
        assertTrue(f.family.allows(item))
        assertFalse(f.family.allows(item, "extension:blocked"))
        f.family.registerPlayback("https://fixture.invalid/allowed.mp4", "extension:approved", item)
        assertTrue(f.family.playbackAllowed("https://fixture.invalid/allowed.mp4"))
        assertFalse(f.family.playbackAllowed("https://fixture.invalid/direct-link.mp4"))
        f.family.authorize("2468")
        f.family.saveProfile("kids", true, KidsProfileRules(maximumAge = 0, ratingCountry = "DE", allowedSources = setOf("catalogue", "extension:approved")))
        assertFalse(f.family.playbackAllowed("https://fixture.invalid/allowed.mp4"))
    }
    @Test fun inFlightMetadataCannotCommitAfterSwitchingProfiles() = runTest {
        val f = Fixture(backgroundScope); f.kid()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.lookup = { started.complete(Unit); release.await(); listOf(AgeCertification("DE", "6")) }
        val request = async { f.family.prepare(listOf(f.item)) }
        started.await(); f.profile = "another-child"; f.family.updateSession(); release.complete(Unit)
        assertTrue(request.await().isEmpty())
    }
    @Test fun parentRecoveryRequiresTheOriginalAuthenticatedAccount() = runTest {
        val f = Fixture(backgroundScope); f.kid(); f.verifiedAccount = "different-account"
        assertFailsWith<IllegalStateException> { f.family.recoverPin("parent@example.invalid", "password", "1357", "1357") }
        assertFailsWith<IllegalStateException> { f.family.authorize("1357") }
        f.verifiedAccount = f.account
        f.family.recoverPin("parent@example.invalid", "password", "1357", "1357")
        f.family.lock(); f.family.authorize("1357")
        assertTrue(f.family.canManage())
    }
    @Test fun retainedStateClearsSynchronouslyOnProfileAndRuleChanges() = runTest {
        val f = Fixture(backgroundScope); f.family.initialize()
        val state = ProfilePolicyStateFlow(f.family, emptyList<String>())
        state.value = listOf("Adult private history")
        f.profile = "kids"
        assertTrue(state.value.isEmpty())
        state.value = listOf("Child history")
        f.family.updateSession()
        assertEquals(listOf("Child history"), state.value)
        f.profile = "default"
        assertTrue(state.value.isEmpty())
        f.family.updateSession(); f.family.enable("2468", "2468")
        state.value = listOf("Current adult content")
        val before = f.family.contentSessionKey()
        f.family.saveProfile("kids", true, KidsProfileRules())
        assertEquals(before, f.family.contentSessionKey())
        assertEquals(listOf("Current adult content"), state.value)
    }
}
