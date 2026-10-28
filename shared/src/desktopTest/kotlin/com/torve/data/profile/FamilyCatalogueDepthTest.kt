package com.torve.data.profile

import com.torve.data.PersonalDeviceFixture
import com.torve.data.metadata.*
import com.torve.data.mdblist.*
import com.torve.data.ratings.OmdbClient
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.*
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.home.withInitialDiscoveryShelves
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import com.torve.presentation.home.AppleHomeLayoutPolicy
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class FamilyCatalogueDepthTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }
    private val progress = object : WatchProgressRepository {
        override suspend fun getInProgress(limit: Long) = emptyList<WatchProgress>()
        override suspend fun getProgress(mediaId: String): WatchProgress? = null
        override suspend fun saveProgress(progress: WatchProgress) {}
        override suspend fun getAllProgress() = emptyList<WatchProgress>()
        override suspend fun deleteProgress(mediaId: String) {}
        override suspend fun clearAllProgress() {}
        override suspend fun syncFromTrakt() {}
    }
    private class Fixture(scope: CoroutineScope, delayedRestore: Boolean = false) : AutoCloseable {
        val requests = mutableListOf<Url>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher=Dispatchers.Main; addHandler { request ->
            requests += request.url; delay(2)
            val path=request.url.encodedPath
            val type=if(path.endsWith("/tv")) "tv" else "movie"
            val page=request.url.parameters["page"]?.toIntOrNull() ?: 1
            val id=path.split('/').dropLast(1).lastOrNull()?.toIntOrNull() ?: 0
            val rating=when { id%5==0 -> ""; (id/100)%10<=2 -> "R"; id%2==0 -> if(path.endsWith("content_ratings")) "TV-Y" else "G";else ->"R" }
            val data=when {
                path.endsWith("release_dates") -> """{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"$rating"}]}]}"""
                path.endsWith("content_ratings") -> """{"results":[{"iso_3166_1":"US","rating":"$rating"}]}"""
                else -> buildJsonObject {
                    put("page",page);put("total_pages",60);put("total_results",1200)
                    putJsonArray("results") { repeat(20) { offset -> add(buildJsonObject {
                        put("id",page*100+offset);put(if(type=="tv") "name" else "title","$type ${page*100+offset}")
                        put("media_type",type);put("adult",false);put("vote_count",1)
                    }) } }
                }.toString()
            }
            respond(data,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        } })) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        val device=PersonalDeviceFixture(http)
        val api=TmdbApiClient(http,apiKeyProvider={"fixture"},resolvedHostProvider={emptyList()},diagnostics={})
        private val remote=if(!delayedRestore) null else object : FamilyPolicyRemote {
            override suspend fun fetch():FamilyPolicySnapshot { delay(500);return FamilyPolicySnapshot(FamilyConfiguration(accountId="fixture")) }
            override suspend fun update(configuration:FamilyConfiguration,expectedVersion:Long,pin:String?,grant:String?):FamilyParentProof=error("unused")
            override suspend fun authorize(pin:String):FamilyParentProof=error("unused")
            override suspend fun requestRecovery():FamilyRecoveryDestination=error("unused")
            override suspend fun confirmRecovery(code:String,pin:String):FamilyParentProof=error("unused")
            override suspend fun connections(profile:String,grant:String)=emptyList<String>()
            override suspend fun copyConnections(source:String,target:String,services:List<String>,replace:Boolean,grant:String) {}
        }
        val family=FamilyControls(device.preferences,device.secure,null,SettingsRefreshNotifier(),lookup={api.getAgeCertifications(it)},scope=scope,
            remote=remote,accountId={"fixture"},profileRatingLimit={ContentRating.G})
        val filter=ContentPolicyFilter(family)
        val repository=MetadataRepositoryImpl(api,family)
        override fun close() { device.close() }
    }
    private suspend fun settle(vm:CatalogViewModel) {
        repeat(500) {
            dispatcher.scheduler.advanceTimeBy(20);dispatcher.scheduler.runCurrent()
            if(!vm.state.value.isLoading && vm.state.value.items.isNotEmpty()) return
            withContext(Dispatchers.Default) { delay(1) }
        }
        error("Catalogue did not publish a verified page")
    }
    @Test fun hundredsOfVerifiedTitlesRemainReachableAcrossEmptyPagesAndReopening()=runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            val all=linkedMapOf<String,MediaItem>();var page=1;var requests=0
            do {
                val result=f.repository.getPopularPaged("movie",page)
                assertTrue(result.page>=page)
                result.items.forEach { all[it.id]=it }
                page=result.page+1;requests++
                assertTrue(requests<65)
            } while(page<=60)
            assertTrue(all.size>=300,"Only ${all.size} eligible titles remained reachable")
            assertTrue(all.values.all { f.family.allows(it) && it.ageCertifications.contains(AgeCertification("US","G")) })
            assertTrue(all.values.none { it.id.toInt()%5==0 || it.id.toInt()%2!=0 })
            assertTrue(f.requests.filter { it.encodedPath.endsWith("discover/movie") }.all { it.parameters["vote_count.gte"]=="0" })
            val first=f.repository.getPopularPaged("movie",1)
            assertTrue(first.page>1);assertTrue(first.items.isNotEmpty())
            repeat(3) {
                val vm=CatalogViewModel(f.repository,"movie",contentPolicyFilter=f.filter)
                try { settle(vm);assertTrue(vm.state.value.items.isNotEmpty());assertTrue(vm.state.value.hasMore) }
                finally { vm.close() }
            }
        }
    }
    @Test fun catalogLoaderTracksReturnedPageCursorAndCanGrowPastThreeHundred()=runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            val vm=CatalogViewModel(f.repository,"movie",contentPolicyFilter=f.filter)
            try {
                settle(vm);var previous=vm.state.value.currentPage
                repeat(55) {
                    if(vm.state.value.items.size<300 && vm.state.value.hasMore) {
                        vm.loadMore()
                        var attempts=0
                        do {
                            dispatcher.scheduler.advanceTimeBy(20);dispatcher.scheduler.runCurrent()
                            withContext(Dispatchers.Default) { delay(1) };attempts++
                        } while((vm.state.value.isLoadingMore || vm.state.value.currentPage==previous) && attempts<500)
                        assertFalse(vm.state.value.isLoadingMore)
                        assertTrue(vm.state.value.currentPage>previous)
                        previous=vm.state.value.currentPage
                    }
                }
                assertTrue(vm.state.value.items.size>=300,"Count=${vm.state.value.items.size}; page=$previous")
                assertEquals(vm.state.value.items.size,vm.state.value.items.map { it.id }.toSet().size)
                assertTrue(vm.state.value.items.all { f.family.allows(it) })
            } finally { vm.close() }
        }
    }
    @Test fun aSavedGProfileWaitsForDelayedAccountPolicyBeforeCapturingItsOwner()=runTest(dispatcher) {
        Fixture(backgroundScope,delayedRestore=true).use { f ->
            val before=f.filter.profileOwnerKey()
            val vm=CatalogViewModel(f.repository,"movie",watchProgressRepo=progress,contentPolicyFilter=f.filter)
            try {
                settle(vm)
                assertTrue(f.family.state.value.loaded)
                assertNotEquals(before,f.filter.profileOwnerKey())
                assertTrue(vm.state.value.items.isNotEmpty())
                assertTrue(vm.state.value.items.all { f.family.allows(it) })
                repeat(1000) {
                    dispatcher.scheduler.advanceTimeBy(30);dispatcher.scheduler.runCurrent()
                    if(!vm.state.value.shelvesLoaded) withContext(Dispatchers.Default) { delay(1) }
                }
                assertTrue(vm.state.value.shelvesLoaded)
            } finally { vm.close() }
        }
    }
    @Test fun homeRestoresTrendingRowsEvenWhenOnlyThoseRowsWereSavedAsVisible()=runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            val shelves=f.repository.getHomeShelves()
            val rows=AppleHomeLayoutPolicy.rows(com.torve.presentation.home.HomeUiState(shelves=shelves),
                listOf(HomeSectionConfig(HomeSection.TRENDING_MOVIES,true,0),HomeSectionConfig(HomeSection.TRENDING_TV,true,1)),emptyList(),emptyList())
            assertEquals(2,rows.size);assertTrue(rows.all { it.items.isNotEmpty() })
            assertTrue(rows.flatMap { it.items }.all { f.family.allows(it) })
        }
    }
    @Test fun firstVerifiedHomeRowDoesNotWaitForOtherRowsOrFillTwenty()=runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            val first=f.repository.homeShelves().first()
            assertEquals(listOf("trending-movies"),first.map { it.id })
            assertEquals(8,first.single().items.size)
            assertTrue(first.single().items.all { f.family.allows(it) })
            assertTrue(f.requests.none { it.encodedPath.endsWith("discover/tv") || it.encodedPath.endsWith("upcoming") })
            val pages=f.requests.filter { it.encodedPath.endsWith("discover/movie") }
            assertEquals(listOf("1","2","3"),pages.map { it.parameters["page"] })
            val proofCount=f.requests.count { it.encodedPath.endsWith("release_dates") }
            val again=f.repository.homeShelves().first()
            assertEquals(first,again)
            assertEquals(proofCount,f.requests.count { it.encodedPath.endsWith("release_dates") })
        }
    }
    @Test fun everyAvailableKidsRailGrowsToTwentyWithoutDelayingFirstPaint()=runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            val snapshots=f.repository.homeShelves().toList()
            assertEquals(8,snapshots.first().single().items.size)
            val final=snapshots.last()
            assertEquals(6,final.size)
            assertTrue(final.none { it.id=="upcoming" })
            assertTrue(final.all { it.items.size==20 },final.map { it.id to it.items.size }.toString())
            for ((before,after) in snapshots.zipWithNext()) for (rail in before) {
                assertEquals(rail.items,after.first { it.id==rail.id }.items.take(rail.items.size))
            }
            assertTrue(final.flatMap { it.items }.all { f.family.allows(it) })
        }
    }
    @Test fun kidsCatalogRailsFillTwentyAndPaginationPreservesTheVisiblePrefix()=runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            val vm=CatalogViewModel(f.repository,"movie",watchProgressRepo=progress,contentPolicyFilter=f.filter)
            try {
                repeat(1000) {
                    dispatcher.scheduler.advanceTimeBy(30);dispatcher.scheduler.runCurrent()
                    if(!vm.state.value.shelvesLoaded) withContext(Dispatchers.Default) { delay(1) }
                }
                assertTrue(vm.state.value.shelvesLoaded)
                assertEquals(listOf(20,20,20),vm.state.value.let { listOf(it.trendingItems.size,it.popularItems.size,it.topRatedItems.size) })
                val prefix=vm.state.value.items.map { it.id }
                vm.loadMore()
                repeat(1000) {
                    dispatcher.scheduler.advanceTimeBy(30);dispatcher.scheduler.runCurrent()
                    if(vm.state.value.isLoadingMore) withContext(Dispatchers.Default) { delay(1) }
                }
                assertTrue(vm.state.value.items.size>prefix.size)
                assertEquals(prefix,vm.state.value.items.take(prefix.size).map { it.id })
            } finally { vm.close() }
        }
    }
    @Test fun streamedRowsAppendWithoutReplacingVisibleRowsOrHero() {
        val first=CatalogShelf(id="trending-movies",title="Trending",items=listOf(MediaItem("1",title="One",type=MediaType.MOVIE)))
        val second=CatalogShelf(id="trending-tv",title="TV",items=listOf(MediaItem("2",title="Two",type=MediaType.SERIES)))
        val initial=com.torve.presentation.home.HomeUiState().withInitialDiscoveryShelves(listOf(first))
        val next=initial.withInitialDiscoveryShelves(listOf(first.copy(items=listOf(MediaItem("3",title="Refreshed",type=MediaType.MOVIE))),second))
        assertEquals(listOf(first,second),next.shelves)
        assertEquals(initial.heroItem,next.heroItem)
        assertFalse(next.isLoading)
        assertEquals(next,next.withInitialDiscoveryShelves(listOf(first,second)))
    }
    @Test fun proofCacheSurvivesReopeningAndWritesOncePerPreparedBatch()=runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            var proofWrites=0
            val settings=object : com.torve.domain.repository.DeviceLocalSettingsRepository by f.device.preferences {
                override suspend fun setString(key:String,value:String) {
                    if(key=="family_device_certifications_v1") proofWrites++
                    f.device.preferences.setString(key,value)
                }
            }
            fun gate()=FamilyControls(settings,f.device.secure,null,SettingsRefreshNotifier(),lookup={listOf(AgeCertification("US","G"))},
                scope=backgroundScope,accountId={"fixture"},profileRatingLimit={ContentRating.G})
            val items=(1..100).map { MediaItem(it.toString(),title="G $it",type=MediaType.MOVIE,adult=false) }
            val first=gate();assertEquals(100,first.prepare(items).size);assertEquals(1,proofWrites)
            val reopened=gate();reopened.initialize()
            assertTrue(items.all { reopened.allows(it) });assertEquals(1,proofWrites)
        }
    }
    @Test fun slowReviewScoreProvidersDoNotBlockTheDefaultCatalogue()=runTest(dispatcher) {
        Fixture(backgroundScope).use { f ->
            var scoreRequests=0
            val slow=HttpClient(MockEngine(MockEngineConfig().apply { dispatcher=Dispatchers.Main;addHandler {
                scoreRequests++;delay(120_000);respond("{}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            } })) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
            f.device.signIn()
            f.device.preferences.setString("mdblist_api_key","score-fixture")
            val enricher=RatingsEnricher(MdbListApi(slow),f.api,TraktClient(slow,Json {ignoreUnknownKeys=true}),RatingsCacheRepository(f.device.database),OmdbClient(slow,f.device.preferences,f.device.secrets))
            val vm=CatalogViewModel(f.repository,"movie",prefsRepo=f.device.preferences,ratingsEnricher=enricher,contentPolicyFilter=f.filter)
            try {
                settle(vm)
                assertFalse(vm.state.value.isLoading);assertTrue(vm.state.value.items.isNotEmpty())
                assertTrue(testScheduler.currentTime<10_000,"Posters waited for review providers")
                repeat(20) { dispatcher.scheduler.runCurrent();withContext(Dispatchers.Default) { delay(2) } }
                assertTrue(scoreRequests>0,"The simulated slow provider was not exercised")
                assertFalse(vm.state.value.isLoading)
            } finally { vm.close();slow.close() }
        }
    }
}
