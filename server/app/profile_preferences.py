"""Nonsecret per-profile settings supported by the shared app sync contract."""
import json
from fastapi import HTTPException

PREFERENCE_SCHEMA = [
    {
        "key": "addon_shelf_visibility",
        "label": "Addon shelf visibility",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "ai_provider",
        "label": "Ai provider",
        "group": "Sources & search",
        "type": "select",
        "options": [
            "CHATGPT",
            "GEMINI",
            "CLAUDE",
            "PERPLEXITY",
            "DEEPSEEK"
        ]
    },
    {
        "key": "allow_4k_auto",
        "label": "Allow 4k auto",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "allow_subtitle_language_fallback",
        "label": "Allow subtitle language fallback",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "app_language",
        "label": "App language",
        "group": "Language & region",
        "type": "select",
        "options": [
            "ENGLISH",
            "GERMAN",
            "SPANISH",
            "FRENCH",
            "ITALIAN",
            "PORTUGUESE",
            "TURKISH"
        ]
    },
    {
        "key": "auto_play_enabled",
        "label": "Start playback automatically",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "auto_play_next_episode",
        "label": "Auto play next episode",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "auto_source_mode",
        "label": "Auto source mode",
        "group": "Playback",
        "type": "select",
        "options": [
            "BALANCED",
            "STABILITY_FIRST",
            "QUALITY_FIRST",
            "MAX_1080P",
            "MAX_720P"
        ]
    },
    {
        "key": "card_prefs",
        "label": "Card prefs",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "card_style_default_preset_id",
        "label": "Card style default preset id",
        "group": "Layout & ratings",
        "type": "text"
    },
    {
        "key": "card_style_presets",
        "label": "Card style presets",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "catalog_groups_v1",
        "label": "Catalog groups v1",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "codec_preference",
        "label": "Codec preference",
        "group": "Playback",
        "type": "select",
        "options": [
            "HEVC_PREFERRED",
            "H264_ONLY",
            "ANY"
        ]
    },
    {
        "key": "connection_alert_muted_categories",
        "label": "Connection alert muted categories",
        "group": "Notifications",
        "type": "json"
    },
    {
        "key": "connection_alerts_enabled",
        "label": "Connection alerts enabled",
        "group": "Notifications",
        "type": "boolean"
    },
    {
        "key": "content_region_code",
        "label": "Content region (country code)",
        "group": "Language & region",
        "type": "text"
    },
    {
        "key": "debrid_provider",
        "label": "Debrid provider",
        "group": "Sources & search",
        "type": "select",
        "options": [
            "TORBOX",
            "REAL_DEBRID",
            "ALL_DEBRID",
            "PREMIUMIZE"
        ]
    },
    {
        "key": "dedupe_results",
        "label": "Dedupe results",
        "group": "Sources & search",
        "type": "boolean"
    },
    {
        "key": "enabled_streaming_services",
        "label": "Enabled streaming services",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "hdr_mode",
        "label": "Hdr mode",
        "group": "Playback",
        "type": "select",
        "options": [
            "PREFER_HDR",
            "SDR_ONLY",
            "AUTO"
        ]
    },
    {
        "key": "home_layout_order",
        "label": "Home layout order",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "home_layout_source",
        "label": "Home layout source",
        "group": "Layout & ratings",
        "type": "text"
    },
    {
        "key": "home_section_configs",
        "label": "Home section configs",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "mdblist_shelves",
        "label": "Mdblist shelves",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "movies_hero_enabled",
        "label": "Movies hero enabled",
        "group": "Layout & ratings",
        "type": "boolean"
    },
    {
        "key": "next_episode_mode",
        "label": "Next episode mode",
        "group": "Playback",
        "type": "select",
        "options": [
            "AT_END",
            "AT_CREDITS",
            "OFF"
        ]
    },
    {
        "key": "next_episode_preload_buffer_seconds",
        "label": "Next episode preload buffer seconds",
        "group": "Playback",
        "type": "number"
    },
    {
        "key": "next_episode_preload_max_mb",
        "label": "Next episode preload max mb",
        "group": "Playback",
        "type": "number"
    },
    {
        "key": "next_episode_preload_wifi_only",
        "label": "Next episode preload wifi only",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "next_episode_preparation_mode",
        "label": "Next episode preparation mode",
        "group": "Playback",
        "type": "select",
        "options": [
            "OFF",
            "RESOLVE_ONLY",
            "RESOLVE_AND_BUFFER"
        ]
    },
    {
        "key": "prefer_compatible_codecs",
        "label": "Prefer compatible codecs",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "preferred_audio_language",
        "label": "Audio language (language code)",
        "group": "Language & region",
        "type": "text"
    },
    {
        "key": "preferred_subtitle_language",
        "label": "Subtitle language (language code)",
        "group": "Language & region",
        "type": "text"
    },
    {
        "key": "rail_spacing_dp",
        "label": "Rail spacing dp",
        "group": "Layout & ratings",
        "type": "number"
    },
    {
        "key": "rating_display_prefs",
        "label": "Rating display prefs",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "recording_default_duration_min",
        "label": "Recording default duration min",
        "group": "Recording",
        "type": "number"
    },
    {
        "key": "recording_max_concurrent",
        "label": "Recording max concurrent",
        "group": "Recording",
        "type": "number"
    },
    {
        "key": "recording_post_roll_min",
        "label": "Recording post roll min",
        "group": "Recording",
        "type": "number"
    },
    {
        "key": "recording_pre_roll_min",
        "label": "Recording pre roll min",
        "group": "Recording",
        "type": "number"
    },
    {
        "key": "regex_patterns",
        "label": "Regex patterns",
        "group": "Sources & search",
        "type": "json"
    },
    {
        "key": "saved_media_collections_v1",
        "label": "Saved media collections v1",
        "group": "Layout & ratings",
        "type": "json"
    },
    {
        "key": "seek_step_seconds",
        "label": "Seek step seconds",
        "group": "Playback",
        "type": "number"
    },
    {
        "key": "show_remaining_episodes",
        "label": "Show remaining episode count",
        "group": "Layout & ratings",
        "type": "boolean"
    },
    {
        "key": "shows_hero_enabled",
        "label": "Shows hero enabled",
        "group": "Layout & ratings",
        "type": "boolean"
    },
    {
        "key": "source_fallback_policy",
        "label": "Source fallback policy",
        "group": "Playback",
        "type": "select",
        "options": [
            "ASK",
            "DO_NOT_AUTOPLAY",
            "BEST_AVAILABLE"
        ]
    },
    {
        "key": "source_language_match_mode",
        "label": "Source language match mode",
        "group": "Playback",
        "type": "select",
        "options": [
            "PREFER",
            "REQUIRE"
        ]
    },
    {
        "key": "stream_cached_only",
        "label": "Cached sources only",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "stream_groups",
        "label": "Stream groups",
        "group": "Sources & search",
        "type": "json"
    },
    {
        "key": "stream_hdr_enabled",
        "label": "Stream hdr enabled",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "stream_max_file_size_mb",
        "label": "Stream max file size mb",
        "group": "Playback",
        "type": "number"
    },
    {
        "key": "stream_max_quality",
        "label": "Maximum video quality",
        "group": "Playback",
        "type": "select",
        "options": [
            "UNKNOWN",
            "REMUX_4K",
            "UHD_4K",
            "FHD_1080P",
            "HD_720P",
            "SD_480P"
        ]
    },
    {
        "key": "stream_min_quality",
        "label": "Minimum video quality",
        "group": "Playback",
        "type": "select",
        "options": [
            "UNKNOWN",
            "REMUX_4K",
            "UHD_4K",
            "FHD_1080P",
            "HD_720P",
            "SD_480P"
        ]
    },
    {
        "key": "stream_min_source_size_per_hour_mb",
        "label": "Stream min source size per hour mb",
        "group": "Playback",
        "type": "number"
    },
    {
        "key": "subtitles_enabled_default",
        "label": "Subtitles enabled default",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "theme_mode",
        "label": "Appearance",
        "group": "Layout & ratings",
        "type": "select",
        "options": [
            "SYSTEM",
            "LIGHT",
            "DARK"
        ]
    },
    {
        "key": "trakt_scrobble_enabled",
        "label": "Send watch progress to Trakt",
        "group": "Playback",
        "type": "boolean"
    },
    {
        "key": "unknown_source_language_policy",
        "label": "Unknown source language policy",
        "group": "Playback",
        "type": "select",
        "options": [
            "ALLOW_WITH_PENALTY",
            "REJECT"
        ]
    },
    {
        "key": "unknown_source_size_policy",
        "label": "Unknown source size policy",
        "group": "Playback",
        "type": "select",
        "options": [
            "ALLOW_WITH_PENALTY",
            "REJECT"
        ]
    }
]
SHARED_KEYS = frozenset(field["key"] for field in PREFERENCE_SCHEMA)

def remote_key(profile, key):
    if profile == "default": return "language" if key == "app_language" else key
    return f"profilepref_v1:{profile}:{key}"

def read_preferences(settings, profile):
    result = {}
    for field in PREFERENCE_SCHEMA:
        key = field["key"]
        value = settings.get(remote_key(profile, key))
        if profile == "default" and key == "app_language" and value is None: value = settings.get("app_language")
        if isinstance(value, str): result[key] = value
    return result

def validate_preferences(values):
    for key, value in values.items():
        field = next((f for f in PREFERENCE_SCHEMA if f["key"] == key), None)
        if field is None or not isinstance(value, str) or len(value) > 131072:
            raise HTTPException(422, "Unsupported profile preference")
        kind = field["type"]
        if kind == "boolean" and value not in ("true", "false"): raise HTTPException(422, "Choose Yes or No")
        if kind == "number":
            try:
                if not 0 <= int(value) <= 10000000: raise ValueError()
            except ValueError: raise HTTPException(422, "Enter a valid non-negative number")
        if kind == "json":
            try: parsed = json.loads(value)
            except (ValueError, TypeError): raise HTTPException(422, "Invalid structured preference")
            if not isinstance(parsed, (dict, list)): raise HTTPException(422, "Use an object or list for structured preferences")
        # Preserve enum values from newer app releases when they are already
        # stored; the portal only offers known choices for new selections.
        if kind == "select" and value not in field["options"]:
            if key != "app_language" or value not in ("en", "de", "es", "fr", "it", "pt", "tr"):
                raise HTTPException(422, "Unsupported preference choice")
