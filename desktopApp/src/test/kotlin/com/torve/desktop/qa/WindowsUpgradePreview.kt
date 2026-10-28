package com.torve.desktop.qa

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.torve.desktop.di.desktopAppModule
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.theme.TorveDesktopTheme
import com.torve.desktop.ui.v2.library.*
import com.torve.di.sharedModule
import com.torve.domain.model.*
import com.torve.presentation.home.HomeUiState
import com.torve.presentation.settings.ThemeMode
import org.koin.core.context.startKoin

/** Manual native smoke host; run only with LOCALAPPDATA pointing at a disposable fixture directory. */
fun main() {
    require(System.getenv("TORVE_UPGRADE_PREVIEW") == "1") { "Preview requires an isolated data directory" }
    require(System.getenv("LOCALAPPDATA").orEmpty().contains("windows-1.6.0"))
    startKoin { modules(sharedModule, desktopAppModule) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Torve 1.6.0 Windows workflow preview", state = rememberWindowState(width = 1040.dp, height = 820.dp)) {
            TorveDesktopTheme(ThemeMode.DARK) {
                var tool by remember { mutableStateOf<DesktopLibraryTool?>(null) }
                var selected by remember { mutableStateOf("") }
                val home = remember { HomeUiState(isLoading = false, shelves = listOf(
                    CatalogShelf("fixture-movies", "Fixture movies", listOf(MediaItem("1", type = MediaType.MOVIE, title = "Fixture movie", tmdbId = 1))),
                    CatalogShelf("fixture-shows", "Fixture shows", listOf(MediaItem("2", type = MediaType.SERIES, title = "Fixture show", tmdbId = 2))),
                )) }
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Windows 1.6.0 · isolated workflow fixture")
                    DesktopLibraryTool.entries.forEach { entry -> TorveGhostButton(entry.label, { tool = entry }) }
                    Text(selected)
                    DesktopFilesBrowser { entry, _ -> selected = "Selected file: ${entry.name}" }
                }
                tool?.let { current -> DesktopLibraryTools(current, home, emptyList(), { tool = null },
                    { selected = "S${it.season} E${it.episode.episodeNumber}"; tool = null },
                    { selected = it.title; tool = null }) }
            }
        }
    }
}
