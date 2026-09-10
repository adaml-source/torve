package com.torve.android.tv.screens

import com.torve.data.usenet.NewznabItem
import com.torve.domain.sports.SportBucket
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val SPORTS_FILTER_ALL = "all"
internal const val SPORTS_FILTER_TODAY = "today"
internal const val SPORTS_FILTER_RECENT = "recent"
internal const val SPORTS_FILTER_DATE_PREFIX = "date:"

private const val SPORTS_RECENT_WINDOW_DAYS = 14
internal const val SPORTS_DEFAULT_RESULT_LIMIT = 200
internal val SPORTS_RESULT_LIMIT_OPTIONS = listOf(200, 500, 1_000, 2_000)

internal enum class TvSportsRefreshKind {
    ALL,
    TODAY,
    RECENT,
    DATE,
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
    requestedMaxItems: Int = SPORTS_DEFAULT_RESULT_LIMIT,
): TvSportsRefreshPlan {
    val normalizedQuery = userQuery.trim().takeIf { it.isNotEmpty() }
    val maxItems = normalizeSportsResultLimit(requestedMaxItems)
    return when (selectedMode) {
        SPORTS_FILTER_ALL -> TvSportsRefreshPlan(
            scopeId = SPORTS_FILTER_ALL,
            kind = TvSportsRefreshKind.ALL,
            remoteQuery = normalizedQuery,
            // Keep free-text search capable of finding older events. The rolling
            // window is for the unfiltered feed, where the newest events matter.
            maxAgeDays = SPORTS_RECENT_WINDOW_DAYS.takeIf { normalizedQuery == null },
            maxItems = maxItems,
        )
        SPORTS_FILTER_TODAY -> TvSportsRefreshPlan(
            scopeId = SPORTS_FILTER_TODAY,
            kind = TvSportsRefreshKind.TODAY,
            remoteQuery = normalizedQuery,
            maxAgeDays = 1,
            maxItems = maxItems,
        )
        SPORTS_FILTER_RECENT -> TvSportsRefreshPlan(
            scopeId = SPORTS_FILTER_RECENT,
            kind = TvSportsRefreshKind.RECENT,
            remoteQuery = normalizedQuery,
            maxAgeDays = SPORTS_RECENT_WINDOW_DAYS,
            maxItems = maxItems,
        )
        else -> {
            sportsDateFromMode(selectedMode)?.let {
                return TvSportsRefreshPlan(
                    scopeId = selectedMode,
                    kind = TvSportsRefreshKind.DATE,
                    remoteQuery = normalizedQuery,
                    maxAgeDays = SPORTS_RECENT_WINDOW_DAYS,
                    maxItems = maxItems,
                )
            }
            val bucket = SportBucket.entries.firstOrNull { it.name == selectedMode }
                ?: return tvSportsRefreshPlan(SPORTS_FILTER_ALL, userQuery, maxItems)
            val bucketQuery = sportsRemoteQuery(bucket)
            TvSportsRefreshPlan(
                scopeId = bucket.name,
                kind = TvSportsRefreshKind.BUCKET,
                bucket = bucket,
                remoteQuery = listOfNotNull(normalizedQuery, bucketQuery)
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() },
                maxItems = maxItems,
            )
        }
    }
}

internal fun mergeTvSportsRefresh(
    existing: List<NewznabItem>,
    fetched: List<NewznabItem>,
    plan: TvSportsRefreshPlan,
): List<NewznabItem> {
    if (plan.kind == TvSportsRefreshKind.ALL || plan.kind == TvSportsRefreshKind.DATE) {
        return fetched
            .sortedForSports()
            .distinctBy(NewznabItem::sportsStableId)
            .take(plan.maxItems)
    }

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
        .sortedForSports()
        .take(plan.maxItems)
}

internal fun normalizeSportsResultLimit(value: Int): Int =
    value.takeIf(SPORTS_RESULT_LIMIT_OPTIONS::contains) ?: SPORTS_DEFAULT_RESULT_LIMIT

internal fun nextSportsResultLimit(value: Int): Int {
    val currentIndex = SPORTS_RESULT_LIMIT_OPTIONS.indexOf(normalizeSportsResultLimit(value))
    return SPORTS_RESULT_LIMIT_OPTIONS[(currentIndex + 1) % SPORTS_RESULT_LIMIT_OPTIONS.size]
}

internal fun sportsDateMode(date: LocalDate): String = "$SPORTS_FILTER_DATE_PREFIX$date"

internal fun sportsDateFromMode(mode: String): LocalDate? = mode
    .takeIf { it.startsWith(SPORTS_FILTER_DATE_PREFIX) }
    ?.removePrefix(SPORTS_FILTER_DATE_PREFIX)
    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun sportsEventDate(title: String): LocalDate? {
    val match = SPORTS_EVENT_DATE_REGEX.find(title) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

internal fun sportsDateChipLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))

private fun List<NewznabItem>.sortedForSports(): List<NewznabItem> =
    sortedWith(
        compareByDescending<NewznabItem> { item ->
            item.sportsRecencyAtMillis()
        }.thenByDescending { item -> item.sportsPublishedAtMillis() },
    )

private fun NewznabItem.sportsRecencyAtMillis(): Long =
    sportsEventDate(title)
        ?.atStartOfDay()
        ?.toInstant(ZoneOffset.UTC)
        ?.toEpochMilli()
        ?: sportsPublishedAtMillis()

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

private val SPORTS_EVENT_DATE_REGEX = Regex("\\b(20\\d{2})[._ -](\\d{2})[._ -](\\d{2})\\b")
