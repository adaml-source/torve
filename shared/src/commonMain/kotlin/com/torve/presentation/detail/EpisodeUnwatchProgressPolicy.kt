package com.torve.presentation.detail

import com.torve.domain.model.WatchProgress

/** Removing a watched mark must preserve an unfinished episode's resume position. */
internal fun shouldClearEpisodeProgressOnUnwatch(progress: WatchProgress?, season: Int, episode: Int): Boolean =
    progress != null && progress.seasonNumber == season && progress.episodeNumber == episode && progress.isWatched()
