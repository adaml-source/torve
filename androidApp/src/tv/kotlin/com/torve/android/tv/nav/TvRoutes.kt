package com.torve.android.tv.nav

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import com.torve.android.R

data class TvTopDestination(
    val route: String,
    @StringRes val labelResId: Int,
    val icon: ImageVector,
)

object TvRoutes {
    /** Empty start destination for the sub-route NavHost (details, player, etc.). */
    const val SUB_NAV_START = "tv_none"
    const val HOME = "tv_home"
    const val MOVIES = "tv_movies"
    const val SHOWS = "tv_shows"
    const val SEARCH = "tv_search"
    const val IPTV = "tv_iptv"
    const val SPORTS = "tv_sports"
    const val LIBRARY = "tv_library"
    const val SETTINGS = "tv_settings"
    const val HOME_LAYOUT = "tv_home_layout"
    const val RATINGS_SETTINGS = "tv_ratings_settings"
    const val WATCH_STATS = "tv_watch_stats"
    const val BETA_PROGRAM = "tv_beta_program"
    const val DEVICE_LIMIT_REACHED = "tv_device_limit_reached"
    const val PANDA_SETUP = "tv_panda_setup"

    const val SEE_ALL = "tv_see_all/{railKey}/{mediaType}/{title}"
    fun seeAll(railKey: String, mediaType: String, title: String): String {
        return "tv_see_all/${Uri.encode(railKey)}/${Uri.encode(mediaType)}/${Uri.encode(title)}"
    }

    const val VOD_SERIES_DETAILS = "tv_vod_series_details/{cacheKey}"
    fun vodSeriesDetails(cacheKey: String): String {
        return "tv_vod_series_details/${Uri.encode(cacheKey)}"
    }

    const val DETAILS = "tv_details/{type}/{id}?autoPlay={autoPlay}&handoffPositionMs={handoffPositionMs}&focusEpisodes={focusEpisodes}"
    fun details(
        type: String,
        id: Int,
        autoPlay: Boolean = false,
        handoffPositionMs: Long = 0L,
        focusEpisodes: Boolean = false,
    ): String {
        return "tv_details/$type/$id?autoPlay=$autoPlay&handoffPositionMs=$handoffPositionMs&focusEpisodes=$focusEpisodes"
    }

    const val LIVE_PLAYER = "tv_live_player?channelUrl={channelUrl}&channelName={channelName}&groupName={groupName}"
    fun livePlayer(channelUrl: String, channelName: String, groupName: String): String {
        return "tv_live_player?channelUrl=${Uri.encode(channelUrl)}" +
            "&channelName=${Uri.encode(channelName)}" +
            "&groupName=${Uri.encode(groupName)}"
    }

    const val PLAYER =
        "tv_player?url={url}&title={title}&mediaId={mediaId}&mediaType={mediaType}" +
            "&posterUrl={posterUrl}&backdropUrl={backdropUrl}" +
            "&seasonNumber={seasonNumber}&episodeNumber={episodeNumber}" +
            "&showTmdbId={showTmdbId}&showImdbId={showImdbId}&fallbackUrl={fallbackUrl}" +
            "&startPositionMs={startPositionMs}&autoSourceSelection={autoSourceSelection}" +
            "&episodeName={episodeName}"

    fun player(
        url: String,
        fallbackUrl: String,
        title: String,
        mediaId: String,
        mediaType: String,
        posterUrl: String = "",
        backdropUrl: String = "",
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeName: String = "",
        showTmdbId: Int? = null,
        showImdbId: String? = null,
        startPositionMs: Long = 0L,
        autoSourceSelection: Boolean = false,
    ): String {
        return "tv_player?url=${Uri.encode(url)}" +
            "&title=${Uri.encode(title)}" +
            "&mediaId=${Uri.encode(mediaId)}" +
            "&mediaType=${Uri.encode(mediaType)}" +
            "&posterUrl=${Uri.encode(posterUrl)}" +
            "&backdropUrl=${Uri.encode(backdropUrl)}" +
            "&seasonNumber=${seasonNumber ?: -1}" +
            "&episodeNumber=${episodeNumber ?: -1}" +
            "&showTmdbId=${showTmdbId ?: -1}" +
            "&showImdbId=${Uri.encode(showImdbId.orEmpty())}" +
            "&fallbackUrl=${Uri.encode(fallbackUrl)}" +
            "&startPositionMs=$startPositionMs" +
            "&autoSourceSelection=$autoSourceSelection" +
            "&episodeName=${Uri.encode(episodeName)}"
    }
}

val tvTopDestinations = listOf(
    TvTopDestination(TvRoutes.HOME, R.string.nav_home, Icons.Filled.Home),
    TvTopDestination(TvRoutes.MOVIES, R.string.nav_movies, Icons.Filled.Movie),
    TvTopDestination(TvRoutes.SHOWS, R.string.nav_tv_shows, Icons.Filled.Tv),
    TvTopDestination(TvRoutes.SEARCH, R.string.tv_nav_search, Icons.Filled.Search),
    TvTopDestination(TvRoutes.IPTV, R.string.tv_nav_iptv, Icons.Filled.LiveTv),
    TvTopDestination(TvRoutes.SPORTS, R.string.tv_nav_sports, Icons.Filled.SportsSoccer),
    TvTopDestination(TvRoutes.LIBRARY, R.string.tv_nav_library, Icons.Filled.Bookmark),
    TvTopDestination(TvRoutes.SETTINGS, R.string.tv_nav_settings, Icons.Filled.Settings),
)
