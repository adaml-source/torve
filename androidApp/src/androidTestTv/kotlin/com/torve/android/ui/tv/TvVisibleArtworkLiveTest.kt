package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.rememberTvVisibleMetadata
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Opt-in provider integration check; never requires network in the regular suite. */
class TvVisibleArtworkLiveTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun sparseSavedShowsResolveToDecodablePosterArtwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_metadata") == "true")
        val items = listOf(
            MediaItem("94997", tmdbId = 94997, type = MediaType.SERIES, title = "House of the Dragon"),
            MediaItem("125988", tmdbId = 125988, type = MediaType.SERIES, title = "Silo"),
        )
        val resolved = AtomicReference(items)
        rule.setContent { MaterialTheme {
            val enriched = rememberTvVisibleMetadata(items, "live-artwork-check")
            resolved.set(enriched)
            Text(enriched.joinToString { "${it.title}: ${!it.posterUrl.isNullOrBlank()}" })
        } }
        rule.waitUntil(30_000) { resolved.get().all { !it.posterUrl.isNullOrBlank() } }
        resolved.get().forEach { item ->
            val result = runBlocking(Dispatchers.IO) { withTimeout(15_000) {
                rule.activity.imageLoader.execute(ImageRequest.Builder(rule.activity).data(item.posterUrl).size(240, 360).build())
            } }
            assertTrue("Poster must decode to pixels for ${item.title}", result is SuccessResult)
        }
    }
}
