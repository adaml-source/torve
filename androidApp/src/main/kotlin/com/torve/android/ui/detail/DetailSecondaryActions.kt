package com.torve.android.ui.detail

import com.torve.android.ui.community.parityLabel as localizedUiText

import com.torve.android.ui.system.TorveModalBottomSheet as ModalBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.*
import com.torve.android.ui.components.MobileGlassIconButton as OutlinedIconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Torve
import com.torve.presentation.detail.DetailUiState
import com.torve.presentation.detail.primaryPlaybackTarget
import com.torve.presentation.detail.playbackPositionLabel
import kotlin.math.roundToInt

@Composable
internal fun DetailPlaybackButtonLabel(label: String) {
    val parts = label.split(" · ", limit = 2)
    Column(verticalArrangement = Arrangement.Center) {
        Text(parts.first(), style = MaterialTheme.typography.labelMedium, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (parts.size > 1) Text(parts.last(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
internal fun detailPlaybackLabel(state: DetailUiState): String {
    val target = state.primaryPlaybackTarget
    val season = target.season
    val episode = target.episode
    val position = target.positionMs
    return when {
        season != null && episode != null -> stringResource(
            if (target.isResume) R.string.detail_resume_episode else R.string.detail_watch_episode,
            season, episode,
        )
        target.isResume && position != null -> stringResource(R.string.detail_resume_position, playbackPositionLabel(position))
        target.isResume -> stringResource(R.string.player_resume)
        else -> stringResource(R.string.detail_watch_now)
    }
}

internal data class DetailSecondaryActionSpec(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val enabled: Boolean = true,
    val testTag: String? = null,
    val onClick: () -> Unit,
)

/** Keep secondary actions available without pushing the synopsis off screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailSecondaryActions(
    title: String,
    actions: List<DetailSecondaryActionSpec>,
    onTrailer: (() -> Unit)?,
    modifier: Modifier = Modifier,
    libraryStatus: DetailUiState? = null,
    primaryContent: (@Composable RowScope.() -> Unit)? = null,
    watchlistAction: DetailSecondaryActionSpec? = null,
    onPlayOnTv: (() -> Unit)? = null,
) {
    var showMore by remember(title) { mutableStateOf(false) }
    // Read the Activity's inset before the sheet consumes it in its own window.
    val bottomInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    BoxWithConstraints(modifier) {
    val inlineCast = maxWidth >= 400.dp
    val inlineLibrary = maxWidth >= 360.dp
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        primaryContent?.invoke(this)
        onTrailer?.let { open ->
            OutlinedIconButton(onClick = open, modifier = Modifier.size(48.dp).testTag("detail_trailer_button")) {
                Icon(Icons.Rounded.OndemandVideo, stringResource(R.string.detail_watch_trailer), Modifier.size(22.dp))
            }
        }
        onPlayOnTv?.takeIf { inlineCast }?.let { play ->
            OutlinedIconButton(onClick = play, modifier = Modifier.size(48.dp).testTag("detail_play_on_tv")) {
                Icon(Icons.Rounded.Cast, stringResource(R.string.detail_play_on_tv))
            }
        }
        watchlistAction?.let { action ->
            OutlinedIconButton(onClick = action.onClick, enabled = action.enabled, modifier = Modifier.size(48.dp)) {
                Icon(action.icon, action.label, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        libraryStatus?.takeIf { inlineLibrary }?.let { DetailLibraryBadge(it, onClick = { showMore = true }) }
        OutlinedIconButton(
            onClick = { showMore = true },
            modifier = Modifier.size(48.dp).testTag("detail_more"),
        ) {
            Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.product_more), Modifier.size(22.dp))
        }
    }
    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) },
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = bottomInset + 16.dp)) {
                Text(localizedUiText(title), style = MaterialTheme.typography.titleLarge)
                libraryStatus?.takeIf { !inlineLibrary }?.let { state ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.nav_library), Modifier.weight(1f))
                        DetailLibraryBadge(state, onClick = {})
                    }
                }
                Spacer(Modifier.height(12.dp))
                onTrailer?.let { open -> TextButton(onClick = { showMore = false; open() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Movie, null); Spacer(Modifier.width(16.dp)); Text(stringResource(R.string.detail_watch_trailer), Modifier.weight(1f))
                } }
                onPlayOnTv?.let { play ->
                    TextButton(onClick = { showMore = false; play() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Cast, null); Spacer(Modifier.width(16.dp)); Text(stringResource(R.string.detail_play_on_tv), Modifier.weight(1f))
                    }
                }
                actions.forEach { action ->
                    TextButton(
                        onClick = { showMore = false; action.onClick() },
                        enabled = action.enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .then(action.testTag?.let { Modifier.testTag(it) } ?: Modifier),
                        colors = ButtonDefaults.textButtonColors(contentColor = action.contentColor),
                    ) {
                        Icon(action.icon, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(localizedUiText(action.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                TextButton(onClick = { showMore = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("detail_more_close")) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}
}

@Composable
internal fun DetailWatchedConfirmation(
    isSeries: Boolean,
    markWatched: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(when {
        isSeries && markWatched -> R.string.detail_mark_show_watched
        isSeries -> R.string.detail_clear_show_history
        markWatched -> R.string.detail_mark_movie_watched
        else -> R.string.detail_mark_movie_unwatched
    })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedUiText(title)) },
        text = { Text(stringResource(when {
            isSeries && markWatched -> R.string.detail_confirm_show_watched
            isSeries -> R.string.detail_confirm_clear_history
            markWatched -> R.string.detail_confirm_movie_watched
            else -> R.string.detail_confirm_movie_unwatched
        })) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(localizedUiText(title)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Opening or cancelling the editor never changes a rating. */
@Composable
internal fun DetailRatingDialog(currentRating: Int?, onSave: (Int?) -> Unit, onDismiss: () -> Unit) {
    var rating by remember(currentRating) { mutableFloatStateOf((currentRating ?: 7).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_your_rating)) },
        text = {
            Column {
                Text("${rating.roundToInt()}/10", style = MaterialTheme.typography.headlineMedium)
                Slider(value = rating, onValueChange = { rating = it }, valueRange = 1f..10f, steps = 8)
                if (currentRating != null) {
                    TextButton(onClick = { onSave(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.detail_remove_rating), color = Torve.colors.textSecondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(rating.roundToInt()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) } },
    )
}
