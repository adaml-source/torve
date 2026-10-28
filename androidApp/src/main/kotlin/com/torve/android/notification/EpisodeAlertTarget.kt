package com.torve.android.notification

import android.content.Intent

data class EpisodeAlertTarget(val person: String, val showId: Int, val season: Int, val episode: Int) {
    companion object {
        const val PERSON = "episode_alert_person"
        const val SHOW = "episode_alert_show"
        const val SEASON = "episode_alert_season"
        const val EPISODE = "episode_alert_episode"
        fun from(intent: Intent?): EpisodeAlertTarget? {
            val person = intent?.getStringExtra(PERSON)?.takeIf { it.isNotBlank() } ?: return null
            val show = intent.getIntExtra(SHOW, -1)
            val season = intent.getIntExtra(SEASON, -1)
            val episode = intent.getIntExtra(EPISODE, -1)
            return if (show > 0 && season > 0 && episode > 0) EpisodeAlertTarget(person, show, season, episode) else null
        }
    }
}
