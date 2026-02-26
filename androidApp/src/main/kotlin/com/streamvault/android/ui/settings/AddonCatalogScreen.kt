package com.streamvault.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import android.content.Intent
import android.net.Uri
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Ash
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Ruby
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.presentation.addon.AddonViewModel
import org.koin.compose.koinInject

enum class AddonCategory(val label: String) {
    ALL("All"),
    STREAMS("Streams"),
    CATALOGS("Catalogs"),
    SUBTITLES("Subtitles"),
}

data class PopularAddon(
    val name: String,
    val description: String,
    val url: String,
    val categories: List<AddonCategory>,
    val logo: String? = null,
)

val POPULAR_ADDONS = listOf(
    // ── Catalogs / Metadata ──
    PopularAddon(
        "Cinemeta",
        "Official Stremio catalog — movie & series info from IMDB/TMDB",
        "https://v3-cinemeta.strem.io/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://v3-cinemeta.strem.io/images/cinemeta-logo.png",
    ),
    PopularAddon(
        "The Movie Database Addon",
        "Rich catalogs powered by TMDB — trending, popular, top rated",
        "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://www.themoviedb.org/assets/2/v4/logos/v2/blue_square_1-5bdc75aaebeb75dc7ae79426ddd9be3b2be1e342510f8202baf6bffa71d7f5c4.svg",
    ),
    PopularAddon(
        "Trakt Lists",
        "Access your Trakt lists, trending, popular, and anticipated titles",
        "https://2ecbbd610840-trakt.baby-beamup.club/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://walter.trakt.tv/hotlink-ok/public/favicon.svg",
    ),
    PopularAddon(
        "RPDB Catalogs",
        "Rating poster database — catalogs with rating overlays",
        "https://1fe84bc728af-rpdb.baby-beamup.club/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://ratingposterdb.com/assets/img/logo.svg",
    ),
    // ── Streams ──
    PopularAddon(
        "Torrentio",
        "Streams from various sources — works great with debrid",
        "https://torrentio.strem.fun/manifest.json",
        listOf(AddonCategory.STREAMS),
        "https://torrentio.strem.fun/images/torrentio-logo.png",
    ),
    PopularAddon(
        "MediaFusion",
        "Aggregated streams from multiple sources with debrid support",
        "https://mediafusion.elfhosted.com/manifest.json",
        listOf(AddonCategory.STREAMS),
        "https://mediafusion.elfhosted.com/static/images/mediafusion_logo.png",
    ),
    PopularAddon(
        "Comet",
        "Fast debrid stream resolver with smart caching",
        "https://comet.elfhosted.com/manifest.json",
        listOf(AddonCategory.STREAMS),
        "https://comet.elfhosted.com/static/images/comet.png",
    ),
    PopularAddon(
        "Jackettio",
        "Streams via Jackett indexers — requires Jackett setup",
        "https://jackettio.elfhosted.com/manifest.json",
        listOf(AddonCategory.STREAMS),
        "https://jackettio.elfhosted.com/static/images/jackettio.png",
    ),
    PopularAddon(
        "Peerflix",
        "P2P streaming with instant playback",
        "https://peerflix-addon.elfhosted.com/manifest.json",
        listOf(AddonCategory.STREAMS),
        "https://peerflix-addon.elfhosted.com/static/images/peerflix.png",
    ),
    // ── Subtitles ──
    PopularAddon(
        "OpenSubtitles v3",
        "Subtitles from OpenSubtitles.com — largest subtitle database",
        "https://opensubtitles-v3.strem.io/manifest.json",
        listOf(AddonCategory.SUBTITLES),
        "https://opensubtitles-v3.strem.io/images/opensubtitles-logo.png",
    ),
    PopularAddon(
        "OpenSubtitles Pro",
        "Enhanced OpenSubtitles with ad-free access",
        "https://opensubtitlespro.elfhosted.com/manifest.json",
        listOf(AddonCategory.SUBTITLES),
        "https://opensubtitlespro.elfhosted.com/static/images/opensubtitlespro.png",
    ),
    PopularAddon(
        "Subdl Subtitles",
        "Subtitles from subdl.com — fast and comprehensive",
        "https://subdl-subs.elfhosted.com/manifest.json",
        listOf(AddonCategory.SUBTITLES),
        "https://subdl-subs.elfhosted.com/static/images/subdl.png",
    ),
    PopularAddon(
        "Subscene Subtitles",
        "Community subtitles from Subscene",
        "https://subscene-subs.elfhosted.com/manifest.json",
        listOf(AddonCategory.SUBTITLES),
        "https://subscene-subs.elfhosted.com/static/images/subscene.png",
    ),
)

@Composable
fun AddonCatalogScreen(
    onBack: () -> Unit,
    viewModel: AddonViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(AddonCategory.ALL) }

    val installedUrls = remember(state.addons) {
        state.addons
            .flatMap { addon ->
                val manifestUrl = addon.manifestUrl.trimEnd('/')
                val baseUrl = manifestUrl.removeSuffix("/manifest.json")
                listOf(manifestUrl, baseUrl)
            }
            .toSet()
    }

    val filtered = remember(searchQuery, selectedCategory) {
        val byCategory = if (selectedCategory == AddonCategory.ALL) POPULAR_ADDONS
        else POPULAR_ADDONS.filter { selectedCategory in it.categories }

        if (searchQuery.isBlank() || searchQuery.startsWith("http")) byCategory
        else byCategory.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val availableAddons = remember(filtered, installedUrls) {
        filtered.filter { addon ->
            val manifestUrl = addon.url.trimEnd('/')
            val baseUrl = manifestUrl.removeSuffix("/manifest.json")
            manifestUrl !in installedUrls && baseUrl !in installedUrls
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // ── Top Bar ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                "Addon Catalog",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
        }

        Text(
            "${state.addons.size} installed",
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        // ── Search / URL Input ──
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search or paste addon URL...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Amber,
                unfocusedBorderColor = Steel,
                cursorColor = Amber,
                focusedTextColor = Snow,
                unfocusedTextColor = Snow,
            ),
            trailingIcon = {
                if (searchQuery.startsWith("http")) {
                    if (state.isInstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(4.dp),
                            color = Amber,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = {
                            viewModel.setInstallUrl(searchQuery)
                            viewModel.installAddon()
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Add, "Install", tint = Amber)
                        }
                    }
                }
            },
        )

        // ── Category Filter Chips ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AddonCategory.entries.forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(category.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber,
                        selectedLabelColor = Gunmetal,
                        containerColor = Gunmetal,
                        labelColor = Silver,
                    ),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            // ── Installed Section ──
            if (state.addons.isNotEmpty()) {
                item(key = "installed_header") {
                    Text(
                        "Installed",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Amber,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                items(state.addons, key = { it.manifestUrl }) { addon ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Logo with letter fallback
                        AddonLogo(name = addon.manifest.name, logoUrl = addon.manifest.logo)
                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                addon.manifest.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Snow,
                            )
                            addon.manifest.description?.let { desc ->
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Silver,
                                    maxLines = 1,
                                )
                            }
                        }

                        // Toggle
                        Switch(
                            checked = addon.isEnabled,
                            onCheckedChange = { viewModel.toggleAddon(addon.manifestUrl, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Amber,
                                checkedTrackColor = Amber.copy(alpha = 0.3f),
                            ),
                        )

                        // Delete
                        IconButton(onClick = { viewModel.removeAddon(addon.manifestUrl) }) {
                            Icon(Icons.Default.Delete, "Remove", tint = Ruby, modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider(color = Steel.copy(alpha = 0.15f))
                }

                item(key = "installed_spacer") {
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ── Available Section ──
            if (availableAddons.isNotEmpty()) {
                item(key = "available_header") {
                    Text(
                        "Available",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Ash,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                items(availableAddons, key = { it.url }) { addon ->
                    val isThisInstalling = state.isInstalling && state.installingUrl == addon.url
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Logo with letter fallback
                        AddonLogo(name = addon.name, logoUrl = addon.logo)
                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                addon.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Snow,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                addon.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Silver,
                                maxLines = 2,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                addon.categories.joinToString(" \u2022 ") { it.label },
                                style = MaterialTheme.typography.labelSmall,
                                color = Ash,
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        OutlinedButton(
                            onClick = {
                                viewModel.setInstallUrl(addon.url)
                                viewModel.installAddon()
                            },
                            enabled = !state.isInstalling,
                        ) {
                            if (isThisInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Amber,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Installing…", color = Amber)
                            } else {
                                Text("Install", color = if (state.isInstalling) Steel else Amber)
                            }
                        }
                    }
                    HorizontalDivider(color = Steel.copy(alpha = 0.15f))
                }
            }

            // Install error message
            if (state.installError != null) {
                item(key = "install_error") {
                    Text(
                        state.installError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ruby,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    val lastUrl = state.lastInstallUrl
                    if (lastUrl.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(lastUrl))
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                            ) {
                                Text("Open in Browser")
                            }
                            TextButton(
                                onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(lastUrl))
                                },
                            ) {
                                Text("Copy URL", color = Amber)
                            }
                        }
                        Text(
                            text = "If the page shows a JSON manifest, copy its URL and try again.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Silver,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonLogo(name: String, logoUrl: String?) {
    var showFallback by remember(logoUrl) { mutableStateOf(logoUrl == null) }

    if (!showFallback && logoUrl != null) {
        AsyncImage(
            model = logoUrl,
            contentDescription = name,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
            onError = { showFallback = true },
        )
    } else {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Amber.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Amber,
            )
        }
    }
}
