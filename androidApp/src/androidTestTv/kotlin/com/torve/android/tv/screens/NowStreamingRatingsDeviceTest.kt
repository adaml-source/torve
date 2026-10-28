package com.torve.android.tv.screens

import androidx.test.platform.app.InstrumentationRegistry
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.metadata.TmdbApiClient
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Uses the existing QA account and normal rating pipeline, without reading or exporting credentials. */
class NowStreamingRatingsDeviceTest {
    @Test fun screenshotTitlesReceiveRealImdbRatingsThroughTheConfiguredPipeline() = runBlocking {
        val items = listOf(
            MediaItem("1416", tmdbId = 1416, title = "Grey's Anatomy", type = MediaType.SERIES),
            MediaItem("1411", tmdbId = 1411, title = "Person of Interest", type = MediaType.SERIES),
            MediaItem("83631", tmdbId = 83631, title = "What We Do in the Shadows", type = MediaType.SERIES),
        )
        val graph = GlobalContext.get()
        withTimeout(30_000) {
            for (item in items) {
                val detail = graph.get<TmdbApiClient>().getTvDetail(item.tmdbId!!)
                assertEquals("Verify title identity before checking a rating", item.title, detail.originalName ?: detail.name)
            }
        }
        val key = graph.get<IntegrationSecretStore>().get(IntegrationSecretKey.MDBLIST_API_KEY)
            ?: graph.get<PreferencesRepository>().getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
            ?: MdbListApi.DEFAULT_API_KEY
        val enriched = withTimeout(30_000) { graph.get<RatingsEnricher>().enrichList(items, key) }
        val receipt = enriched.joinToString(",", "{\"items\":[", "]}") {
            "{\"tmdbId\":${it.tmdbId},\"imdb\":${it.ratings?.imdbScore},\"rt\":${it.ratings?.rottenTomatoesScore}}"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), "now-streaming-ratings-observation.json").writeText(receipt)
        assertEquals(items.map { it.id }, enriched.map { it.id })
        assertTrue("Every established series in the screenshot should have an IMDb result", enriched.all { (it.ratings?.imdbScore ?: 0f) > 0f })
    }
}
