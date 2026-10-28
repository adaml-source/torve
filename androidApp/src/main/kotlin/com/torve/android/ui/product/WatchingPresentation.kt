package com.torve.android.ui.product

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.domain.model.Episode
import com.torve.presentation.watching.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun watchingErrorMessage(state: WatchingHubState): String? = when (state.errorKind) {
    WatchingHubError.PARTIAL_REFRESH -> stringResource(R.string.flow_shows_partial_error)
    WatchingHubError.REFRESH_FAILED -> stringResource(R.string.flow_shows_refresh_error)
    WatchingHubError.MARK_FAILED -> stringResource(R.string.flow_shows_mark_error)
    null -> state.error
}

@Composable
internal fun WatchingEpisode.presentedEpisode(hideSpoilers: Boolean): Episode {
    val presented = episode.forPresentation(hideSpoilers, watched)
    return if (hideSpoilers && !watched) presented.copy(name = stringResource(R.string.flow_episode_number, episode.episodeNumber)) else presented
}

@Composable
internal fun watchingDateLabel(date: String?, today: String): String =
    localEpisodeDateLabel(date, today, LocalContext.current)

internal fun localEpisodeDateLabel(date: String?, today: String, context: Context): String {
    val value = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return context.getString(R.string.flow_date_unknown)
    val current = LocalDate.parse(today)
    return when (value) {
        current -> context.getString(R.string.flow_today)
        current.plusDays(1) -> context.getString(R.string.flow_tomorrow)
        current.minusDays(1) -> context.getString(R.string.flow_yesterday)
        else -> value.format(DateTimeFormatter.ofPattern("EEE, d MMM", context.resources.configuration.locales[0]))
    }
}
