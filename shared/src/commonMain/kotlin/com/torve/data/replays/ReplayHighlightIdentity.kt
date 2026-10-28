package com.torve.data.replays

/** Match participants, not the spelling or sponsorship text of a replay headline. */
internal object ReplayHighlightIdentity {
    private data class Team(val short: String, val names: List<String>)
    private fun team(short: String, vararg names: String) = Team(short, (names.toList() + short).map(::normalize))
    private val teams = listOf(
        team("76ers", "Philadelphia 76ers", "Philadelphia Sixers", "Sixers", "PHI"),
        team("Bucks", "Milwaukee Bucks", "MIL"), team("Lakers", "Los Angeles Lakers", "LA Lakers", "LAL"),
        team("Clippers", "Los Angeles Clippers", "LA Clippers", "LAC"), team("Celtics", "Boston Celtics", "BOS"),
        team("Warriors", "Golden State Warriors", "GSW"), team("Knicks", "New York Knicks", "NYK"),
        team("Nets", "Brooklyn Nets", "BKN"), team("Heat", "Miami Heat", "MIA"), team("Hornets", "Charlotte Hornets", "CHA"),
        team("Cavaliers", "Cleveland Cavaliers", "Cavs", "CLE"), team("Mavericks", "Dallas Mavericks", "Mavs", "DAL"),
        team("Nuggets", "Denver Nuggets", "DEN"), team("Pistons", "Detroit Pistons", "DET"),
        team("Rockets", "Houston Rockets", "HOU"), team("Pacers", "Indiana Pacers", "IND"),
        team("Grizzlies", "Memphis Grizzlies", "MEM"), team("Timberwolves", "Minnesota Timberwolves", "Wolves", "MIN"),
        team("Pelicans", "New Orleans Pelicans", "NOP"), team("Thunder", "Oklahoma City Thunder", "OKC"),
        team("Magic", "Orlando Magic", "ORL"), team("Suns", "Phoenix Suns", "PHX"),
        team("Trail Blazers", "Portland Trail Blazers", "Blazers", "POR"), team("Kings", "Sacramento Kings", "SAC"),
        team("Spurs", "San Antonio Spurs", "SAS"), team("Raptors", "Toronto Raptors", "TOR"),
        team("Jazz", "Utah Jazz", "UTA"), team("Wizards", "Washington Wizards", "WAS"),
        team("Hawks", "Atlanta Hawks", "ATL"), team("Bulls", "Chicago Bulls", "CHI"),
        team("Chiefs", "Kansas City Chiefs", "KC"), team("Bills", "Buffalo Bills", "BUF"),
        team("Patriots", "New England Patriots", "NE"), team("Dolphins", "Miami Dolphins"), team("Jets", "New York Jets", "NYJ"),
        team("Giants", "New York Giants", "NYG"), team("Eagles", "Philadelphia Eagles"), team("Cowboys", "Dallas Cowboys"),
        team("Commanders", "Washington Commanders", "Washington Football Team"), team("Ravens", "Baltimore Ravens"),
        team("Bengals", "Cincinnati Bengals"), team("Browns", "Cleveland Browns"), team("Steelers", "Pittsburgh Steelers"),
        team("Texans", "Houston Texans"), team("Colts", "Indianapolis Colts"), team("Jaguars", "Jacksonville Jaguars", "Jags"),
        team("Titans", "Tennessee Titans"), team("Broncos", "Denver Broncos"), team("Raiders", "Las Vegas Raiders", "Oakland Raiders"),
        team("Chargers", "Los Angeles Chargers", "LA Chargers"), team("Bears", "Chicago Bears"),
        team("Lions", "Detroit Lions"), team("Packers", "Green Bay Packers"), team("Vikings", "Minnesota Vikings"),
        team("Falcons", "Atlanta Falcons"), team("Panthers", "Carolina Panthers"), team("Saints", "New Orleans Saints"),
        team("Buccaneers", "Tampa Bay Buccaneers", "Bucs"), team("Cardinals", "Arizona Cardinals"),
        team("Rams", "Los Angeles Rams", "LA Rams"), team("49ers", "San Francisco 49ers", "Niners"), team("Seahawks", "Seattle Seahawks"),
        team("Olympiacos", "Olympiacos Piraeus", "Olympiakos"), team("Fenerbahce", "Fenerbahce Istanbul", "Fenerbahce Beko"),
        team("Zalgiris", "Zalgiris Kaunas"), team("ASVEL", "ASV EL Villeurbanne", "LDLC ASVEL", "ASVEL Villeurbanne"),
        team("Paris", "Paris Basketball"), team("Valencia", "Valencia Basket"), team("Real Madrid"),
        team("Barcelona", "FC Barcelona", "Barca"), team("Panathinaikos", "Panathinaikos Athens", "Panathinaikos AKTOR"),
        team("Partizan", "Partizan Belgrade", "Partizan Mozzart Bet"), team("Crvena Zvezda", "Red Star Belgrade", "Crvena Zvezda Belgrade"),
        team("Baskonia", "Vitoria Gasteiz"), team("Anadolu Efes", "Efes Istanbul"), team("Monaco", "AS Monaco"),
        team("Bayern", "Bayern Munich", "Bayern Munchen"), team("Maccabi", "Maccabi Tel Aviv"),
        team("Milano", "Olimpia Milano", "EA7 Emporio Armani Milan"), team("Virtus Bologna", "Virtus Segafredo Bologna"),
        team("Hapoel Tel Aviv"), team("Dubai", "Dubai Basketball")
    )
    fun normalize(value: String): String {
        val accents = "áàâäãåéèêëíìîïóòôöõúùûüýÿçñšžćčğşı"
        val ascii =   "aaaaaaeeeeiiiiooooouuuuyycnszccgsi"
        return value.lowercase().map { c -> accents.indexOf(c).takeIf { it >= 0 }?.let { ascii[it] } ?: c }.joinToString("")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }
    private fun contains(text: String, name: String) = " $name " in " $text "
    private fun identity(side: String): Team? {
        val normalized = normalize(side)
        // Prefer a full name over an abbreviation shared with another sport.
        return teams.filter { team -> team.names.any { contains(normalized, it) } }
            .maxByOrNull { team -> team.names.filter { contains(normalized, it) }.maxOf { it.length } }
    }
    fun clean(value: String): String = value
        .replace(Regex("(?i)\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},?\\s+20\\d{2}\\b|\\b20\\d{2}-\\d{2}-\\d{2}\\b"), " ")
        .replace(Regex("(?i)\\b(?:full[ -]*(?:game|match|race|event)|replays?|nba|nfl|nhl|mlb|euroleague)\\b"), " ")
        .replace(Regex("\\s+[-|]\\s+"), " ").replace(Regex("\\s+"), " ").trim()
    fun sides(event: ReplayEvent, game: String?): List<String> = clean(game?.takeIf { it.isNotBlank() } ?: event.title)
        .split(Regex("\\s+(?:vs\\.?|v\\.?|versus|@)\\s+", RegexOption.IGNORE_CASE)).takeIf { it.size == 2 }.orEmpty()
    fun searchNames(event: ReplayEvent, game: String?): String {
        val sides = sides(event, game)
        return if (sides.size == 2) sides.joinToString(" ") { identity(it)?.short ?: it }
        else clean(game?.takeIf { it.isNotBlank() } ?: event.title)
    }
    /** Event context comes from the selected replay, never the outcome-bearing YouTube title. */
    fun displayName(event: ReplayEvent, game: String?): String {
        val participants = sides(event, game)
        return if (participants.size == 2) participants.joinToString(" vs ") { identity(it)?.short ?: it.trim() }
        else clean(game?.takeIf { it.isNotBlank() } ?: event.title)
    }
    fun participantsMatch(title: String, event: ReplayEvent, game: String?): Boolean {
        val sides = sides(event, game)
        if (sides.size != 2) return false
        val normalized = normalize(title)
        return sides.all { side ->
            val team = identity(side)
            if (team != null) team.names.any { contains(normalized, it) }
            else normalize(side).split(' ').filter { it.length > 2 && it !in setOf("basketball", "basket", "football", "club") }
                .let { words -> words.isNotEmpty() && words.all { contains(normalized, it) } }
        }
    }

    private val raceVenues = listOf(
        listOf("United States", "USA", "US", "Austin", "COTA"), listOf("Great Britain", "British", "Silverstone"),
        listOf("Netherlands", "Dutch", "Zandvoort"), listOf("Italy", "Italian", "Monza"),
        listOf("Emilia Romagna", "Imola"), listOf("Belgium", "Belgian", "Spa"),
        listOf("Brazil", "Brazilian", "Sao Paulo", "Interlagos"), listOf("Mexico", "Mexican", "Mexico City"),
        listOf("Austria", "Austrian", "Spielberg"), listOf("Japan", "Japanese", "Suzuka"),
        listOf("Australia", "Australian", "Melbourne"), listOf("Canada", "Canadian", "Montreal"),
        listOf("Spain", "Spanish", "Barcelona"), listOf("Azerbaijan", "Baku"),
        listOf("Saudi Arabia", "Saudi Arabian", "Jeddah"), listOf("Abu Dhabi", "Yas Marina"),
        listOf("Singapore"), listOf("Monaco"), listOf("Qatar", "Lusail"), listOf("Bahrain", "Sakhir"),
        listOf("Miami"), listOf("Las Vegas"), listOf("Hungary", "Hungarian", "Budapest"), listOf("China", "Chinese", "Shanghai")
    ).map { aliases -> aliases.map(::normalize) }
    fun raceMatches(title: String, expected: String): Boolean {
        val source = normalize(expected); val candidate = normalize(title)
        val venue = raceVenues.firstOrNull { aliases -> aliases.any { contains(source, it) } } ?: return false
        if (venue.none { contains(candidate, it) }) return false
        val session = when {
            "qualifying" in source || "qualification" in source -> "qualifying"
            "sprint" in source -> "sprint"
            "practice" in source -> "practice"
            else -> "race"
        }
        return contains(candidate, session) && (session != "race" || !contains(candidate, "sprint"))
    }
}
