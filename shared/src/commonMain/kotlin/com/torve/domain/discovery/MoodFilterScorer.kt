package com.torve.domain.discovery

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlin.math.abs
import kotlin.math.ln

enum class MoodFilter {
    HiddenGems,
    CriticallyAcclaimed,
    Dark,
    Funny,
    Cinematic,
    FastPaced,
    ComfortWatch,
    Blockbuster,
    EasyWatch,
    DateNight,
    MindBending,
    FamilyNight,
    ;

    companion object {
        fun fromChipId(id: String): MoodFilter? = when (id) {
            "hidden" -> HiddenGems
            "acclaimed" -> CriticallyAcclaimed
            "dark" -> Dark
            "funny" -> Funny
            "cinematic" -> Cinematic
            "fast" -> FastPaced
            "comfort" -> ComfortWatch
            "blockbuster" -> Blockbuster
            "easy" -> EasyWatch
            "date" -> DateNight
            "mind" -> MindBending
            "family" -> FamilyNight
            else -> null
        }
    }
}

data class MoodSelectionState(
    val searchQuery: String = "",
    val isAiSearchEnabled: Boolean = false,
    val selectedMoodId: String = ALL_MOODS_ID,
)

fun applyMoodChipClick(
    state: MoodSelectionState,
    clickedMoodId: String,
): MoodSelectionState = state.copy(
    selectedMoodId = nextSelectedMoodId(state.selectedMoodId, clickedMoodId),
)

fun nextSelectedMoodId(currentMoodId: String, clickedMoodId: String): String =
    if (clickedMoodId == ALL_MOODS_ID || clickedMoodId == currentMoodId) ALL_MOODS_ID else clickedMoodId

class CandidateStats private constructor(
    val candidateCount: Int,
    private val popularityPercentiles: Map<String, Double>,
    private val voteCountPercentiles: Map<String, Double>,
    private val ratingPercentiles: Map<String, Double>,
) {
    fun popularityPercentile(item: MediaItem): Double = popularityPercentiles[item.discoveryKey()] ?: 50.0
    fun voteCountPercentile(item: MediaItem): Double = voteCountPercentiles[item.discoveryKey()] ?: 0.0
    fun ratingPercentile(item: MediaItem): Double = ratingPercentiles[item.discoveryKey()] ?: 50.0

    companion object {
        fun from(candidates: List<MediaItem>): CandidateStats = CandidateStats(
            candidateCount = candidates.size,
            popularityPercentiles = percentiles(candidates) { it.popularity },
            voteCountPercentiles = percentiles(candidates) { it.voteSignal().toDouble().takeIf { votes -> votes > 0 } },
            ratingPercentiles = percentiles(candidates) { it.ratingSignal() },
        )

        private fun percentiles(
            candidates: List<MediaItem>,
            valueOf: (MediaItem) -> Double?,
        ): Map<String, Double> {
            val values = candidates
                .mapNotNull { item -> valueOf(item)?.let { item.discoveryKey() to it } }
                .sortedBy { it.second }
            if (values.isEmpty()) return emptyMap()
            val denominator = (values.size - 1).coerceAtLeast(1)
            return values.mapIndexed { index, (key, _) ->
                key to (index * 100.0 / denominator)
            }.toMap()
        }
    }
}

data class MoodMatchResult(
    val matches: Boolean,
    val score: Double,
    val reason: String? = null,
)

class MoodFilterScorer {
    fun score(
        item: MediaItem,
        mood: MoodFilter,
        stats: CandidateStats,
    ): MoodMatchResult = when (mood) {
        MoodFilter.HiddenGems -> scoreHiddenGem(item, stats)
        MoodFilter.CriticallyAcclaimed -> scoreCriticallyAcclaimed(item)
        MoodFilter.Dark -> scoreDark(item)
        MoodFilter.Funny -> scoreFunny(item)
        MoodFilter.Cinematic -> scoreCinematic(item)
        MoodFilter.FastPaced -> scoreFastPaced(item)
        MoodFilter.ComfortWatch,
        MoodFilter.EasyWatch -> scoreComfortWatch(item)
        MoodFilter.Blockbuster -> scoreBlockbuster(item, stats)
        MoodFilter.DateNight -> scoreDateNight(item)
        MoodFilter.MindBending -> scoreMindBending(item)
        MoodFilter.FamilyNight -> scoreFamilyNight(item)
    }

    private fun scoreHiddenGem(item: MediaItem, stats: CandidateStats): MoodMatchResult {
        val rating = item.ratingSignal()
        val votes = item.voteSignal()
        val ratingThreshold = if (item.type == MediaType.SERIES) 7.0 else 6.8
        val minimumVotes = if (item.type == MediaType.SERIES) 75 else 150
        if (rating == null || rating < ratingThreshold) {
            return MoodMatchResult(false, 0.0, "below quality threshold")
        }
        if (votes < minimumVotes) {
            return MoodMatchResult(false, 0.0, "insufficient vote signal")
        }

        val popularityPercentile = stats.popularityPercentile(item)
        val votePercentile = stats.voteCountPercentile(item)
        val absolutePopularity = item.popularity ?: 0.0
        if (item.isKnownOverexposedTitle()) {
            return MoodMatchResult(false, 0.0, "globally overexposed")
        }
        if (popularityPercentile >= 85.0) {
            return MoodMatchResult(false, 0.0, "top popularity band")
        }
        if (votePercentile >= 95.0 && popularityPercentile >= 50.0) {
            return MoodMatchResult(false, 0.0, "top vote-count band")
        }
        if (votes >= item.veryHighVoteCountThreshold() && (popularityPercentile >= 45.0 || absolutePopularity >= 120.0)) {
            return MoodMatchResult(false, 0.0, "mainstream vote and popularity signal")
        }

        val qualityScore = scoreRange(rating, ratingThreshold, 9.2) * 0.75 + voteConfidence(votes, minimumVotes) * 0.25
        val underseenScore = when {
            popularityPercentile <= 70.0 -> {
                0.72 + (1.0 - abs(popularityPercentile - 35.0) / 35.0).coerceIn(0.0, 1.0) * 0.28
            }
            popularityPercentile < 85.0 -> 0.35
            else -> 0.0
        }
        val freshnessOrAvailabilityScore = when {
            item.year == null -> 0.45
            item.year >= 2015 -> 0.62
            item.year >= 2000 -> 0.52
            else -> 0.42
        }
        val score = qualityScore * 0.55 + underseenScore * 0.35 + freshnessOrAvailabilityScore * 0.10
        return MoodMatchResult(score >= 0.50, score, "quality plus underseen signal")
    }

    private fun scoreCriticallyAcclaimed(item: MediaItem): MoodMatchResult {
        val rating = item.ratingSignal() ?: return MoodMatchResult(false, 0.0, "missing rating")
        val votes = item.voteSignal()
        val threshold = if (item.type == MediaType.SERIES) 7.8 else 7.4
        val minimumVotes = if (item.type == MediaType.SERIES) 100 else 200
        val score = scoreRange(rating, threshold, 9.4) * 0.8 + voteConfidence(votes, minimumVotes) * 0.2
        return MoodMatchResult(rating >= threshold && votes >= minimumVotes, score, "high rating with strong signal")
    }

    private fun scoreCinematic(item: MediaItem): MoodMatchResult {
        val rating = item.ratingSignal() ?: 0.0
        val hasCinematicGenre = item.hasGenreOrText(
            "science fiction",
            "sci-fi",
            "fantasy",
            "adventure",
            "war",
            "history",
            "epic",
            "visual",
            "visually",
            "spectacle",
        )
        val hasPrestigeDrama = item.hasGenreOrText("drama") && rating >= 7.2
        val matches = hasCinematicGenre || hasPrestigeDrama || rating >= 7.8
        val score = (if (hasCinematicGenre) 0.48 else 0.0) +
            (if (hasPrestigeDrama) 0.22 else 0.0) +
            scoreRange(rating, 6.8, 9.2) * 0.40
        return MoodMatchResult(matches, score, "visual or prestige signal")
    }

    private fun scoreDark(item: MediaItem): MoodMatchResult {
        val hasSignal = item.hasGenreOrText(
            "dark",
            "crime",
            "thriller",
            "horror",
            "mystery",
            "noir",
            "psychological",
            "murder",
            "serial killer",
            "dystopian",
        )
        return MoodMatchResult(hasSignal, if (hasSignal) 0.72 else 0.0, "dark genre or text signal")
    }

    private fun scoreFunny(item: MediaItem): MoodMatchResult {
        val hasSignal = item.hasGenreOrText("comedy", "funny", "humor", "humour", "satire", "sitcom", "parody")
        return MoodMatchResult(hasSignal, if (hasSignal) 0.72 else 0.0, "comedy or humor signal")
    }

    private fun scoreFastPaced(item: MediaItem): MoodMatchResult {
        val hasSignal = item.hasGenreOrText(
            "action",
            "adventure",
            "thriller",
            "crime",
            "chase",
            "heist",
            "mission",
            "fight",
            "survival",
        )
        return MoodMatchResult(hasSignal, if (hasSignal) 0.72 else 0.0, "action or momentum signal")
    }

    private fun scoreComfortWatch(item: MediaItem): MoodMatchResult {
        val hasComfortSignal = item.hasGenreOrText(
            "comedy",
            "family",
            "animation",
            "kids",
            "sitcom",
            "feel-good",
            "feel good",
            "cozy",
            "cosy",
            "heartwarming",
            "charming",
            "friendship",
        )
        val harshSignal = item.hasGenreOrText("gore", "slasher", "torture", "brutal", "bleak")
        val matches = hasComfortSignal && !harshSignal
        return MoodMatchResult(matches, if (matches) 0.72 else 0.0, "comfort genre or tone signal")
    }

    private fun scoreBlockbuster(item: MediaItem, stats: CandidateStats): MoodMatchResult {
        val votes = item.voteSignal()
        val popularityPercentile = stats.popularityPercentile(item)
        val matches = item.hasGenreOrText("action", "adventure", "science fiction", "sci-fi") ||
            votes >= 5_000 ||
            popularityPercentile >= 80.0 ||
            (item.popularity ?: 0.0) >= 90.0
        return MoodMatchResult(matches, if (matches) 0.75 else 0.0, "large audience or spectacle signal")
    }

    private fun scoreDateNight(item: MediaItem): MoodMatchResult {
        if (item.type != MediaType.MOVIE) {
            return MoodMatchResult(false, 0.0, "date night is movie-specific")
        }

        val rating = item.ratingSignal()
        val votes = item.voteSignal()
        if (rating != null && rating < 5.2 && votes >= 25) {
            return MoodMatchResult(false, 0.0, "below date-night quality floor")
        }

        val romanceSignal = item.hasGenreOrText(
            "romance",
            "romantic",
            "love",
            "couple",
            "dating",
            "date",
            "marriage",
            "wedding",
            "relationship",
        )
        val warmSignal = item.hasGenreOrText(
            "feel-good",
            "feel good",
            "heartfelt",
            "heartwarming",
            "charming",
            "cozy",
            "cosy",
            "coming-of-age",
            "coming of age",
            "emotional",
        )
        val comedy = item.hasGenreOrText("comedy", "romantic comedy")
        val drama = item.hasGenreOrText("drama")
        val familyOrAnimation = item.hasGenreOrText("family", "animation")
        val musical = item.hasGenreOrText("music", "musical")

        val positiveScore =
            (if (romanceSignal) 0.42 else 0.0) +
                (if (comedy && romanceSignal) 0.24 else 0.0) +
                (if (drama && romanceSignal) 0.16 else 0.0) +
                (if (familyOrAnimation && (warmSignal || romanceSignal)) 0.20 else 0.0) +
                (if (musical && (warmSignal || romanceSignal)) 0.18 else 0.0) +
                (if (comedy && !item.hasHarshDateNightSignal()) 0.30 else 0.0) +
                (if (warmSignal) 0.22 else 0.0)

        val runtimeScore = when (val minutes = item.runtime) {
            null -> 0.04
            in 80..140 -> 0.10
            in 70..160 -> 0.05
            else -> 0.0
        }
        val qualityScore = rating?.let { scoreRange(it, 5.8, 8.2) * 0.14 } ?: 0.07
        val signalPenalty = if (votes in 1..9 && positiveScore < 0.70) 0.22 else 0.0
        val harshPenalty = when {
            item.hasHarshDateNightSignal() && !romanceSignal -> 0.55
            item.hasHarshDateNightSignal() -> 0.22
            else -> 0.0
        }

        val score = positiveScore + runtimeScore + qualityScore - signalPenalty - harshPenalty
        return MoodMatchResult(score >= 0.48, score.coerceAtLeast(0.0), "date-night metadata score")
    }

    private fun scoreMindBending(item: MediaItem): MoodMatchResult {
        val hasSignal = item.hasGenreOrText(
            "mind-bending",
            "mind bending",
            "mystery",
            "science fiction",
            "sci-fi",
            "fantasy",
            "thriller",
            "psychological",
            "time travel",
            "dream",
            "memory",
            "puzzle",
            "reality",
        )
        return MoodMatchResult(hasSignal, if (hasSignal) 0.72 else 0.0, "puzzle or speculative signal")
    }

    private fun scoreFamilyNight(item: MediaItem): MoodMatchResult {
        val familySignal = item.hasGenreOrText("family", "animation", "kids", "children", "friendship", "adventure")
        val adultOrHarsh = item.adult == true || item.hasGenreOrText("gore", "slasher", "torture", "erotic")
        val matches = familySignal && !adultOrHarsh
        return MoodMatchResult(matches, if (matches) 0.72 else 0.0, "family-safe metadata signal")
    }
}

fun List<MediaItem>.applyMoodFilter(
    selectedMoodId: String,
    scorer: MoodFilterScorer = MoodFilterScorer(),
): List<MediaItem> {
    val mood = MoodFilter.fromChipId(selectedMoodId) ?: return this
    val stats = CandidateStats.from(this)
    // Mood is an AND filter layered onto the already-filtered candidate set.
    // Existing page sort is preserved; the score only decides membership.
    return filter { item -> scorer.score(item, mood, stats).matches }
}

private const val ALL_MOODS_ID = "all"

private fun MediaItem.discoveryKey(): String = "${type.name}:${tmdbId ?: id}"

private fun MediaItem.ratingSignal(): Double? {
    val candidates = listOfNotNull(
        ratings?.imdbScore?.toDouble(),
        ratings?.tmdbScore?.toDouble(),
        ratings?.traktScore?.toDouble()?.normalizeRating(),
        ratings?.mdblistScore?.toDouble()?.normalizeRating(),
        rating,
    ).map { it.normalizeRating() }
    return candidates.maxOrNull()
}

private fun Double.normalizeRating(): Double =
    when {
        this > 100.0 -> this / 10.0
        this > 10.0 -> this / 10.0
        else -> this
    }.coerceIn(0.0, 10.0)

private fun MediaItem.voteSignal(): Int = listOfNotNull(
    voteCount,
    ratings?.imdbVotes,
).maxOrNull() ?: 0

private fun MediaItem.veryHighVoteCountThreshold(): Int =
    if (type == MediaType.SERIES) 8_000 else 20_000

private fun MediaItem.hasGenreOrText(vararg tokens: String): Boolean {
    val normalizedTokens = tokens.map { it.lowercase() }
    val genreText = (genres.map { it.name } + genreIds.map { it.toString() }).joinToString(" ").lowercase()
    val body = "${title.lowercase()} ${overview.orEmpty().lowercase()} $genreText"
    return normalizedTokens.any { token -> token in body }
}

private fun MediaItem.hasHarshDateNightSignal(): Boolean {
    val hasRomanceSignal = hasGenreOrText(
        "romance",
        "romantic",
        "love",
        "couple",
        "dating",
        "marriage",
        "wedding",
        "relationship",
    )
    val harsh = hasGenreOrText(
        "gore",
        "slasher",
        "torture",
        "exploitation",
        "brutal",
        "bleak",
        "horror",
        "war",
        "crime",
        "serial killer",
    )
    return harsh && !hasRomanceSignal
}

private fun MediaItem.isKnownOverexposedTitle(): Boolean {
    val normalized = title
        .lowercase()
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .replace(Regex("\\s+"), " ")
        .trim()
    return normalized in knownOverexposedTitles
}

private val knownOverexposedTitles = setOf(
    "breaking bad",
    "game of thrones",
    "the office",
    "friends",
    "stranger things",
    "the last of us",
    "the walking dead",
)

private fun scoreRange(value: Double, min: Double, max: Double): Double {
    if (max <= min) return 0.0
    return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
}

private fun voteConfidence(votes: Int, minimumVotes: Int): Double {
    if (votes <= 0 || minimumVotes <= 0) return 0.0
    return (ln(votes.toDouble() + 1.0) / ln((minimumVotes * 24).toDouble() + 1.0)).coerceIn(0.0, 1.0)
}
