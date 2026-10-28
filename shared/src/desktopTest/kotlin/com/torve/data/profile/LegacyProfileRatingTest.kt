package com.torve.data.profile

import com.torve.data.PersonalDeviceFixture
import com.torve.domain.model.*
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.home.*
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LegacyProfileRatingTest {
    private fun device()=PersonalDeviceFixture(HttpClient(MockEngine { respond("{}") }))
    private fun item(id:String)=MediaItem(id,type=MediaType.MOVIE,title=id,adult=false,familySourceKey="catalogue")
    @Test fun legacyGProfileHidesPgRAndUnratedBeforeAndAfterCertification()=runTest {
        device().use { d ->
            d.signIn();d.database.torveQueries.updateProfileRating("G",PersonalDeviceFixture.ACCOUNT,"default")
            val family=FamilyControls(d.preferences,d.secure,d.ids,SettingsRefreshNotifier(),lookup={ it.id.takeUnless { id->id=="unrated" }?.let { id->listOf(AgeCertification("US",id)) }.orEmpty() },scope=backgroundScope,
                profileRatingLimit={ContentRating.fromString(d.database.torveQueries.getActiveProfile(PersonalDeviceFixture.ACCOUNT).executeAsOneOrNull()?.max_content_rating)})
            family.initialize();assertFalse(family.state.value.configuration.enabled)
            assertTrue(family.state.value.isKids);assertTrue(ContentPolicyFilter(family).familyEnabled)
            val items=listOf("G","TV-Y","PG","PG-13","R","unrated").map(::item)
            assertTrue(items.none { family.allows(it) })
            val visible=family.prepare(items)
            assertEquals(listOf("G","TV-Y"),visible.map { it.id })
            assertEquals(listOf("G","TV-Y"),ContentPolicyFilter(family).filterProfileLimit(items,ContentRating.G).map { it.id })
            assertFalse(family.playbackAllowed("https://fixture.test/unverified"))
            assertTrue(visible.all { it.ageCertifications.isNotEmpty() })
            assertTrue(family.canManage()) // Changing settings is PIN-protected only when Family controls are enabled.
        }
    }
    @Test fun cachedHomeArtworkCannotBypassLegacyRating()=runTest {
        device().use { d ->
            d.signIn();var rating:ContentRating?=null
            val family=FamilyControls(d.preferences,d.secure,d.ids,SettingsRefreshNotifier(),lookup={listOf(AgeCertification("US",it.id))},scope=backgroundScope,profileRatingLimit={rating})
            family.initialize();val g=item("G");val r=item("R");val flow=ProfilePolicyStateFlow(family,emptyList<MediaItem>())
            flow.value=listOf(g,r);assertEquals(2,flow.value.size)
            rating=ContentRating.G
            assertTrue(flow.value.isEmpty())
            family.updateSession();family.prepare(listOf(g,r))
            val raw=HomeUiState(heroItem=r,shelves=listOf(CatalogShelf("trending","Trending",listOf(g,r))),watchlistItems=listOf(g,r),
                addonShelves=listOf(CatalogShelf("addon","Addon",listOf(r))),customShelves=mapOf("hidden" to listOf(r)))
            val safe=raw.applyFamilyBoundary(ContentPolicyFilter(family))
            assertNull(safe.heroItem);assertEquals(listOf(g),safe.shelves.single().items)
            assertEquals(listOf(g),safe.watchlistItems);assertTrue(safe.addonShelves.isEmpty());assertTrue(safe.customShelves.isEmpty())
        }
    }
    @Test fun explicitGNeverRelaxesCloudSourcesOrBlockedTitles() {
        val rules=KidsProfileRules(maximumAge=12,ratingCountry="DE",hideUnrated=false,allowedSources=setOf("jellyfin:child"),blockedTitles=setOf("MOVIE:10"))
        val state=FamilyAccessState(configuration=FamilyConfiguration(enabled=true,kids=mapOf("child" to rules)),activeProfileId="child",profileContentRating=ContentRating.G)
        val effective=assertNotNull(state.effectiveKidsRules)
        assertEquals(0,effective.maximumAge);assertEquals("US",effective.ratingCountry);assertTrue(effective.hideUnrated)
        assertEquals(rules.allowedSources,effective.allowedSources);assertEquals(rules.blockedTitles,effective.blockedTitles)
    }
    @Test fun adultUnrestrictedSettingDoesNotBecomeAKidsProfileOrLockSettings() {
        val access=FamilyAccessState(profileContentRating=ContentRating.NC_17)
        assertFalse(access.isKids);assertFalse(access.contentRestricted)
        assertEquals(listOf(item("unknown")),ParentalFilter.filter(listOf(item("unknown")),ContentRating.NC_17))
    }
    @Test fun classicFilterUsesCertificationsInsteadOfGenresOrReviewScores() {
        val g=item("G").copy(rating=9.7,genreIds=listOf(80),ageCertifications=listOf(AgeCertification("US","G")))
        val r=item("R").copy(rating=3.0,genreIds=listOf(16),ageCertifications=listOf(AgeCertification("US","R")))
        assertEquals(listOf(g),ParentalFilter.filter(listOf(g,r,item("unknown")),ContentRating.G))
        assertEquals(listOf(g),ContentPolicyFilter().filterProfileLimit(listOf(g,r),ContentRating.G))
    }
    @Test fun gNeedsKnownUsCertificateAndDoesNotGuessFromReviewScores() {
        val rules=FamilyAccessState(profileContentRating=ContentRating.G).effectiveKidsRules!!
        assertFalse(FamilyPolicy.allows(item("unknown").copy(rating=10.0,ageCertifications=listOf(AgeCertification("US","Not Rated"))),rules))
        assertFalse(FamilyPolicy.allows(item("foreign").copy(ageCertifications=listOf(AgeCertification("DE","0"))),rules))
        assertFalse(FamilyPolicy.allows(item("mixed").copy(ageCertifications=listOf(AgeCertification("US","G"),AgeCertification("US","R"))),rules))
    }
}
