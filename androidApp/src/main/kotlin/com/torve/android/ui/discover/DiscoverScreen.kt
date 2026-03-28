package com.torve.android.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberDark
import com.torve.android.ui.theme.Amethyst
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Coral
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Sapphire
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.presentation.discover.DiscoverTab
import com.torve.presentation.discover.DiscoverViewModel
import com.torve.presentation.discover.GenreDisplay
import org.koin.compose.koinInject

@Composable
fun DiscoverScreen(
    onGenreClick: (genreId: Int, genreName: String, mediaType: String) -> Unit,
    onChannelsClick: () -> Unit = {},
    onMoodClick: () -> Unit = {},
    viewModel: DiscoverViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding(),
    ) {
        // Header
        Text(
            text = stringResource(R.string.discover_title),
            style = MaterialTheme.typography.headlineLarge,
            color = Snow,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        // Sub-tabs
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            DiscoverTab.entries.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = state.selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = DiscoverTab.entries.size,
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Amber.copy(alpha = 0.2f),
                        activeContentColor = Amber,
                        inactiveContainerColor = Gunmetal,
                        inactiveContentColor = Torve.colors.textSecondary,
                        activeBorderColor = Amber.copy(alpha = 0.4f),
                        inactiveBorderColor = Gunmetal,
                    ),
                ) {
                    Text(tab.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (state.selectedTab == DiscoverTab.LIVE_TV) {
            // Placeholder for Live TV — will be wired to ChannelsScreen content later
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Channels available in the Channels tab",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textSecondary,
                )
            }
        } else {
            // Genre grid
            val mediaType = if (state.selectedTab == DiscoverTab.MOVIES) "movie" else "tv"

            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Mood Matcher card
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(Amber, AmberDark)))
                            .clickable(onClick = onMoodClick)
                            .padding(20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            Text(
                                "What should I watch?",
                                style = MaterialTheme.typography.titleLarge,
                                color = Obsidian,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Pick a mood, get recommendations",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Obsidian.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                // Section header
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Browse by Genre",
                        style = MaterialTheme.typography.titleMedium,
                        color = Amber,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
                    )
                }

                items(state.genres) { genre ->
                    GenreCard(
                        genre = genre,
                        onClick = { onGenreClick(genre.id, genre.name, mediaType) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreCard(
    genre: GenreDisplay,
    onClick: () -> Unit,
) {
    val gradient = genreGradient(genre.id)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(gradient))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        // Subtle overlay for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                    ),
                ),
        )
        Text(
            text = genre.name,
            style = MaterialTheme.typography.titleMedium,
            color = Snow,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private fun genreGradient(genreId: Int): List<Color> = when (genreId) {
    28, 10759 -> listOf(Coral, Ruby)                    // Action
    12 -> listOf(Amber, AmberDark)                       // Adventure
    16 -> listOf(Color(0xFF42A5F5), Sapphire)            // Animation
    35 -> listOf(Amber, Color(0xFFFDD835))               // Comedy
    80 -> listOf(Color(0xFF455A64), Charcoal)             // Crime
    99 -> listOf(Emerald, Color(0xFF00897B))              // Documentary
    18 -> listOf(Amethyst, Color(0xFF7C4DFF))             // Drama
    10751 -> listOf(Color(0xFFFF8A65), Coral)             // Family
    14, 10765 -> listOf(Color(0xFF7C4DFF), Amethyst)      // Fantasy / Sci-Fi & Fantasy
    36 -> listOf(Color(0xFF8D6E63), Color(0xFF5D4037))    // History
    27 -> listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))    // Horror
    10402 -> listOf(Color(0xFFEC407A), Color(0xFFC2185B))  // Music
    9648 -> listOf(Color(0xFF546E7A), Color(0xFF37474F))   // Mystery
    10749 -> listOf(Color(0xFFEC407A), Color(0xFFAD1457))  // Romance
    878 -> listOf(Sapphire, Color(0xFF1565C0))             // Sci-Fi
    53 -> listOf(Color(0xFF616161), Color(0xFF212121))     // Thriller
    10752, 10768 -> listOf(Color(0xFF5D4037), Color(0xFF3E2723)) // War
    37 -> listOf(Color(0xFFA1887F), Color(0xFF795548))     // Western
    10762 -> listOf(Color(0xFF66BB6A), Color(0xFF43A047))  // Kids
    10764 -> listOf(Color(0xFFFF7043), Color(0xFFE64A19))  // Reality
    else -> listOf(Gunmetal, Charcoal)
}
