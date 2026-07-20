package com.torve.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.BuildConfig
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.AddonPolicyFlags
import com.torve.presentation.addon.AddonViewModel
import org.koin.compose.koinInject

enum class AddonCategory(val labelRes: Int) {
    STREAMS(R.string.addon_category_streams),
    CATALOGS(R.string.addon_category_catalogs),
    SUBTITLES(R.string.addon_category_subtitles),
}

data class PopularAddon(
    @param:StringRes val nameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val url: String,
    val categories: List<AddonCategory>,
    val logo: String? = null,
    val action: PopularAddonAction = PopularAddonAction.INSTALL,
    val actionUrl: String = url,
)

enum class PopularAddonAction {
    INSTALL,
    OPEN_BROWSER,
}

val POPULAR_ADDONS = listOf(
    PopularAddon(
        R.string.addon_popular_panda_name,
        R.string.addon_popular_panda_desc,
        "${BuildConfig.PANDA_BASE_URL.trimEnd('/')}/manifest.json",
        listOf(AddonCategory.STREAMS),
        "${BuildConfig.PANDA_BASE_URL.trimEnd('/')}/logo.png",
        action = PopularAddonAction.OPEN_BROWSER,
        actionUrl = "${BuildConfig.PANDA_BASE_URL.trimEnd('/')}/configure",
    ),
    PopularAddon(
        R.string.addon_popular_torrentio_name,
        R.string.addon_popular_torrentio_desc,
        "https://torrentio.strem.fun/manifest.json",
        listOf(AddonCategory.STREAMS),
        "https://torrentio.strem.fun/images/logo_v1.png",
    ),
    PopularAddon(
        R.string.addon_popular_cinemeta_name,
        R.string.addon_popular_cinemeta_desc,
        "https://v3-cinemeta.strem.io/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://v3-cinemeta.strem.io/images/cinemeta-logo.png",
    ),
    PopularAddon(
        R.string.addon_popular_tmdb_name,
        R.string.addon_popular_tmdb_desc,
        "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://www.themoviedb.org/assets/2/v4/logos/v2/blue_square_1-5bdc75aaebeb75dc7ae79426ddd9be3b2be1e342510f8202baf6bffa71d7f5c4.svg",
    ),
    PopularAddon(
        R.string.addon_popular_trakt_name,
        R.string.addon_popular_trakt_desc,
        "https://2ecbbd610840-trakt.baby-beamup.club/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://walter.trakt.tv/hotlink-ok/public/favicon.svg",
    ),
    PopularAddon(
        R.string.addon_popular_imdb_name,
        R.string.addon_popular_imdb_desc,
        "https://1fe84bc728af-imdb-catalogs.baby-beamup.club/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://1fe84bc728af-imdb-catalogs.baby-beamup.club/static/imdb-logo.png",
    ),
    PopularAddon(
        R.string.addon_popular_rpdb_name,
        R.string.addon_popular_rpdb_desc,
        "https://1fe84bc728af-rpdb.baby-beamup.club/manifest.json",
        listOf(AddonCategory.CATALOGS),
        "https://ratingposterdb.com/assets/img/logo.svg",
    ),
    PopularAddon(
        R.string.addon_popular_opensubtitles_name,
        R.string.addon_popular_opensubtitles_desc,
        "https://opensubtitles-v3.strem.io/manifest.json",
        listOf(AddonCategory.SUBTITLES),
        "https://opensubtitles-v3.strem.io/images/opensubtitles-logo.png",
    ),
)

@Composable
fun AddonCatalogScreen(
    onBack: () -> Unit,
    onManagePandaClick: () -> Unit = {},
    viewModel: AddonViewModel = koinInject(),
    addonSyncService: com.torve.data.addon.AddonSyncService = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }

    // Force a sync on screen entry so any stale duplicates (Panda in particular)
    // are collapsed before the user sees the list.
    LaunchedEffect(Unit) {
        // Scrub any local Panda duplicates first, then force-sync so the catalog
        // never displays two copies of the same addon.
        runCatching { addonSyncService.collapseLocalPandaDuplicates() }
        runCatching { addonSyncService.syncIfStale(reason = "addon_catalog_open", force = true) }
        viewModel.loadAddons()
    }

    val installedUrls = remember(state.addons) {
        state.addons
            .flatMap { addon ->
                val manifestUrl = addon.manifestUrl.trimEnd('/')
                val baseUrl = manifestUrl.removeSuffix("/manifest.json")
                listOf(manifestUrl, baseUrl)
            }
            .toSet()
    }

    val filtered = remember(searchQuery) {
        if (searchQuery.isBlank() || searchQuery.startsWith("http")) {
            POPULAR_ADDONS
        } else {
            POPULAR_ADDONS.filter { addon ->
                context.getString(addon.nameRes).contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val pandaBaseUrl = remember { BuildConfig.PANDA_BASE_URL.trimEnd('/') }
    val hasPandaInstalled = remember(state.addons) {
        state.addons.any {
            it.manifest.id == "com.torve.panda" ||
                it.manifestUrl.contains(pandaBaseUrl)
        }
    }

    val availableAddons = remember(filtered, installedUrls, hasPandaInstalled) {
        filtered.filter { addon ->
            if (addon.action == PopularAddonAction.OPEN_BROWSER) {
                return@filter !hasPandaInstalled
            }
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.addon_catalog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
        }

        Text(
            stringResource(R.string.addon_catalog_installed_count, state.addons.size),
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.addon_catalog_search_hint)) },
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
                            modifier = Modifier
                                .size(24.dp)
                                .padding(4.dp),
                            color = Amber,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = {
                            viewModel.setInstallUrl(searchQuery)
                            viewModel.installAddon()
                            searchQuery = ""
                        }) {
                            Icon(
                                Icons.Default.Add,
                                stringResource(R.string.common_install),
                                tint = Amber,
                            )
                        }
                    }
                }
            },
        )

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            if (state.addons.isNotEmpty()) {
                item(key = "installed_header") {
                    Text(
                        stringResource(R.string.addon_catalog_installed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Amber,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                items(state.addons, key = { it.manifestUrl }) { addon ->
                    val flags = state.policyFlagsByUrl[AddonViewModel.normalizeManifestUrl(addon.manifestUrl)]
                    val isRestricted = flags?.installable == false
                    val isPanda = addon.manifest.id == "com.torve.panda" ||
                        addon.manifestUrl.contains(pandaBaseUrl)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (isPanda) Modifier.clickable { onManagePandaClick() } else Modifier
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AddonLogo(name = addon.manifest.name, logoUrl = addon.manifest.logo)
                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                addon.manifest.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (isRestricted) Ash else Snow,
                            )
                            Text(
                                addon.manifest.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Silver,
                                maxLines = 1,
                            )
                            if (isRestricted) {
                                Text(
                                    stringResource(R.string.addon_restricted_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ash,
                                )
                            }
                        }

                        if (!isRestricted) {
                            Switch(
                                checked = addon.isEnabled,
                                onCheckedChange = { viewModel.toggleAddon(addon.manifestUrl, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Amber,
                                    checkedTrackColor = Amber.copy(alpha = 0.3f),
                                ),
                            )
                        }

                        if (isPanda) {
                            IconButton(onClick = { onManagePandaClick() }) {
                                Icon(
                                    Icons.Default.Settings,
                                    stringResource(R.string.panda_setup_reconfigure),
                                    tint = Amber,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        IconButton(onClick = { viewModel.removeAddon(addon.manifestUrl) }) {
                            Icon(
                                Icons.Default.Delete,
                                stringResource(R.string.common_remove),
                                tint = Ruby,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = Steel.copy(alpha = 0.15f))
                }

                item(key = "installed_spacer") {
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (availableAddons.isNotEmpty()) {
                item(key = "available_header") {
                    Text(
                        stringResource(R.string.addon_catalog_available),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Ash,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                items(availableAddons, key = { it.url }) { addon ->
                    val isThisInstalling = state.isInstalling && state.installingUrl == addon.url
                    val addonName = stringResource(addon.nameRes)
                    val addonDescription = stringResource(addon.descriptionRes)
                    val addonFlags = state.policyFlagsByUrl[AddonViewModel.normalizeManifestUrl(addon.url)]
                    val addonInstallBlocked = addonFlags?.installable == false

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AddonLogo(name = addonName, logoUrl = addon.logo)
                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                addonName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (addonInstallBlocked) Ash else Snow,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                addonDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = Silver,
                                maxLines = 2,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                addon.categories.joinToString(" \u2022 ") { category ->
                                    context.getString(category.labelRes)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Ash,
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        if (addonInstallBlocked) {
                            Text(
                                stringResource(R.string.addon_restricted_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = Ash,
                            )
                        } else {
                            OutlinedButton(
                                onClick = {
                                    if (addon.action == PopularAddonAction.OPEN_BROWSER) {
                                        onManagePandaClick()
                                    } else {
                                        viewModel.setInstallUrl(addon.url)
                                        viewModel.installAddon()
                                    }
                                },
                                enabled = addon.action == PopularAddonAction.OPEN_BROWSER || !state.isInstalling,
                            ) {
                                if (addon.action == PopularAddonAction.OPEN_BROWSER) {
                                    Text(
                                        stringResource(R.string.manage_panda_manage),
                                        color = Amber,
                                    )
                                } else if (isThisInstalling) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Amber,
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.addon_catalog_installing), color = Amber)
                                } else {
                                    Text(
                                        stringResource(R.string.addon_catalog_install),
                                        color = if (state.isInstalling) Steel else Amber,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Steel.copy(alpha = 0.15f))
                }
            }

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
                                Text(stringResource(R.string.addon_catalog_open_browser))
                            }
                            TextButton(
                                onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(lastUrl))
                                },
                            ) {
                                Text(stringResource(R.string.addon_catalog_copy_url), color = Amber)
                            }
                        }
                        Text(
                            text = stringResource(R.string.addon_catalog_manifest_hint),
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
