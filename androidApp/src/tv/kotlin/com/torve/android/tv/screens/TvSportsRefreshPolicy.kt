package com.torve.android.tv.screens

import com.torve.data.usenet.NewznabClient
import com.torve.data.usenet.NewznabItem
import com.torve.android.catalog.SportsIndexer
import com.torve.android.catalog.SportsIndexerResult
import com.torve.android.catalog.SportsIndexerResults
import com.torve.android.catalog.fetchSportsIndexers
import com.torve.android.catalog.sportsSourceId
import com.torve.domain.usenet.UsenetIndexerCategoryMap
import com.torve.domain.sports.SportBucket
import com.torve.domain.sports.SportsReleaseDateParser
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal const val SPORTS_FILTER_ALL = "all"
internal const val SPORTS_FILTER_TODAY = "today"
internal const val SPORTS_FILTER_YESTERDAY = "yesterday"
internal const val SPORTS_FILTER_RECENT = "recent"
internal const val SPORTS_FILTER_MONTH = "month"
internal const val SPORTS_FILTER_ANY_DATE = "any_date"
internal const val SPORTS_FILTER_DATE_PREFIX = "date:"
internal const val SPORTS_DEFAULT_RESULT_LIMIT = 200
internal val SPORTS_RESULT_LIMIT_OPTIONS = listOf(200, 500, 1_000, 2_000)
internal val SPORTS_DATE_PRESETS = listOf(
    SPORTS_FILTER_RECENT, SPORTS_FILTER_TODAY, SPORTS_FILTER_YESTERDAY,
    SPORTS_FILTER_MONTH, SPORTS_FILTER_ANY_DATE,
)

internal enum class TvSportsRefreshKind { ALL, TODAY, RECENT, DATE, BUCKET }

internal data class TvSportsRefreshPlan(
    val scopeId: String,
    val kind: TvSportsRefreshKind,
    val bucket: SportBucket? = null,
    val remoteQueries: List<String> = emptyList(),
    val maxAgeDays: Int? = null,
    val maxItems: Int = SPORTS_DEFAULT_RESULT_LIMIT,
    val dateMode: String = SPORTS_FILTER_RECENT,
) {
    val remoteQuery: String? get() = remoteQueries.firstOrNull()
}

internal data class TvSportsEventInteraction(val canFocus: Boolean, val acceptsActivation: Boolean)
internal fun tvSportsEventInteraction(torboxConfigured: Boolean, isWorking: Boolean) =
    TvSportsEventInteraction(canFocus = true, acceptsActivation = torboxConfigured && !isWorking)

internal fun tvSportsRefreshJobKey(pageKey: String, scopeId: String): String = "$pageKey:refresh:$scopeId"

internal fun tvSportsRefreshPlan(
    selectedMode: String,
    userQuery: String,
    requestedMaxItems: Int = SPORTS_DEFAULT_RESULT_LIMIT,
    selectedDateMode: String = SPORTS_FILTER_RECENT,
    today: LocalDate = LocalDate.now(),
): TvSportsRefreshPlan {
    val bucket = SportBucket.entries.firstOrNull { it.name == selectedMode }
    val dateMode = if (selectedMode in SPORTS_DATE_PRESETS || sportsDateFromMode(selectedMode) != null) {
        selectedMode
    } else selectedDateMode
    val period = sportsDateRange(dateMode, today)
    val query = userQuery.trim().takeIf(String::isNotEmpty)
    val specificDate = sportsDateFromMode(dateMode)
    val queries = when {
        specificDate != null -> buildList {
            // maxage alone still returns newer events before this exact day.
            listOf("yyyy.MM.dd", "dd.MM.yyyy").forEach { pattern ->
                add(listOfNotNull(query, specificDate.format(DateTimeFormatter.ofPattern(pattern))).joinToString(" "))
            }
            query?.let(::add)
        }
        query != null -> listOf(query) // Never turn "EuroLeague" into "EuroLeague NBA".
        bucket != null -> sportsRemoteQueries(bucket)
        else -> emptyList()
    }.distinct()
    return TvSportsRefreshPlan(
        scopeId = "${bucket?.name ?: SPORTS_FILTER_ALL}:$dateMode:${query.orEmpty().hashCode()}",
        kind = when {
            bucket != null -> TvSportsRefreshKind.BUCKET
            specificDate != null -> TvSportsRefreshKind.DATE
            selectedMode == SPORTS_FILTER_ALL -> TvSportsRefreshKind.ALL
            dateMode == SPORTS_FILTER_TODAY -> TvSportsRefreshKind.TODAY
            dateMode == SPORTS_FILTER_RECENT -> TvSportsRefreshKind.RECENT
            else -> TvSportsRefreshKind.ALL
        },
        bucket = bucket,
        remoteQueries = queries,
        maxAgeDays = period?.first?.let { (ChronoUnit.DAYS.between(it, today) + 1).toInt().coerceAtLeast(1) },
        maxItems = normalizeSportsResultLimit(requestedMaxItems),
        dateMode = dateMode,
    )
}

internal suspend fun fetchTvSportsRefresh(
    client: NewznabClient,
    indexers: List<SportsIndexer>,
    plan: TvSportsRefreshPlan,
    onResult: (SportsIndexerResult) -> Unit = {},
): SportsIndexerResults = fetchSportsIndexers(indexers, onResult = onResult) { indexer ->
    fetchTvSportsRefresh(client, indexer.url, indexer.apiKey,
        UsenetIndexerCategoryMap.sportsCategoriesFor(indexer.type), plan,
        searchCategories = UsenetIndexerCategoryMap.sportsSearchCategoriesFor(indexer.type))
}

internal suspend fun fetchTvSportsRefresh(
    client: NewznabClient,
    indexerUrl: String,
    indexerKey: String,
    categories: String,
    plan: TvSportsRefreshPlan,
    searchCategories: String = categories,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): List<NewznabItem> {
    if (plan.remoteQueries.isEmpty()) {
        return client.browseAllPages(indexerUrl, indexerKey, categories, plan.maxItems,
            maxAgeDays = plan.maxAgeDays, onProgress = onProgress)
    }
    val collected = mutableListOf<NewznabItem>()
    if (plan.bucket != null && plan.remoteQueries.size > 1) {
        // The category feed also catches releases named after teams/fighters,
        // rather than a league. Keep it bounded alongside the targeted searches.
        collected += client.browseAllPages(indexerUrl, indexerKey, categories,
            maxItems = minOf(100, plan.maxItems), maxAgeDays = plan.maxAgeDays)
    }
    // A busy NBA feed must not consume the allowance before EuroLeague is searched.
    val perQuery = (plan.maxItems + plan.remoteQueries.size - 1) / plan.remoteQueries.size
    for (query in plan.remoteQueries) {
        val queryCategories = if (plan.bucket != null || SportBucket.classify(query) != SportBucket.OTHER) {
            searchCategories
        } else categories
        collected += client.searchAllPages(indexerUrl, indexerKey, queryCategories, query,
            maxItems = perQuery, maxAgeDays = plan.maxAgeDays)
        onProgress(collected.distinctBy(NewznabItem::sportsStableId).size.coerceAtMost(plan.maxItems), plan.maxItems)
    }
    return collected.distinctBy(NewznabItem::sportsStableId)
}

internal fun mergeTvSportsRefresh(
    existing: List<NewznabItem>, fetched: List<NewznabItem>, plan: TvSportsRefreshPlan,
): List<NewznabItem> {
    val scoped = fetched.filter { item ->
        SportBucket.isSportsRelease(item.title, item.categoryId) &&
            (plan.bucket == null || SportBucket.classify(item.title) == plan.bucket)
    }.sortedForSports().distinctBy(NewznabItem::sportsStableId).take(plan.maxItems)
    if (plan.bucket == null) return scoped
    // Keep a full allowance for the selected category, separate from other caches.
    return scoped + existing.filter {
        SportBucket.classify(it.title) != plan.bucket && SportBucket.isSportsRelease(it.title, it.categoryId)
    }.sortedForSports().take(plan.maxItems)
}

internal fun normalizeSportsResultLimit(value: Int): Int =
    value.takeIf(SPORTS_RESULT_LIMIT_OPTIONS::contains) ?: SPORTS_DEFAULT_RESULT_LIMIT
internal fun nextSportsResultLimit(value: Int): Int = SPORTS_RESULT_LIMIT_OPTIONS[
    (SPORTS_RESULT_LIMIT_OPTIONS.indexOf(normalizeSportsResultLimit(value)) + 1) % SPORTS_RESULT_LIMIT_OPTIONS.size
]

internal fun sportsDateMode(date: LocalDate): String = "$SPORTS_FILTER_DATE_PREFIX$date"
internal fun sportsDateFromMode(mode: String): LocalDate? = mode
    .takeIf { it.startsWith(SPORTS_FILTER_DATE_PREFIX) }?.removePrefix(SPORTS_FILTER_DATE_PREFIX)
    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun sportsDateRange(mode: String, today: LocalDate): Pair<LocalDate, LocalDate>? = when (mode) {
    SPORTS_FILTER_TODAY -> today to today
    SPORTS_FILTER_YESTERDAY -> today.minusDays(1) to today.minusDays(1)
    SPORTS_FILTER_RECENT -> today.minusDays(6) to today
    SPORTS_FILTER_MONTH -> today.minusDays(29) to today
    SPORTS_FILTER_ANY_DATE -> null
    else -> sportsDateFromMode(mode)?.let { it to it } ?: (today.minusDays(6) to today)
}

internal fun sportsMatchesDate(item: NewznabItem, mode: String, today: LocalDate): Boolean {
    val period = sportsDateRange(mode, today) ?: return true
    if (sportsEventDate(item.title) == null &&
        (SportsReleaseDateParser.seasonEndYear(item.title)?.let { it < period.first.year } == true)) return false
    val date = item.sportsEffectiveDate() ?: return false
    return !date.isBefore(period.first) && !date.isAfter(period.second)
}

internal fun sportsEventDate(title: String): LocalDate? {
    return SportsReleaseDateParser.eventDate(title)?.let { LocalDate.of(it.year, it.monthNumber, it.dayOfMonth) }
}

internal fun NewznabItem.sportsEffectiveDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    sportsEventDate(title) ?: runCatching {
        ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(zone).toLocalDate()
    }.getOrNull()

internal fun sportsDateChipLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
internal fun sportsDateFilterLabel(mode: String): String = when (mode) {
    SPORTS_FILTER_TODAY -> "Today"
    SPORTS_FILTER_YESTERDAY -> "Yesterday"
    SPORTS_FILTER_RECENT -> "Last 7 days"
    SPORTS_FILTER_MONTH -> "Last 30 days"
    SPORTS_FILTER_ANY_DATE -> "Any date"
    else -> sportsDateFromMode(mode)?.let(::sportsDateChipLabel) ?: "Last 7 days"
}

internal fun List<NewznabItem>.sortedForSports(): List<NewznabItem> = sortedWith(
    compareByDescending<NewznabItem> { item ->
        sportsEventDate(item.title)?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: item.sportsPublishedAtMillis()
    }.thenByDescending { it.sportsPublishedAtMillis() },
)

internal fun sportsRemoteQueries(bucket: SportBucket): List<String> = when (bucket) {
    SportBucket.F1 -> listOf("Formula 1", "F1")
    SportBucket.MMA -> listOf("UFC", "MMA", "Bellator", "PFL", "ONE Championship", "Rizin")
    SportBucket.BOXING -> listOf("Boxing")
    SportBucket.WRESTLING -> listOf("WWE", "AEW", "NJPW", "Wrestling")
    SportBucket.AMERICAN_FOOTBALL -> listOf("NFL", "NCAAF", "NCAA Football")
    SportBucket.BASKETBALL -> listOf("Euroleague", "Euro League", "Eurocup", "Basketball", "NBA", "WNBA", "FIBA", "NCAAB")
    SportBucket.BASEBALL -> listOf("MLB", "Baseball")
    SportBucket.SOCCER -> listOf("Soccer", "UEFA", "Premier League", "La Liga", "Bundesliga", "Serie A", "Ligue 1", "MLS")
    SportBucket.HOCKEY -> listOf("NHL", "Hockey", "IIHF")
    SportBucket.TENNIS -> listOf("Tennis", "ATP", "WTA", "Wimbledon")
    SportBucket.GOLF -> listOf("Golf", "PGA")
    SportBucket.CRICKET -> listOf("Cricket", "IPL", "ICC")
    SportBucket.RUGBY -> listOf("Rugby")
    SportBucket.OTHER -> emptyList()
}

internal fun NewznabItem.sportsStableId(): String = sportsSourceId()
private fun NewznabItem.sportsPublishedAtMillis(): Long = runCatching {
    ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
}.getOrDefault(Long.MIN_VALUE)
