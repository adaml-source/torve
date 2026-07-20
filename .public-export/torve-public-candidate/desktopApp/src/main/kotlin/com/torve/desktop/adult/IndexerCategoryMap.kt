package com.torve.desktop.adult

/**
 * Maps Newznab indexers to their per-language Movies categories.
 *
 * Background:
 *  - The Newznab spec defines `2000=Movies`, `2010=Movies/Foreign`,
 *    `2020=Movies/Other`, `2030=SD`, `2040=HD`, `2045=UHD`,
 *    `2050=BluRay`, `2060=3D`. None of these are language-coded - the
 *    spec assumes a single language pool ("Foreign" = "not English").
 *  - Some indexers extend the spec with their own language-specific
 *    movie sub-categories. scenenzbs is the most explicit: 2100=German,
 *    2200=Spanish, etc. Others (NZBgeek, dognzb, NZBplanet) stick to
 *    the spec - for those we use cat=2000 and rely on title-based
 *    filtering (`GERMAN`/`DEUTSCH`/`MULTi` tags) downstream.
 *
 * If you support a new indexer, add a row here with whatever language
 * mappings it documents. When in doubt, leave the row out and the
 * generic fallback (just `2000`) applies.
 */
object IndexerCategoryMap {

    /** Standard movie language tags Torve surfaces in UI pills. */
    enum class MovieLanguage(val code: String, val label: String) {
        ANY("any", "Any"),
        ENGLISH("en", "English"),
        GERMAN("de", "German"),
        SPANISH("es", "Spanish"),
        FRENCH("fr", "French"),
        ITALIAN("it", "Italian"),
        DUTCH("nl", "Dutch"),
        ;

        companion object {
            fun fromCode(code: String): MovieLanguage? =
                entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
        }
    }

    /**
     * Per-indexer language → category list. When a language isn't keyed
     * here, [moviesCategoriesFor] falls back to the indexer's general
     * movies category (`2000`).
     *
     * Only scenenzbs is fully populated today because that's the indexer
     * that documented language sub-cats; the others use the standard
     * Newznab schema where everything non-English collapses into 2010.
     */
    private val MAP: Map<String, Map<MovieLanguage, List<String>>> = mapOf(
        "scenenzbs" to mapOf(
            MovieLanguage.ANY to listOf("2000"),
            MovieLanguage.ENGLISH to listOf("2000"),
            MovieLanguage.GERMAN to listOf("2100"),
            MovieLanguage.SPANISH to listOf("2200"),
            // The remaining language sub-cats below are best-guess slots
            // following scenenzbs' "+100 per language" pattern. If a row
            // returns 0 results we fall back to 2000 in the page logic.
            MovieLanguage.FRENCH to listOf("2300"),
            MovieLanguage.ITALIAN to listOf("2400"),
            MovieLanguage.DUTCH to listOf("2500"),
        ),
        "nzbgeek" to mapOf(
            MovieLanguage.ANY to listOf("2000"),
            MovieLanguage.ENGLISH to listOf("2000"),
            // Non-English → spec "Foreign" sub-category. Title filter
            // narrows to the actual language client-side.
            MovieLanguage.GERMAN to listOf("2010"),
            MovieLanguage.SPANISH to listOf("2010"),
            MovieLanguage.FRENCH to listOf("2010"),
            MovieLanguage.ITALIAN to listOf("2010"),
            MovieLanguage.DUTCH to listOf("2010"),
        ),
        "dognzb" to mapOf(
            MovieLanguage.ANY to listOf("2000"),
            MovieLanguage.ENGLISH to listOf("2000"),
            MovieLanguage.GERMAN to listOf("2010"),
            MovieLanguage.SPANISH to listOf("2010"),
            MovieLanguage.FRENCH to listOf("2010"),
            MovieLanguage.ITALIAN to listOf("2010"),
            MovieLanguage.DUTCH to listOf("2010"),
        ),
        "nzbplanet" to mapOf(
            MovieLanguage.ANY to listOf("2000"),
            MovieLanguage.ENGLISH to listOf("2000"),
            MovieLanguage.GERMAN to listOf("2010"),
            MovieLanguage.SPANISH to listOf("2010"),
            MovieLanguage.FRENCH to listOf("2010"),
            MovieLanguage.ITALIAN to listOf("2010"),
            MovieLanguage.DUTCH to listOf("2010"),
        ),
    )

    /**
     * Compute the comma-separated `cat=` value to send to the indexer
     * given a set of selected languages. Examples (scenenzbs):
     *  - {ANY}                 → "2000"
     *  - {ENGLISH}             → "2000"
     *  - {GERMAN}              → "2100"
     *  - {ENGLISH, GERMAN}     → "2000,2100"
     *  - {SPANISH, FRENCH}     → "2200,2300"
     */
    fun moviesCategoriesFor(
        indexerType: String,
        languages: Set<MovieLanguage>,
    ): String {
        val effective = if (languages.isEmpty() || MovieLanguage.ANY in languages) {
            setOf(MovieLanguage.ANY)
        } else languages
        val perIndexer = MAP[indexerType.lowercase()]
        val ids = effective.flatMap { lang ->
            perIndexer?.get(lang) ?: listOf("2000")
        }.distinct()
        return ids.joinToString(",")
    }

    /**
     * Per-indexer TV language → category list. scenenzbs follows the
     * same +100 pattern it uses for movies but starts the band at 5000:
     *   5000=All, 5100=German, 5200=Spanish, 5300=French, 5400=Italian,
     *   5500=Dutch. (Cf. https://scenenzbs.com/browse/5100 for German.)
     * Indexers without per-language TV cats return the spec category
     * 5000 and rely on title-tag filtering downstream.
     */
    private val TV_MAP: Map<String, Map<MovieLanguage, List<String>>> = mapOf(
        "scenenzbs" to mapOf(
            MovieLanguage.ANY to listOf("5000"),
            MovieLanguage.ENGLISH to listOf("5000"),
            MovieLanguage.GERMAN to listOf("5100"),
            MovieLanguage.SPANISH to listOf("5200"),
            MovieLanguage.FRENCH to listOf("5300"),
            MovieLanguage.ITALIAN to listOf("5400"),
            MovieLanguage.DUTCH to listOf("5500"),
        ),
    )

    fun tvCategoriesFor(
        indexerType: String,
        languages: Set<MovieLanguage>,
    ): String {
        val effective = if (languages.isEmpty() || MovieLanguage.ANY in languages) {
            setOf(MovieLanguage.ANY)
        } else languages
        val perIndexer = TV_MAP[indexerType.lowercase()]
        val ids = effective.flatMap { lang ->
            perIndexer?.get(lang) ?: listOf("5000")
        }.distinct()
        return ids.joinToString(",")
    }

    fun hasTvLanguageCategories(indexerType: String): Boolean =
        indexerType.equals("scenenzbs", ignoreCase = true)

    /**
     * Sports categories. Spec category 5060 covers TV sport for every
     * Newznab indexer. scenenzbs additionally exposes 5160 as the
     * German-language sport sub-cat (same +100 language pattern it
     * uses everywhere). Combining both broadens the result set for
     * users who want both English and German releases without
     * forcing them to flip a language filter.
     */
    fun sportsCategoriesFor(indexerType: String): String =
        if (indexerType.equals("scenenzbs", ignoreCase = true)) "5060,5160"
        else "5060"

    /**
     * For indexers without a language sub-cat, append a language hint
     * to the free-text `q=`. Pads "Better Call Saul" → "Better Call
     * Saul GERMAN" so Newznab biases toward German-tagged releases.
     */
    fun augmentQueryForLanguage(
        indexerSupportsLanguageCats: Boolean,
        baseQuery: String,
        language: MovieLanguage,
    ): String {
        if (indexerSupportsLanguageCats) return baseQuery
        if (language == MovieLanguage.ANY || language == MovieLanguage.ENGLISH) return baseQuery
        val tag = when (language) {
            MovieLanguage.GERMAN -> "GERMAN"
            MovieLanguage.SPANISH -> "SPANISH"
            MovieLanguage.FRENCH -> "FRENCH"
            MovieLanguage.ITALIAN -> "ITALIAN"
            MovieLanguage.DUTCH -> "DUTCH"
            else -> return baseQuery
        }
        return if (baseQuery.isBlank()) tag else "$baseQuery $tag"
    }

    /**
     * Heuristic title-based language filter - used as a fallback when
     * the indexer doesn't separate languages by category (NZBgeek etc.)
     * so we can still narrow results after the fetch. Returns true if
     * the title's language tags match any of the selected languages.
     * If [languages] is empty / contains ANY, every title passes.
     */
    fun titleMatchesLanguages(title: String, languages: Set<MovieLanguage>): Boolean {
        if (languages.isEmpty() || MovieLanguage.ANY in languages) return true
        val lower = title.lowercase()
        return languages.any { lang ->
            when (lang) {
                MovieLanguage.ENGLISH -> {
                    // English is the assumed default; treat lack of any
                    // foreign tag as English. If a foreign tag is found
                    // (and the user wants ONLY English), fail the row.
                    val foreignTags = listOf(
                        "german", "deutsch", "french", "spanish", "italian",
                        "dutch", "vostfr", "multi.", "subbed", "subtitled",
                    )
                    foreignTags.none { tag -> tag in lower }
                }
                MovieLanguage.GERMAN -> "german" in lower || "deutsch" in lower
                MovieLanguage.SPANISH -> "spanish" in lower || "español" in lower || "castellano" in lower
                MovieLanguage.FRENCH -> "french" in lower || "francais" in lower || "vff" in lower
                MovieLanguage.ITALIAN -> "italian" in lower || "italiano" in lower || "ita." in lower
                MovieLanguage.DUTCH -> "dutch" in lower || "nederland" in lower
                MovieLanguage.ANY -> true
            }
        }
    }

    /**
     * Whether this indexer keeps separate per-language categories. Used
     * by the UI to decide if title-based filtering is needed as a
     * fallback after the fetch.
     */
    fun hasLanguageCategories(indexerType: String): Boolean =
        indexerType.equals("scenenzbs", ignoreCase = true)
}
