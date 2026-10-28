package com.torve.data.addon

data class SourceLanguageOption(val code: String, val name: String, val flag: String) {
    val label: String get() = "$flag $name"
}

/** Explicit audio/subtitle hints. MULTI never establishes a particular language. */
object SourceLanguages {
    private data class Language(val option: SourceLanguageOption, val aliases: Set<String>)
    private fun language(code: String, name: String, flag: String, vararg aliases: String) =
        Language(SourceLanguageOption(code, name, flag), (aliases.toList() + code + name).map { it.uppercase() }.toSet())

    private val languages = listOf(
        language("DE", "German", "🇩🇪", "DEU", "GER", "DEUTSCH"),
        language("EN", "English", "🇬🇧", "ENG"),
        language("FR", "French", "🇫🇷", "FRA", "FRE", "FRANCAIS", "FRANÇAIS", "TRUEFRENCH", "VFF", "VFQ"),
        language("ES", "Spanish", "🇪🇸", "SPA", "ESP", "ESPANOL", "ESPAÑOL", "CASTELLANO", "LATINO"),
        language("IT", "Italian", "🇮🇹", "ITA", "ITALIANO"),
        language("PT", "Portuguese", "🇵🇹", "POR", "PORTUGUES", "PORTUGUÊS"),
        language("PT-BR", "Brazilian Portuguese", "🇧🇷", "PTBR", "PT_BR"),
        language("NL", "Dutch", "🇳🇱", "NLD", "DUT", "NEDERLANDS"),
        language("PL", "Polish", "🇵🇱", "POL", "POLSKI"),
        language("RU", "Russian", "🇷🇺", "RUS"),
        language("UK", "Ukrainian", "🇺🇦", "UKR"),
        language("JA", "Japanese", "🇯🇵", "JPN", "JAP"),
        language("KO", "Korean", "🇰🇷", "KOR"),
        language("ZH", "Chinese", "🇨🇳", "ZHO", "CHI", "CHS", "CHT", "MANDARIN"),
        language("HI", "Hindi", "🇮🇳", "HIN"),
        language("TA", "Tamil", "🇮🇳", "TAM"),
        language("TE", "Telugu", "🇮🇳", "TEL"),
        language("AR", "Arabic", "🇸🇦", "ARA"),
        language("TR", "Turkish", "🇹🇷", "TUR"),
        language("SV", "Swedish", "🇸🇪", "SWE"),
        language("NO", "Norwegian", "🇳🇴", "NOR", "NOB", "NNO"),
        language("DA", "Danish", "🇩🇰", "DAN"),
        language("FI", "Finnish", "🇫🇮", "FIN"),
        language("CS", "Czech", "🇨🇿", "CES", "CZE"),
        language("SK", "Slovak", "🇸🇰", "SLK", "SLO"),
        language("HU", "Hungarian", "🇭🇺", "HUN"),
        language("RO", "Romanian", "🇷🇴", "RON", "RUM"),
        language("EL", "Greek", "🇬🇷", "ELL", "GRE"),
        language("HE", "Hebrew", "🇮🇱", "HEB"),
        language("ID", "Indonesian", "🇮🇩", "IND"),
        language("TH", "Thai", "🇹🇭", "THA"),
        language("VI", "Vietnamese", "🇻🇳", "VIE"),
    )
    private val aliasCodes = languages.flatMap { language -> language.aliases.map { it to language.option.code } }.toMap()
    private val flagCodes = languages.groupBy { it.option.flag }.filterValues { it.size == 1 }
        .mapValues { it.value.single().option.code }
    // A provider's untyped language/flag list may include subtitles. Only an
    // audio label or audio badge establishes audio; generic lists stay unknown.
    private val sectionMarker = Regex(
        "(?i)(\\baudio(?:[\\t ]+(?:languages?|tracks?))?[\\t ]*[:=]|🗣️?|🔊|🎧)" +
            "|(\\b(?:subtitles?|subs|cc)(?:[\\t ]+(?:languages?|tracks?))?[\\t ]*[:=]|" +
            "\\b(?:subtitles?|subs)[\\t ]+|💬)" +
            "|(\\b(?:languages?|lang)[\\t ]*[:=])"
    )
    private enum class SectionKind { RELEASE, AUDIO, SUBTITLE, UNTYPED }
    private data class Section(val kind: SectionKind, val content: String)
    private val technicalStart = Regex("(?i)(?:\\b(?:19|20)\\d{2}\\b|\\bS\\d{1,2}E\\d{1,3}\\b|\\b(?:2160p|1080p|720p|480p|4k|web[ .-]?dl|bluray|bdrip)\\b)")
    private val subtitleToken = Regex("(?i)\\b([a-z]{2,12})[ ._-]*(?:subs?|subbed|subtitles?)\\b|\\b(?:subs?|subtitles?)[ ._-]+([a-z]{2,12})\\b")
    private val subtitleMarker = Regex("(?i)\\b(?:subs?|subbed|subtitles?|VOSTFR)\\b")
    private val brackets = Regex("\\[([^]\\n]+)]|\\(([^)\\n]+)\\)")

    private fun sections(line: String): List<Section> {
        val markers = sectionMarker.findAll(line).toList()
        return buildList {
            add(Section(SectionKind.RELEASE, line.substring(0, markers.firstOrNull()?.range?.first ?: line.length)))
            markers.forEachIndexed { index, marker ->
                val kind = when {
                    marker.groups[1] != null -> SectionKind.AUDIO
                    marker.groups[2] != null -> SectionKind.SUBTITLE
                    else -> SectionKind.UNTYPED
                }
                add(Section(kind, line.substring(marker.range.last + 1,
                    markers.getOrNull(index + 1)?.range?.first ?: line.length)))
            }
        }
    }

    private fun releaseMetadata(text: String): String? {
        val start = technicalStart.find(text)?.range?.first ?: return null
        // Bare flags also occur next to a filename, not just on their own line.
        val flagStart = (languages.map { it.option.flag } + "🇺🇸")
            .map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: text.length
        return text.substring(start, flagStart.coerceAtLeast(start))
    }

    fun normalize(value: String): String? {
        val token = value.trim().uppercase()
        return aliasCodes[token] ?: aliasCodes[token.substringBefore('-').substringBefore('_')]
    }

    fun extract(text: String): List<String> {
        val explicit = linkedSetOf<String>()
        val inferred = linkedSetOf<String>()
        text.lines().flatMap(::sections).forEach { section ->
            if (section.kind == SectionKind.AUDIO) {
                explicit += parseTokens(section.content, allowShortCodes = true, allowFlags = true)
            } else if (section.kind == SectionKind.RELEASE) {
                releaseMetadata(section.content)?.let { metadata ->
                    val withoutSubtitleGroups = brackets.replace(metadata) {
                        if (subtitleMarker.containsMatchIn(it.value)) " ".repeat(it.value.length) else it.value
                    }
                    val subtitleStart = subtitleMarker.find(withoutSubtitleGroups)?.range?.first
                    val clean = subtitleToken.replace(withoutSubtitleGroups) { " ".repeat(it.value.length) }
                        .take(subtitleStart ?: withoutSubtitleGroups.length)
                    inferred += parseTokens(clean, allowShortCodes = false)
                    brackets.findAll(clean).forEach {
                        inferred += parseTokens(it.value, allowShortCodes = true)
                    }
                }
            }
        }
        return (explicit.ifEmpty { inferred }).toList()
    }

    private fun parseTokens(text: String, allowShortCodes: Boolean, allowFlags: Boolean = false): List<String> {
        val codes = linkedSetOf<String>()
        // PT-BR is a single audio language variant, not two separate tokens.
        val normalized = text.uppercase().replace("PT-BR", "PTBR").replace("PT_BR", "PTBR")
        normalized.split(Regex("[^\\p{L}]+"))
            .filter { allowShortCodes || it.length >= 3 }
            .mapNotNullTo(codes) { aliasCodes[it] }
        if (allowFlags) {
            flagCodes.forEach { (flag, code) -> if (text.contains(flag)) codes += code }
            if (text.contains("🇺🇸")) codes += "EN"
        }
        return codes.toList()
    }

    fun codes(stream: ParsedStream): List<String> = stream.languages.mapNotNull(::normalize).distinct()
        .ifEmpty { if (stream.trackLanguagesVerified) emptyList() else extract(stream.title) }

    fun extractSubtitles(text: String): List<String> {
        val result = linkedSetOf<String>()
        text.lines().flatMap(::sections).forEach { section ->
            if (section.kind == SectionKind.SUBTITLE) {
                result += parseTokens(section.content, allowShortCodes = true, allowFlags = true)
            } else if (section.kind == SectionKind.RELEASE) {
                releaseMetadata(section.content)?.let { metadata ->
                    brackets.findAll(metadata).filter { subtitleMarker.containsMatchIn(it.value) }.forEach {
                        result += parseTokens(it.value, allowShortCodes = true)
                    }
                    subtitleToken.findAll(metadata).forEach { match ->
                        match.groupValues.drop(1).mapNotNullTo(result, ::normalize)
                    }
                    subtitleMarker.find(metadata)?.let {
                        result += parseTokens(metadata.substring(it.range.last + 1), allowShortCodes = true)
                    }
                    if (Regex("(?i)\\bVOSTFR\\b").containsMatchIn(metadata)) result += "FR"
                }
            }
        }
        return result.toList()
    }

    fun subtitleCodes(stream: ParsedStream): List<String> = stream.subtitleLanguages.mapNotNull(::normalize).distinct()
        .ifEmpty { if (stream.trackLanguagesVerified) emptyList() else extractSubtitles(stream.title) }

    /** Keep verified track types separate; never merge old flags or filename hints back in. */
    fun withVerifiedTracks(stream: ParsedStream, audio: List<String>, subtitles: List<String>): ParsedStream =
        stream.copy(languages = audio.mapNotNull(::normalize).distinct(),
            subtitleLanguages = subtitles.mapNotNull(::normalize).distinct(), trackLanguagesVerified = true)

    fun allOptions(): List<SourceLanguageOption> = languages.map { it.option }.sortedBy { it.name }

    fun options(streams: List<ParsedStream>): List<SourceLanguageOption> {
        val available = streams.flatMap { codes(it) + subtitleCodes(it) }.toSet()
        return languages.map { it.option }.filter { it.code in available }.sortedBy { it.name }
    }

    fun matches(stream: ParsedStream, language: String): Boolean = when (language) {
        "" -> true
        "UNKNOWN" -> codes(stream).isEmpty()
        else -> language in codes(stream)
    }

    fun matchesPreference(stream: ParsedStream, language: String, allowSubtitles: Boolean): Boolean =
        matches(stream, language) || (allowSubtitles && language in subtitleCodes(stream))

    fun subtitleLabel(stream: ParsedStream): String = subtitleCodes(stream).mapNotNull { code ->
        languages.firstOrNull { it.option.code == code }?.option?.label
    }.joinToString(" · ")

    fun label(stream: ParsedStream): String {
        val known = codes(stream)
        if (known.isEmpty()) {
            return if (Regex("(?i)\\b(?:multi|dual)[ ._-]?(?:audio)?\\b").containsMatchIn(stream.title)) {
                "Multiple languages · unspecified"
            } else "Audio language unknown"
        }
        return known.mapNotNull { code -> languages.firstOrNull { it.option.code == code }?.option?.label }
            .joinToString(" · ")
    }
}
