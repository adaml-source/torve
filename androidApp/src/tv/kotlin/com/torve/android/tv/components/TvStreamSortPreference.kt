package com.torve.android.tv.components

import android.content.SharedPreferences
import com.torve.data.addon.StreamSortMode

private const val TV_STREAM_SORT_PREF_KEY = "tv_stream_sort_v1"

internal fun SharedPreferences.readTvStreamSortMode(): StreamSortMode =
    StreamSortMode.entries.firstOrNull { it.name == getString(TV_STREAM_SORT_PREF_KEY, null) }
        ?: StreamSortMode.DEFAULT

internal fun SharedPreferences.writeTvStreamSortMode(mode: StreamSortMode) {
    edit().putString(TV_STREAM_SORT_PREF_KEY, mode.name).apply()
}
