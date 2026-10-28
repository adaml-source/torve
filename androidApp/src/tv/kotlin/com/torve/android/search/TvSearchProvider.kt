package com.torve.android.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.auth.UserIdProvider
import com.torve.android.launcher.launcherOwner
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.*
import org.koin.java.KoinJavaComponent.getKoin

class TvSearchProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = SearchManager.SUGGEST_MIME_TYPE
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(BaseColumns._ID, SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2, SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR, SearchManager.SUGGEST_COLUMN_DURATION,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE, SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA))
        if (uri.pathSegments.firstOrNull() != SearchManager.SUGGEST_URI_PATH_QUERY) return cursor
        val query = (selectionArgs?.firstOrNull() ?: uri.lastPathSegment.orEmpty()).trim().take(160)
        if (query.length < 2 || query == SearchManager.SUGGEST_URI_PATH_QUERY) return cursor
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeout(2500) {
                    val koin = getKoin()
                    val settings = koin.get<SettingsViewModel>().state.value
                    // The optional catalogue must be enabled before making any metadata request.
                    if (!settings.tmdbExtensionsLoaded || !settings.tmdbCatalogueEnabled) return@withTimeout
                    val ids = koin.get<UserIdProvider>()
                    val owner = ids.currentDataUserId()
                    val filter = koin.get<ContentPolicyFilter>()
                    filter.awaitProfileBoundary()
                    val policy = koin.get<ContentPolicyRepository>().state.value
                    if (filter.isSensitiveQuery(query) && policy.isLocked) return@withTimeout
                    val metadata = koin.get<MetadataRepository>()
                    val items = metadata.searchMulti(query).take(8).mapNotNull { candidate ->
                        if (candidate.adult != null) candidate else {
                            val id = candidate.tmdbId ?: candidate.id.extractTmdbIdOrNull()
                            if (id == null) null else withTimeoutOrNull(250) {
                                runCatching { metadata.getDetail(if (candidate.type == MediaType.SERIES) "tv" else "movie", id) }.getOrNull()
                            }
                        }
                    }
                    if (owner != ids.currentDataUserId()) return@withTimeout
                    filter.filterItems(policy, ContentAccessContext.SEARCH_SUGGESTION, items,
                        ContentSourceType.TMDB, strictUnknown = true).items.take(12).forEachIndexed { index, item ->
                        val id = item.tmdbId ?: item.id.extractTmdbIdOrNull() ?: return@forEachIndexed
                        val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                        val link = Uri.Builder().scheme("https").authority("torve.app").path("/app/watch")
                            .appendQueryParameter("id", id.toString()).appendQueryParameter("type", type)
                            .appendQueryParameter("owner", launcherOwner(owner)).appendQueryParameter("origin", "search").build()
                        cursor.addRow(arrayOf<Any?>(index, item.title, item.overview, item.posterUrl, item.year,
                            item.runtime?.toLong()?.times(60_000L) ?: 0L,
                            if (item.type == MediaType.SERIES) "vnd.android.cursor.item/tv" else "video/*",
                            android.content.Intent.ACTION_VIEW, link.toString()))
                    }
                }
            }
        }
        return cursor
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
