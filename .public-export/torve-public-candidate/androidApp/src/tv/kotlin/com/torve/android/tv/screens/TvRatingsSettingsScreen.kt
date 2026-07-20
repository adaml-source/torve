package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.*
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@Composable
fun TvRatingsSettingsScreen(
    railFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    entryFocusRequester: FocusRequester,
    onEntryFocusReadyChanged: (Boolean) -> Unit = {},
    onEntryFocusFocused: () -> Unit = {},
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val settingsState by settingsViewModel.state.collectAsState()
    val prefs = settingsState.ratingPrefs

    BackHandler(onBack = onBack)

    LaunchedEffect(entryFocusRequester) {
        onFirstContentRequester(entryFocusRequester)
    }
    DisposableEffect(Unit) {
        onEntryFocusReadyChanged(false)
        onDispose { onEntryFocusReadyChanged(false) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, top = 20.dp, end = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Title / back
        item(key = "title") {
            TvSettingCard(
                title = stringResource(R.string.tv_ratings_title),
                subtitle = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { left = railFocusRequester }
                    .onGloballyPositioned { onEntryFocusReadyChanged(true) },
                focusRequester = entryFocusRequester,
                onFocused = {
                    onEntryFocusFocused()
                    onContentFocused(entryFocusRequester)
                },
                onClick = onBack,
            )
        }

        // Show Ratings on Detail toggle
        item(key = "show_on_detail") {
            val requester = remember("rt_detail") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_ratings_show_on_detail),
                subtitle = if (prefs.showRatingsOnDetailPage) stringResource(R.string.tv_home_layout_enabled)
                           else stringResource(R.string.tv_home_layout_disabled),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    settingsViewModel.updateRatingPrefs(
                        prefs.copy(showRatingsOnDetailPage = !prefs.showRatingsOnDetailPage),
                    )
                },
            )
        }

        // Show Torve Score toggle
        item(key = "show_torve") {
            val requester = remember("rt_torve") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_ratings_show_torve_score),
                subtitle = if (prefs.showTorveScoreOnDetailPage) stringResource(R.string.tv_home_layout_enabled)
                           else stringResource(R.string.tv_home_layout_disabled),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    settingsViewModel.updateRatingPrefs(
                        prefs.copy(showTorveScoreOnDetailPage = !prefs.showTorveScoreOnDetailPage),
                    )
                },
            )
        }

        // Section header: Rating Sources
        item(key = "sources_header") {
            Text(
                text = stringResource(R.string.tv_ratings_section_sources),
                style = MaterialTheme.typography.labelLarge,
                color = Ash,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Per-source toggle cards
        items(RatingSource.entries.toList(), key = { "source_${it.name}" }) { source ->
            val requester = remember("rt_${source.name}") { FocusRequester() }
            val isEnabled = source in prefs.enabledProviders
            TvSettingCard(
                title = source.displayName,
                subtitle = if (isEnabled) stringResource(R.string.tv_home_layout_enabled)
                           else stringResource(R.string.tv_home_layout_disabled),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    val newProviders = if (isEnabled) {
                        prefs.enabledProviders - source
                    } else {
                        prefs.enabledProviders + source
                    }
                    settingsViewModel.updateRatingPrefs(prefs.copy(enabledProviders = newProviders))
                },
            )
        }

        // Max Ratings on Cards cycling card
        item(key = "max_ratings") {
            val requester = remember("rt_max") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_ratings_max_on_cards),
                subtitle = "${prefs.maxRatingsOnCard}",
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    val next = if (prefs.maxRatingsOnCard >= 5) 1 else prefs.maxRatingsOnCard + 1
                    settingsViewModel.updateRatingPrefs(prefs.copy(maxRatingsOnCard = next))
                },
            )
        }

        // Reset
        item(key = "reset") {
            val requester = remember("rt_reset") { FocusRequester() }
            TvSettingCard(
                title = stringResource(R.string.tv_ratings_reset),
                subtitle = stringResource(R.string.tv_ratings_reset_subtitle),
                modifier = Modifier.fillMaxWidth().focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = {
                    settingsViewModel.updateRatingPrefs(RatingDisplayPrefs())
                },
            )
        }
    }
}
