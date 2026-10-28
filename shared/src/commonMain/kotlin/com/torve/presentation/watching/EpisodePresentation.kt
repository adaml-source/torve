package com.torve.presentation.watching

import com.torve.domain.model.Episode

const val KEY_HIDE_UNWATCHED_SPOILERS = "hide_unwatched_episode_spoilers"

/** Keep the episode identity and air date usable without exposing story details. */
fun Episode.forPresentation(hideSpoilers: Boolean, watched: Boolean): Episode =
    if (hideSpoilers && !watched) copy(name = "Episode $episodeNumber", overview = "", stillUrl = null, rating = 0.0)
    else this
