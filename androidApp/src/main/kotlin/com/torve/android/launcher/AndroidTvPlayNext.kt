package com.torve.android.launcher

import android.content.*
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import app.cash.sqldelight.coroutines.asFlow
import com.torve.android.BuildConfig
import com.torve.data.auth.*
import com.torve.db.TorveDatabase
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.java.KoinJavaComponent.getKoin

/** Publishes on supported Android TV launchers; Amazon devices are excluded. */
object AndroidTvPlayNext {
    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope) {
        if (!BuildConfig.FLAVOR.contains("google", true) || !BuildConfig.FLAVOR.contains("tv", true) || Build.VERSION.SDK_INT < 26) return
        if (context.packageManager.resolveContentProvider(TvContract.AUTHORITY, 0) == null) return
        val koin = getKoin(); val ids = koin.get<UserIdProvider>(); val auth = koin.get<AuthClient>()
        val database = koin.get<TorveDatabase>(); val progress = koin.get<WatchProgressRepository>()
        scope.launch {
            auth.authUserFlow.collectLatest { user ->
                if (user == null) { runCatching { publish(context, emptyList()) }; return@collectLatest }
                var owner: String? = null
                merge(database.torveQueries.getAllProfiles(user.id).asFlow().map { Unit }, progress.progressChanges)
                    .onStart { emit(Unit) }.onEach {
                        val activeOwner = ids.currentDataUserId()
                        if (owner != activeOwner) {
                            publish(context, emptyList())
                            owner = activeOwner
                        }
                    }.debounce(10_000).collectLatest {
                        try { ids.withPersonalDataScope {
                            val person = ids.currentDataUserId()
                            val profile = koin.get<ProfileRepository>().getActiveProfile()
                            if (profile?.maxContentRating != null || !profile?.pin.isNullOrBlank()) { publish(context, emptyList()); return@withPersonalDataScope }
                            val metadata = koin.get<MetadataRepository>()
                            val policy = koin.get<com.torve.data.contentpolicy.ContentPolicyRepository>()
                            val entries = progress.getInProgress(12).mapNotNull { saved ->
                                if (saved.positionMs <= 0 || saved.progressPercent >= .85f || saved.isContentPlaceholder) return@mapNotNull null
                                val id = saved.mediaId.extractTmdbIdOrNull() ?: return@mapNotNull null
                                val type = if (saved.mediaType == MediaType.SERIES) "tv" else "movie"
                                if (type == "tv" && (saved.seasonNumber == null || saved.episodeNumber == null)) return@mapNotNull null
                                val item = metadata.getDetail(type, id)
                                if (ContentPolicyFilter().filterItems(policy.state.value, ContentAccessContext.HISTORY_DERIVED,
                                    listOf(item), ContentSourceType.TMDB).items.none { !it.isContentPlaceholder && !it.isStubDetail }) return@mapNotNull null
                                val key = "torve-next:${launcherOwner(person)}:$type:$id:${saved.seasonNumber ?: 0}:${saved.episodeNumber ?: 0}"
                                val uri = Uri.Builder().scheme("https").authority("torve.app").path("/app/watch")
                                    .appendQueryParameter("owner", launcherOwner(person)).appendQueryParameter("type", type).appendQueryParameter("id", id.toString())
                                    .apply { saved.seasonNumber?.let { appendQueryParameter("season", it.toString()) }; saved.episodeNumber?.let { appendQueryParameter("episode", it.toString()) } }.build()
                                val intent = Intent(Intent.ACTION_VIEW, uri).setClassName(context.packageName, "com.torve.android.TvMainActivity")
                                Entry(key, item.title, saved, item.backdropUrl ?: item.posterUrl, intent.toUri(Intent.URI_INTENT_SCHEME))
                            }
                            if (person == ids.currentDataUserId()) publish(context, entries)
                        } } catch (error: Exception) { if (error is CancellationException) throw error }
                    }
            }
        }
    }
    private data class Entry(val key: String, val title: String, val progress: WatchProgress, val artwork: String?, val intent: String)
    @androidx.annotation.RequiresApi(26)
    private fun publish(context: Context, desired: List<Entry>) {
        val uri = TvContract.WatchNextPrograms.CONTENT_URI; val resolver = context.contentResolver
        val existing = linkedMapOf<String, Pair<Long, Boolean>>()
        resolver.query(uri, arrayOf("_id", "internal_provider_id", "browsable", "package_name"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(1).orEmpty()
                if (cursor.getString(3) == context.packageName && key.startsWith("torve-next:")) existing[key] = cursor.getLong(0) to (cursor.getInt(2) != 0)
            }
        }
        val preferences = context.getSharedPreferences("torve_launcher", Context.MODE_PRIVATE)
        val known = preferences.getStringSet("published", emptySet()).orEmpty().toMutableSet()
        val wanted = desired.map { it.key }.toSet()
        existing.filterKeys { it !in wanted }.forEach { (key, row) ->
            if (resolver.delete(ContentUris.withAppendedId(uri, row.first), null, null) > 0 && row.second) known -= key
        }
        desired.forEach { item ->
            val old = existing[item.key]
            if (old?.second == false || (old == null && item.key in known)) return@forEach
            val values = ContentValues().apply {
                put("internal_provider_id", item.key); put("content_id", item.key); put("title", item.title)
                put("type", if (item.progress.mediaType == MediaType.SERIES) 3 else 0); put("watch_next_type", 0)
                put("last_engagement_time_utc_millis", item.progress.updatedAt)
                put("last_playback_position_millis", item.progress.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                put("duration_millis", item.progress.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                put("intent_uri", item.intent); item.artwork?.let { put("poster_art_uri", it) }; put("poster_art_aspect_ratio", 0)
                item.progress.seasonNumber?.let { put("season_display_number", it.toString()) }
                item.progress.episodeNumber?.let { put("episode_display_number", it.toString()) }
            }
            val stored = if (old == null) resolver.insert(uri, values) != null else resolver.update(ContentUris.withAppendedId(uri, old.first), values, null, null) > 0
            if (stored) known += item.key
        }
        preferences.edit().putStringSet("published", known.toList().takeLast(500).toSet()).apply()
    }
}
