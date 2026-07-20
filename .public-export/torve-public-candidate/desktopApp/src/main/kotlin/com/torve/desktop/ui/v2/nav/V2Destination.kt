package com.torve.desktop.ui.v2.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

// NZB_MOVIES (formerly "Movies via Usenet") was removed in favor of a
// "Latest on Usenet" shelf inside the regular Movies tab - the source
// picker already surfaces NZB candidates so a separate destination was
// redundant. See V2NzbSeeAllPage for the dedicated filter grid that
// replaces it.
enum class V2Destination(
    val icon: ImageVector,
    val label: String,
) {
    HOME(Icons.Filled.Home, "Home"),
    MOVIES(Icons.Filled.Movie, "Movies"),
    TV_SHOWS(Icons.Filled.Tv, "Shows"),
    SEARCH(Icons.Filled.Search, "Search"),
    LIBRARY(Icons.Filled.VideoLibrary, "Library"),
    LIVE_TV(Icons.Filled.LiveTv, "Live"),
    RECORDINGS(Icons.Filled.FiberManualRecord, "Recordings"),
    SPORTS(Icons.Filled.SportsSoccer, "Sports"),
    ADULT(Icons.Filled.LocalFireDepartment, "Adult"),
}
