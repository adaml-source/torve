package com.torve.data.subtitles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OsSearchResponse(
    val data: List<OsSubtitleItem> = emptyList(),
    @SerialName("total_count") val totalCount: Int? = null,
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
)

@Serializable
data class OsSubtitleItem(
    val id: String = "",
    val attributes: OsSubtitleAttributes = OsSubtitleAttributes(),
)

@Serializable
data class OsSubtitleAttributes(
    @SerialName("subtitle_id") val subtitleId: String? = null,
    val language: String = "",
    val files: List<OsSubtitleFile> = emptyList(),
    @SerialName("download_count") val downloadCount: Int? = null,
    @SerialName("new_download_count") val newDownloadCount: Int? = null,
    @SerialName("from_trusted") val fromTrusted: Boolean? = null,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean? = null,
    val hd: Boolean? = null,
    val fps: Double? = null,
    val votes: Int? = null,
    val release: String = "",
    @SerialName("upload_date") val uploadDate: String = "",
    @SerialName("ai_translated") val aiTranslated: Boolean? = null,
    @SerialName("machine_translated") val machineTranslated: Boolean? = null,
    @SerialName("foreign_parts_only") val foreignPartsOnly: Boolean? = null,
    @SerialName("nb_cd") val numberOfCds: Int? = null,
    val ratings: Double? = null,
    val comments: String? = null,
    val uploader: OsSubtitleUploader? = null,
    @SerialName("feature_details") val featureDetails: OsFeatureDetails? = null,
    @SerialName("moviehash_match") val movieHashMatch: Boolean? = null,
)

@Serializable
data class OsSubtitleUploader(
    @SerialName("uploader_id") val uploaderId: Int? = null,
    val name: String? = null,
    val rank: String? = null,
)

@Serializable
data class OsFeatureDetails(
    val title: String? = null,
    @SerialName("movie_name") val movieName: String? = null,
    val year: Int? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("imdb_id") val imdbId: Int? = null,
    @SerialName("parent_imdb_id") val parentImdbId: Int? = null,
    @SerialName("parent_title") val parentTitle: String? = null,
)

@Serializable
data class OsSubtitleFile(
    @SerialName("file_id") val fileId: Int = 0,
    @SerialName("cd_number") val cdNumber: Int = 1,
    @SerialName("file_name") val fileName: String = "",
)

@Serializable
data class OsDownloadRequest(
    @SerialName("file_id") val fileId: Int,
)

@Serializable
data class OsDownloadResponse(
    val link: String = "",
    @SerialName("file_name") val fileName: String = "",
    val remaining: Int = 0,
    val requests: Int = 0,
    @SerialName("reset_time") val resetTime: String = "",
)

data class OsSubtitleResult(
    val subtitleId: String?,
    val fileId: Int,
    val fileName: String,
    val language: String,
    val flagEmoji: String,
    val languageName: String,
    val downloadCount: Int?,
    val recentDownloadCount: Int?,
    val fromTrusted: Boolean?,
    val hearingImpaired: Boolean?,
    val release: String,
    val aiTranslated: Boolean?,
    val machineTranslated: Boolean?,
    val ratings: Double?,
    val voteCount: Int?,
    val fps: Double?,
    val hd: Boolean?,
    val forced: Boolean?,
    val uploadDate: String?,
    val uploaderName: String?,
    val uploaderRank: String?,
    val comments: String?,
    val numberOfCds: Int?,
    val movieHashMatch: Boolean?,
    val mediaTitle: String?,
    val mediaYear: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val mediaImdbId: Int?,
    val parentImdbId: Int?,
)

/** One provider page plus enough diagnostics to prove whether the provider actually answered. */
data class OpenSubtitlesSearchPage(
    val subtitles: List<OsSubtitleResult>,
    val page: Int,
    val totalCount: Int?,
    val totalPages: Int?,
    /** Sanitized status only; never contains request URLs, API keys, or response bodies. */
    val failure: String? = null,
)

fun languageInfo(code: String): Pair<String, String> {
    val lower = code.lowercase().trim()
    // Normalise 3-letter ISO 639-2 → 2-letter ISO 639-1
    val c = when (lower) {
        // Full English names (some addons send these)
        "english" -> "en"; "german", "deutsch" -> "de"; "french", "français" -> "fr"
        "spanish", "español" -> "es"; "italian", "italiano" -> "it"; "portuguese", "português" -> "pt"
        "dutch", "nederlands" -> "nl"; "polish", "polski" -> "pl"; "russian", "русский" -> "ru"
        "turkish", "türkçe" -> "tr"; "arabic", "العربية" -> "ar"; "chinese", "中文" -> "zh"
        "japanese", "日本語" -> "ja"; "korean", "한국어" -> "ko"; "czech", "čeština" -> "cs"
        "swedish", "svenska" -> "sv"; "danish", "dansk" -> "da"; "finnish", "suomi" -> "fi"
        "norwegian", "norsk" -> "nb"; "hungarian", "magyar" -> "hu"; "romanian", "română" -> "ro"
        "croatian", "hrvatski" -> "hr"; "slovak", "slovenčina" -> "sk"; "greek", "ελληνικά" -> "el"
        "hebrew", "עברית" -> "he"; "thai", "ไทย" -> "th"; "vietnamese", "tiếng việt" -> "vi"
        "indonesian", "bahasa indonesia" -> "id"; "ukrainian", "українська" -> "uk"
        "bulgarian", "български" -> "bg"; "serbian", "српски" -> "sr"
        "slovenian", "slovenščina" -> "sl"; "catalan", "català" -> "ca"
        "albanian", "shqip", "alb", "sqi" -> "sq"
        // 3-letter ISO 639-2 codes
        "eng" -> "en"; "deu", "ger" -> "de"; "fre", "fra" -> "fr"
        "spa", "esl" -> "es"; "ita" -> "it"; "por" -> "pt"
        "nld", "dut" -> "nl"; "pol" -> "pl"; "rus" -> "ru"
        "tur" -> "tr"; "ara" -> "ar"; "zho", "chi" -> "zh"
        "jpn" -> "ja"; "kor" -> "ko"; "ces", "cze" -> "cs"
        "swe" -> "sv"; "dan" -> "da"; "fin" -> "fi"
        "nor", "nob" -> "nb"; "hun" -> "hu"; "ron", "rum" -> "ro"
        "hrv" -> "hr"; "slk", "slo" -> "sk"; "ell", "gre" -> "el"
        "heb" -> "he"; "tha" -> "th"; "vie" -> "vi"; "ind" -> "id"
        "ukr" -> "uk"; "bul" -> "bg"; "srp" -> "sr"; "slv" -> "sl"
        "cat" -> "ca"
        else -> lower.take(2)
    }
    return when (c) {
        "en" -> "🇬🇧" to "English"
        "de" -> "🇩🇪" to "German"
        "fr" -> "🇫🇷" to "French"
        "es" -> "🇪🇸" to "Spanish"
        "it" -> "🇮🇹" to "Italian"
        "pt" -> "🇵🇹" to "Portuguese"
        "nl" -> "🇳🇱" to "Dutch"
        "pl" -> "🇵🇱" to "Polish"
        "ru" -> "🇷🇺" to "Russian"
        "tr" -> "🇹🇷" to "Turkish"
        "ar" -> "🇸🇦" to "Arabic"
        "zh" -> "🇨🇳" to "Chinese"
        "ja" -> "🇯🇵" to "Japanese"
        "ko" -> "🇰🇷" to "Korean"
        "cs" -> "🇨🇿" to "Czech"
        "sv" -> "🇸🇪" to "Swedish"
        "da" -> "🇩🇰" to "Danish"
        "fi" -> "🇫🇮" to "Finnish"
        "nb" -> "🇳🇴" to "Norwegian"
        "hu" -> "🇭🇺" to "Hungarian"
        "ro" -> "🇷🇴" to "Romanian"
        "hr" -> "🇭🇷" to "Croatian"
        "sk" -> "🇸🇰" to "Slovak"
        "el" -> "🇬🇷" to "Greek"
        "he" -> "🇮🇱" to "Hebrew"
        "th" -> "🇹🇭" to "Thai"
        "vi" -> "🇻🇳" to "Vietnamese"
        "id" -> "🇮🇩" to "Indonesian"
        "uk" -> "🇺🇦" to "Ukrainian"
        "bg" -> "🇧🇬" to "Bulgarian"
        "sr" -> "🇷🇸" to "Serbian"
        "sl" -> "🇸🇮" to "Slovenian"
        "ca" -> "🇪🇸" to "Catalan"
        "sq" -> "🇦🇱" to "Albanian"
        "no" -> "🇳🇴" to "Norwegian"
        else -> "🌐" to code.uppercase()
    }
}

fun subtitleLanguagesMatch(left: String?, right: String?): Boolean {
    if (left.isNullOrBlank() || right.isNullOrBlank()) return false
    return normalizeSubtitleLanguageCode(left) == normalizeSubtitleLanguageCode(right)
}

/** ISO-639-1 code accepted by OpenSubtitles for Torve's configured language values. */
fun normalizeSubtitleLanguageCode(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when (languageInfo(raw).second.lowercase()) {
        "english" -> "en"; "german" -> "de"; "french" -> "fr"; "spanish" -> "es"
        "italian" -> "it"; "portuguese" -> "pt"; "dutch" -> "nl"; "polish" -> "pl"
        "russian" -> "ru"; "turkish" -> "tr"; "arabic" -> "ar"; "chinese" -> "zh"
        "japanese" -> "ja"; "korean" -> "ko"; "czech" -> "cs"; "swedish" -> "sv"
        "danish" -> "da"; "finnish" -> "fi"; "norwegian" -> "nb"; "hungarian" -> "hu"
        "romanian" -> "ro"; "croatian" -> "hr"; "slovak" -> "sk"; "greek" -> "el"
        "hebrew" -> "he"; "thai" -> "th"; "vietnamese" -> "vi"; "indonesian" -> "id"
        "ukrainian" -> "uk"; "bulgarian" -> "bg"; "serbian" -> "sr"; "slovenian" -> "sl"
        "catalan" -> "ca"; "albanian" -> "sq"
        else -> raw.lowercase().take(2).takeIf { it.length == 2 }
    }
}
