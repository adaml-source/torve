package com.torve.android.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.TvActionButton
import com.torve.android.tv.screens.TvDetailsHeroLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvDetailsHeroLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun tallHeaderContentCannotHideThePlayLabelOrPreventRemoteFocus() {
        val playRequester = FocusRequester()
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        TvDetailsHeroLayout(
                            isSeries = true,
                            background = { Box(Modifier.matchParentSize()) },
                            foreground = {
                                Spacer(Modifier.height(270.dp))
                                Text("11 episodes watched")
                                TvActionButton("Watch S2 E4", Modifier.focusRequester(playRequester),
                                    isPrimary = true, onFocused = {}, onClick = { clicks++ })
                            },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("Watch S2 E4").assertIsDisplayed()
        composeRule.onNodeWithText("Watch S2 E4").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("Watch S2 E4").assertIsFocused()
        composeRule.onNodeWithText("Watch S2 E4").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun largeTextAndMultipleStatusLinesKeepActionLabelsVisible() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MaterialTheme {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            TvDetailsHeroLayout(
                                isSeries = true,
                                background = { Box(Modifier.matchParentSize()) },
                                foreground = {
                                    Text("The Gentlemen", style = MaterialTheme.typography.headlineLarge)
                                    Text("2024 · Available on desktop")
                                    Text("11 episodes watched · In library")
                                    TvActionButton("Watch S2 E4", isPrimary = true, onFocused = {}, onClick = {})
                                    TvActionButton("Episodes", onFocused = {}, onClick = {})
                                },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Watch S2 E4").assertIsDisplayed()
        composeRule.onNodeWithText("Episodes").assertIsDisplayed()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun frequentActionsWrapAtLargeTextSizesWithoutLosingTheirLabels() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MaterialTheme {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Box(Modifier.width(460.dp)) {
                                TvDetailsHeroLayout(isSeries = true, background = {}, foreground = {
                                    Text("The Gentlemen", style = MaterialTheme.typography.headlineLarge)
                                    Text("11 episodes watched")
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        listOf("Watch S2 E4", "Trailer", "Add to Watchlist", "More").forEach { label ->
                                            TvActionButton(label, onFocused = {}, onClick = {})
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
        listOf("Watch S2 E4", "Trailer", "Add to Watchlist", "More").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("More").assertIsFocused()
    }
}
