package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.TvDetailsMetadataRow
import com.torve.android.tv.screens.TvDetailsHeroLayout
import com.torve.android.tv.screens.TvActionButton
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import org.junit.Rule
import org.junit.Test

class TvDetailsMetadataRowTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun headerUsesTheProviderScoreAndObservesDetailVisibilitySettings() {
        var visible by mutableStateOf(true)
        val movie = MediaItem("42", title = "You Can See Everything", type = MediaType.MOVIE,
            year = 2026, runtime = 174, rating = 6.1,
            ratings = MediaRatings(imdbScore = 9.3f, rottenTomatoesScore = 98))
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF11141C)).padding(24.dp)) {
                    TvDetailsHeroLayout(false, background = {
                        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                            listOf(Color(0xFF131720), Color(0xFF333946), Color(0xFF686354)))))
                    }, foreground = {
                        Text(movie.title, color = Color.White, style = MaterialTheme.typography.headlineLarge)
                        TvDetailsMetadataRow(movie, RatingDisplayPrefs(showRatingsOnDetailPage = visible,
                            enabledProviders = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES)))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("Watch now", "Trailer", "Add to Watchlist", "More").forEach { label ->
                                TvActionButton(label, isPrimary = label == "Watch now", onFocused = {}, onClick = {})
                            }
                        }
                    })
                }
            }
        }
        composeRule.onNodeWithText("2026 · 174m").assertIsDisplayed()
        composeRule.onNodeWithText("9.3").assertIsDisplayed()
        composeRule.onNodeWithText("98%").assertIsDisplayed()
        composeRule.onNodeWithText("6.1").assertDoesNotExist()
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val proof = java.io.File(composeRule.activity.getExternalFilesDir(null), "detail-glass-header.png")
        proof.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        composeRule.runOnIdle { visible = false }
        composeRule.onNodeWithText("9.3").assertDoesNotExist()
        composeRule.onNodeWithText("98%").assertDoesNotExist()
        composeRule.onNodeWithText("2026 · 174m").assertIsDisplayed()
    }
}
