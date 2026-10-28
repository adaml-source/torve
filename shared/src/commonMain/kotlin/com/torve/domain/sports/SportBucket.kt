package com.torve.domain.sports

import com.torve.util.DisplayTextDecoder

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
            "basketball", "euro.league", "eurocup", "fiba", "acb",
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
        private val separators = Regex("[^a-z0-9]+")
        private val episodePattern = Regex("(?i)(?:^|[^a-z0-9])(?:S\\d{1,3}[ ._-]*E\\d{1,3}|\\d{1,2}x\\d{2})(?:$|[^a-z0-9])")
        private fun words(value: String) = value.lowercase().replace(separators, " ").trim()
        private val keywordPatterns by lazy {
            entries.filter { it != OTHER }.associateWith { bucket ->
                bucket.keywords.map { keyword -> Regex("\\b${Regex.escape(words(keyword))}(?:\\d+)?\\b") }
            }
        }

        fun classify(title: String): SportBucket {
            val decodedTitle = DisplayTextDecoder.decodeReleaseTitle(title)
            // League names in a reality show's episode title are not events.
            val eventName = episodePattern.find(decodedTitle)?.let { decodedTitle.take(it.range.first) } ?: decodedTitle
            val normalized = words(eventName)
            // EuroLeague Monaco vs Barcelona is basketball, even though Monaco
            // is also a Formula 1 venue. Prefer the earliest sport signal.
            return keywordPatterns.mapNotNull { (bucket, patterns) ->
                patterns.mapNotNull { it.find(normalized)?.range?.first }.minOrNull()
                    ?.let { position -> bucket to position }
            }.minByOrNull { it.second }?.first ?: OTHER
        }

        private val gamePlatform = Regex("\\b(?:ps[1-5]|playstation|xbox|nintendo|switch|steam|gog|elamigos|fitgirl|skidrow|codex|dlc|nsp|xci|pkg|iso)\\b")
        private val gameTitle = Regex("\\b(?:(?:nba|wwe|pga(?: tour)?) ?2k ?\\d+|madden(?: nfl)? ?\\d+|fifa ?\\d+|ea sports (?:fc|ufc) ?\\d+|(?:nhl|f1) \\d{2})\\b")
        private val nonEvent = Regex("\\b(?:documentary|documentaries|docuseries|doku|dokumentation|biography|biopic|movie|film|featurette|trailer|interview|podcast|talk ?show|preview|countdown|embedded|magazine|news|sports ?cent(?:er|re)|sportschau|inside the nba|nba today|nfl total access|first take|pardon the interruption|drive to survive|all or nothing|hard knocks|the ultimate fighter|total divas|total bellas|30 for 30|untold)\\b")
        private val otherSports = Regex("\\b(?:darts|snooker|cycling|motogp|moto[23]|superbike|indycar|nascar|dtm|wec|wrc|rally|tour de france|vuelta|giro|olympics?|olympic|swimming|athletics|biathlon|skiing|ski jumping|volleyball|handball|badminton|table tennis|sailing|triathlon|marathon|surfing|gymnastics|water polo|fencing|weightlifting|judo|taekwondo)\\b")
        private val eventWords = Regex("\\b(?:vs?|versus|at|race|qualifying|practice|sprint|grand prix|main card|prelims?|preliminary|fight night|round|stage|finals?|quarterfinals?|semifinals?|playoffs?|championship|tournament|match|game|week|day|session|royal rumble|wrestlemania)(?:\\d+)?\\b")
        private val numberedEvent = Regex("\\b(?:ufc|bellator|pfl|rizin) ?\\d{2,3}\\b")
        private val wrestlingBroadcast = Regex("^(?:wwe (?:raw|smackdown|nxt|main event)|aew (?:dynamite|collision|rampage))\\b")

        /** A league keyword alone also describes games, films and studio programmes. Require event evidence. */
        fun isSportsRelease(title: String, categoryId: String? = null): Boolean {
            val categories = categoryId.orEmpty().split(',', ' ').mapNotNull { it.toIntOrNull() }
            // Explicit console, PC, movie, music, book, anime or documentary categories cannot be sports events.
            if (categories.any { it in 1000..4999 || it in 6000..8999 || it in setOf(5070, 5080, 5170, 5180, 5270, 5280, 5370, 5380) }) return false
            val decoded = DisplayTextDecoder.decodeReleaseTitle(title)
            val normalized = words(decoded)
            if (gamePlatform.containsMatchIn(normalized) || gameTitle.containsMatchIn(normalized) || nonEvent.containsMatchIn(normalized)) return false
            val wrestling = wrestlingBroadcast.containsMatchIn(normalized)
            // Weekly wrestling broadcasts are actual events; league-themed drama/reality episodes are not.
            if (episodePattern.containsMatchIn(decoded)) return wrestling
            if (classify(decoded) == OTHER && !otherSports.containsMatchIn(normalized)) return false
            return wrestling || SportsReleaseDateParser.eventDate(decoded) != null ||
                eventWords.containsMatchIn(normalized) || numberedEvent.containsMatchIn(normalized) || '@' in decoded
        }
    }
}
