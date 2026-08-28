package com.torve.android.tv.screens

import com.torve.data.usenet.NewznabItem
import com.torve.domain.sports.SportBucket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val SPORTS_FILTER_ALL = "all"
internal const val SPORTS_FILTER_TODAY = "today"
internal const val SPORTS_FILTER_RECENT = "recent"

internal enum class TvSportsRefreshKind {
    ALL,
    TODAY,
    RECENT,
    BUCKET,
}

internal data class TvSportsRefreshPlan(
    val scopeId: String,
    val kind: TvSportsRefreshKind,
    val bucket: SportBucket? = null,
    val remoteQuery: String? = null,
    val maxAgeDays: Int? = null,
    val maxItems: Int = 200,
)

internal data class TvSportsEventInteraction(
    val canFocus: Boolean,
    val acceptsActivation: Boolean,
)

internal fun tvSportsEventInteraction(
    torboxConfigured: Boolean,
    isWorking: Boolean,
): TvSportsEventInteraction = TvSportsEventInteraction(
    canFocus = true,
    acceptsActivation = torboxConfigured && !isWorking,
)

internal fun tvSportsRefreshJobKey(pageKey: String, scopeId: String): String =
    "$pageKey:refresh:$scopeId"

internal fun tvSportsRefreshPlan(
    selectedMode: String,
    userQuery: String,
): TvSportsRefreshPlan {
    val normalizedQuery = userQuery.trim().takeIf { it.isNotEmpty() }
    return when (selectedMode) {
        SPORTS_FILTER_ALL -> TvSportsRefreshPlan(
            scopeId = SPORTS_FILTER_ALL,
            kind = TvSportsRefreshKind.ALL,
            remoteQuery = normalizedQuery,
        )
        SPORTS_FILTER_TODAY -> TvSportsRefreshPlan(
            scopeId = SPORTS_FILTER_TODAY,
            kind = TvSportsRefreshKind.TODAY,
            remoteQuery = normalizedQuery,
            maxAgeDays = 1,
            maxItems = 100,
        )
        SPORTS_FILTER_RECENT -> TvSportsRefreshPlan(
            scopeId = SPORTS_FILTER_RECENT,
            kind = TvSportsRefreshKind.RECENT,
            remoteQuery = normalizedQuery,
            maxItems = 80,
        )
        else -> {
            val bucket = SportBucket.entries.firstOrNull { it.name == selectedMode }
                ?: return tvSportsRefreshPlan(SPORTS_FILTER_ALL, userQuery)
            val bucketQuery = sportsRemoteQuery(bucket)
            TvSportsRefreshPlan(
                scopeId = bucket.name,
                kind = TvSportsRefreshKind.BUCKET,
                bucket = bucket,
                remoteQuery = listOfNotNull(normalizedQuery, bucketQuery)
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() },
            )
        }
    }
}

internal fun mergeTvSportsRefresh(
    existing: List<NewznabItem>,
    fetched: List<NewznabItem>,
    plan: TvSportsRefreshPlan,
): List<NewznabItem> {
    if (plan.kind == TvSportsRefreshKind.ALL) return fetched.distinctBy(NewznabItem::sportsStableId)

    val retained = if (plan.kind == TvSportsRefreshKind.BUCKET && plan.bucket != null) {
        existing.filterNot { SportBucket.classify(it.title) == plan.bucket }
    } else {
        existing
    }
    val scopedFetched = if (plan.kind == TvSportsRefreshKind.BUCKET && plan.bucket != null) {
        fetched.filter { SportBucket.classify(it.title) == plan.bucket }
    } else {
        fetched
    }
    return (scopedFetched + retained)
        .distinctBy(NewznabItem::sportsStableId)
        .sortedByDescending(NewznabItem::sportsPublishedAtMillis)
        .take(400)
}

internal fun sportsRemoteQuery(bucket: SportBucket): String = when (bucket) {
    SportBucket.F1 -> "Formula 1"
    SportBucket.MMA -> "UFC"
    SportBucket.BOXING -> "Boxing"
    SportBucket.WRESTLING -> "WWE"
    SportBucket.AMERICAN_FOOTBALL -> "NFL"
    SportBucket.BASKETBALL -> "NBA"
    SportBucket.BASEBALL -> "MLB"
    SportBucket.SOCCER -> "Premier League"
    SportBucket.HOCKEY -> "NHL"
    SportBucket.TENNIS -> "Tennis"
    SportBucket.GOLF -> "Golf"
    SportBucket.CRICKET -> "Cricket"
    SportBucket.RUGBY -> "Rugby"
    SportBucket.OTHER -> "Sports"
}

internal fun NewznabItem.sportsStableId(): String = guid?.takeIf { it.isNotBlank() }
    ?: "nzb_${nzbUrl.hashCode().toUInt().toString(16)}"

private fun NewznabItem.sportsPublishedAtMillis(): Long = runCatching {
    ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
}.getOrDefault(Long.MIN_VALUE)
