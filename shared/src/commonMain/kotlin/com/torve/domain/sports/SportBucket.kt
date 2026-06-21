package com.torve.domain.sports

/**
 * Buckets a release-name string into a coarse sport category. Used by
 * the Sports page filter pills so users can narrow Newznab category
 * 5060 results (which mix every sport into one feed) to just the
 * league they care about.
 *
 * Match priority is keyword-first, then league/team hints. Order
 * matters for ambiguous cases — e.g. "F1" must check before generic
 * substring "1" sneaks in. The classifier walks buckets in declaration
 * order so put the most specific keywords first.
 *
 * Lives in shared/ so desktop, TV, and mobile sports surfaces all
 * share the same bucketing rules — adding a sport means one edit, not
 * three.
 */
enum class SportBucket(
    val label: String,
    /**
     * Lowercase substrings that, when present in the title, classify
     * the release into this bucket. Keep them specific to the sport —
     * avoid words like "match" that mean nothing on their own.
     */
    val keywords: List<String>,
) {
    F1(
        label = "Formula 1",
        keywords = listOf(
            "formula1", "formula.1", "formula 1", "f1.", " f1 ", ".f1.",
            ".gp.", " gp ", "grand prix", "grand.prix",
            "monaco", "silverstone", "spa.francorchamps",
        ),
    ),
    MMA(
        label = "MMA / UFC",
        keywords = listOf(
            "ufc", "mma", "bellator", "pfl", "one championship", "one.championship",
            "fight.night", "fightnight", "rizin",
        ),
    ),
    BOXING(
        label = "Boxing",
        keywords = listOf(
            "boxing", "heavyweight", "bantamweight", "welterweight",
            "fury", "wilder", "canelo", "joshua",
        ),
    ),
    WRESTLING(
        label = "Wrestling",
        keywords = listOf(
            "wwe", "aew", "raw.is", " smackdown", "smackdown.", "njpw",
            "wrestling", "royal.rumble", "wrestlemania",
        ),
    ),
    AMERICAN_FOOTBALL(
        label = "Football (NFL)",
        keywords = listOf(
            "nfl", "ncaa.football", "ncaa football", "ncaaf",
            "super.bowl", "superbowl", "super bowl",
            "afc", "nfc", "playoffs.afc", "playoffs.nfc",
            "monday.night.football", "thursday.night.football",
        ),
    ),
    BASKETBALL(
        label = "Basketball",
        keywords = listOf(
            "nba", "ncaa.basketball", "ncaa basketball", "ncaab",
            "wnba", "euroleague", "march.madness", "final.four",
            "basketball",
        ),
    ),
    BASEBALL(
        label = "Baseball",
        keywords = listOf(
            "mlb", "world.series", "world series",
            "alds", "nlds", "alcs", "nlcs",
            "baseball", "mets", "yankees",
        ),
    ),
    HOCKEY(
        label = "Hockey",
        keywords = listOf(
            "nhl", "stanley.cup", "stanley cup", "ice.hockey", "ice hockey",
            "hockey", "iihf",
        ),
    ),
    SOCCER(
        label = "Soccer",
        keywords = listOf(
            "soccer", "fifa.world.cup", "world.cup",
            "uefa", "champions.league", "europa.league", "conference.league",
            "premier.league", "la.liga", "bundesliga", "serie.a", "ligue.1",
            "mls", "epl", "afc.cup", "copa.america", "copa america",
            "fa.cup", "fa cup", "carabao",
            "vs.real.madrid", "vs.barcelona", "manchester.united", "manutd",
            "liverpool.fc", "arsenal.fc", "chelsea.fc",
        ),
    ),
    TENNIS(
        label = "Tennis",
        keywords = listOf(
            "tennis", "atp", "wta", "wimbledon", "us.open.tennis",
            "australian.open", "french.open", "roland.garros", "rolandgarros",
        ),
    ),
    GOLF(
        label = "Golf",
        keywords = listOf(
            "pga.", "pga ", "the.masters", "the masters", "golf.",
            "ryder.cup", "us.open.golf", "british.open", "the.open.championship",
        ),
    ),
    CRICKET(
        label = "Cricket",
        keywords = listOf(
            "cricket", "ipl.", "ipl ", "icc.", "icc ", "the.ashes", "ashes",
            "t20.world.cup", "bbl",
        ),
    ),
    RUGBY(
        label = "Rugby",
        keywords = listOf(
            "rugby", "six.nations", "six nations", "premiership.rugby",
            "world.cup.rugby", "rugby.world.cup",
        ),
    ),
    OTHER(label = "Other", keywords = emptyList());

    companion object {
        fun classify(title: String): SportBucket {
            val lower = title.lowercase()
            for (bucket in entries) {
                if (bucket == OTHER) continue
                if (bucket.keywords.any { it in lower }) return bucket
            }
            return OTHER
        }
    }
}
