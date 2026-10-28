package com.torve.android.tv.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.community.parityLabel

private val tvSecondsValue = Regex("^(\\d+) seconds$")
private val tvRunningChecks = Regex("^(\\d+) checks running$")

/** Resolve grouped TV settings labels through the same localized resources as the rest of TV. */
@Composable
internal fun tvSettingsLabel(value: String): String {
    tvSecondsValue.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
        return stringResource(R.string.tv_audit_seconds, it)
    }
    tvRunningChecks.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
        return stringResource(R.string.tv_audit_running_checks, it)
    }
    val resource = when (value) {
        "Usenet & NZB indexers" -> R.string.usenet_setup_menu
        "News server, indexers and download client" -> R.string.usenet_setup_summary
        "Account" -> R.string.tv_settings_category_account
        "Sources" -> R.string.tv_grouped_sources
        "Playback" -> R.string.tv_settings_category_playback
        "Language" -> R.string.tv_settings_language
        "Reduce motion" -> R.string.tv_settings_reduce_motion
        "On" -> R.string.common_on
        "Off" -> R.string.common_off
        "Library & sync" -> R.string.tv_grouped_library_sync
        "Help & advanced" -> R.string.tv_grouped_help_advanced
        "Theme & language" -> R.string.tv_grouped_theme_language
        "Hide watched titles in discovery" -> R.string.tv_grouped_hide_watched
        "Account & devices" -> R.string.tv_grouped_account_devices
        "Sign-in, profiles and devices" -> R.string.tv_grouped_account_summary
        "Playback, tracking and connected libraries" -> R.string.tv_grouped_sources_summary
        "Video, audio, subtitles and episodes" -> R.string.tv_grouped_playback_summary
        "Display, cards and Home layout" -> R.string.tv_grouped_appearance_summary
        "Lists, collections and backups" -> R.string.tv_grouped_library_summary
        "Connection tests, AI Search and support" -> R.string.tv_grouped_help_summary
        "Sign-in and paired devices" -> R.string.tv_grouped_devices_summary
        "Manage your household" -> R.string.tv_grouped_profiles_summary
        "Display mode and app language" -> R.string.tv_grouped_display_summary
        "System" -> R.string.tv_grouped_system_theme
        "Streaming & extensions" -> R.string.tv_grouped_menu_streaming_extensions
        "Sports Replays" -> R.string.tv_grouped_menu_sports_replays
        "Streaming services" -> R.string.tv_grouped_menu_streaming_services
        "Tracking accounts" -> R.string.tv_grouped_menu_tracking_accounts
        "Media servers & requests" -> R.string.tv_grouped_menu_servers_requests
        "Saved collections" -> R.string.tv_grouped_menu_saved_collections
        "App features" -> R.string.tv_grouped_menu_app_features
        "Connection tests" -> R.string.tv_grouped_menu_connection_tests
        "About & help" -> R.string.tv_grouped_menu_about_help
        "Additional sources" -> R.string.tv_grouped_menu_additional_sources
        "Kodi devices" -> R.string.tv_grouped_menu_kodi_devices
        "Video & sources" -> R.string.tv_grouped_menu_video_sources
        "Audio & subtitles" -> R.string.tv_grouped_menu_audio_subtitles
        "Advanced playback" -> R.string.tv_grouped_menu_advanced_playback
        "Background & spacing" -> R.string.tv_grouped_menu_background_spacing
        "Home & cards" -> R.string.tv_grouped_menu_home_cards
        "Browsing style" -> R.string.tv_grouped_menu_browsing_style
        "Episode badges" -> R.string.tv_grouped_menu_episode_badges
        "Profiles" -> R.string.settings_profiles
        "Episodes" -> R.string.settings_episodes
        "AI Search" -> R.string.tv_grouped_ai_search
        "Cloud backup" -> R.string.premium_cloud_backup
        else -> null
    }
    return (resource ?: auditedTvLabelResources[value])?.let { stringResource(it) }
        ?: auditedTvUiText(value) ?: parityLabel(value)
}
