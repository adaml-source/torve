package com.torve.android.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.components.mediaItemLazyKey
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.catalog.CatalogViewModel
import org.koin.compose.koinInject

@Composable
fun TvHomeScreen(
    onMediaClick: (MediaItem) -> Unit,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val integrationSecretStore: IntegrationSecretStore = koinInject()
    val movieViewModel = remember {
        CatalogViewModel(
            metadataRepo = metadataRepo,
            mediaType = "movie",
            prefsRepo = prefsRepo,
            ratingsEnricher = ratingsEnricher,
            integrationSecretStore = integrationSecretStore,
        )
    }
    val tvViewModel = remember {
        CatalogViewModel(
            metadataRepo = metadataRepo,
            mediaType = "tv",
            prefsRepo = prefsRepo,
            ratingsEnricher = ratingsEnricher,
            integrationSecretStore = integrationSecretStore,
        )
    }

    val movieState by movieViewModel.state.collectAsState()
    val tvState by tvViewModel.state.collectAsState()

    val movieItems = remember(movieState.trendingItems, movieState.items) {
        val source = movieState.trendingItems.ifEmpty { movieState.items }
        source.take(24)
    }
    val tvItems = remember(tvState.trendingItems, tvState.items) {
        val source = tvState.trendingItems.ifEmpty { tvState.items }
        source.take(24)
    }

    val firstCardFocusRequester = remember { FocusRequester() }
    val shouldFocusMovies = movieItems.isNotEmpty()
    val shouldFocusTv = !shouldFocusMovies && tvItems.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D1220), Color(0xFF151B2F), Color(0xFF0A0D18)),
                ),
            ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.home_recommended),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }
            }

            if (movieState.isLoading || tvState.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        CircularProgressIndicator(color = Color(0xFFE8A838))
                    }
                }
            }

            if (movieItems.isNotEmpty()) {
                item {
                    TvContentRow(
                        title = stringResource(R.string.tv_trending_movies),
                        items = movieItems,
                        onItemClick = onMediaClick,
                        initialFocusRequester = if (shouldFocusMovies) firstCardFocusRequester else null,
                    )
                }
            }

            if (tvItems.isNotEmpty()) {
                item {
                    TvContentRow(
                        title = stringResource(R.string.tv_trending_tv),
                        items = tvItems,
                        onItemClick = onMediaClick,
                        initialFocusRequester = if (shouldFocusTv) firstCardFocusRequester else null,
                    )
                }
            }

            if (!movieState.isLoading && !tvState.isLoading && movieItems.isEmpty() && tvItems.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.catalog_failed_to_load),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.86f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvContentRow(
    title: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    initialFocusRequester: FocusRequester?,
) {
    LaunchedEffect(initialFocusRequester, items.firstOrNull()?.id) {
        if (initialFocusRequester != null && items.isNotEmpty()) {
            initialFocusRequester.requestFocus()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            itemsIndexed(items, key = { index, item -> mediaItemLazyKey(item, index) }) { index, item ->
                TvMediaCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.then(
                        if (index == 0 && initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun TvMediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(220.dp)
            .aspectRatio(2f / 3f)
            .clip(MaterialTheme.shapes.medium),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Content-policy hardening: locked/placeholder items get no real artwork
            AsyncImage(
                model = if (item.isContentPlaceholder || item.isStubDetail) null else item.posterUrl,
                contentDescription = if (item.isContentPlaceholder || item.isStubDetail) null else item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.year?.let { year ->
                        Text(
                            text = year.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                    }
                }
            }
        }
    }
}
