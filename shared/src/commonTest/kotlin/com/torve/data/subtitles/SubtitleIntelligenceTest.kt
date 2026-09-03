package com.torve.data.subtitles

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.SourceProfile
import kotlinx.serialization.json.Json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubtitleIntelligenceTest {
    private val intelligence = SubtitleIntelligence()
    private val active = fingerprint(
        "The.Big.Bang.Theory.S07E11.1080p.BluRay.x264-DEMAND",
        season = 7,
        episode = 11,
    )

    @Test
    fun realReleaseScenarioRanksExactThenStrongAndRejectsWrongEpisode() {
        val exact = candidate("The.Big.Bang.Theory.S07E11.1080p.BluRay.x264-DEMAND")
        val sameFamily720 = candidate("The.Big.Bang.Theory.S07E11.720p.BluRay.x264-DEMAND")
        val web = candidate("The.Big.Bang.Theory.S07E11.1080p.WEB-DL.DD5.1.H264-NTb")
        val hdtv = candidate("The.Big.Bang.Theory.S07E11.HDTV.x264-LOL")
        val wrongEpisode = candidate("The.Big.Bang.Theory.S07E12.1080p.BluRay.x264-DEMAND")

        val ranked = intelligence.rank(active, listOf(hdtv, wrongEpisode, web, sameFamily720, exact), 7, 11)

        assertEquals(exact, ranked[0].subtitle)
        assertEquals(SubtitleMatchTier.EXACT_RELEASE, ranked[0].tier)
        assertEquals(sameFamily720, ranked[1].subtitle)
        assertEquals(SubtitleMatchTier.STRONG_RELEASE_MATCH, ranked[1].tier)
        assertTrue(ranked.indexOfFirst { it.subtitle == web } < ranked.indexOfFirst { it.subtitle == hdtv })
        assertEquals(SubtitleMatchTier.REJECTED, ranked.last().tier)
        assertEquals(wrongEpisode, ranked.last().subtitle)
    }

    @Test
    fun exactHashWithPoorPopularityAlwaysBeatsPopularGeneric() {
        val exactHash = candidate(
            release = "The Big Bang Theory S07E11",
            rating = 7.5,
            votes = 8,
            downloads = 80,
            movieHashMatch = true,
        )
        val popularGeneric = candidate(
            release = "The Big Bang Theory S07E11 generic",
            rating = 10.0,
            votes = 2_000,
            downloads = 50_000,
        )

        val ranked = intelligence.rank(active, listOf(popularGeneric, exactHash), 7, 11)

        assertEquals(exactHash, ranked.first().subtitle)
        assertEquals(SubtitleMatchTier.EXACT_FILE, ranked.first().tier)
        assertEquals(100, ranked.first().subtitleMatchScore)
    }

    @Test
    fun confidenceAdjustedRatingPrefers94From500VotesOver10FromOne() {
        val oneVote = candidate("Generic.S07E11", rating = 10.0, votes = 1)
        val proven = candidate("Generic.S07E11", rating = 9.4, votes = 500)

        assertTrue(intelligence.publicQualityScore(proven) > intelligence.publicQualityScore(oneVote))
    }

    @Test
    fun sourceSwitchChangesFingerprintCacheKeyAndRanking() {
        val groupA = fingerprint("Show.S02E05.1080p.WEB-DL-GROUPA", 2, 5)
        val groupB = fingerprint("Show.S02E05.1080p.BluRay-GROUPB", 2, 5)
        val subtitleA = candidate("Show.S02E05.1080p.WEB-DL-GROUPA")
        val subtitleB = candidate("Show.S02E05.1080p.BluRay-GROUPB")

        assertEquals(subtitleA, intelligence.rank(groupA, listOf(subtitleB, subtitleA), 2, 5).first().subtitle)
        assertEquals(subtitleB, intelligence.rank(groupB, listOf(subtitleA, subtitleB), 2, 5).first().subtitle)
        assertNotEquals(groupA.subtitleCacheFingerprint(), groupB.subtitleCacheFingerprint())
    }

    @Test
    fun autoplayFingerprintUsesNewEpisodeAndCannotRankPreviousEpisode() {
        val episode12 = fingerprint(
            "The.Big.Bang.Theory.S07E12.1080p.BluRay.x264-DEMAND",
            season = 7,
            episode = 12,
        )
        val oldSubtitle = candidate("The.Big.Bang.Theory.S07E11.1080p.BluRay.x264-DEMAND")
        val newSubtitle = candidate("The.Big.Bang.Theory.S07E12.1080p.BluRay.x264-DEMAND")

        val ranked = intelligence.rank(episode12, listOf(oldSubtitle, newSubtitle), 7, 12)

        assertEquals(newSubtitle, ranked.first().subtitle)
        assertEquals(SubtitleMatchTier.REJECTED, ranked.last().tier)
    }

    @Test
    fun clearlyDifferentMovieTitleIsRejectedEvenWhenPopular() {
        val video = fingerprint("Dune.Part.Two.2024.1080p.BluRay-GROUP")
        val wrongMovie = candidate("The.Fall.Guy.2024.1080p.BluRay-GROUP", rating = 10.0, votes = 500, downloads = 50_000)

        val ranked = intelligence.score(video, wrongMovie)

        assertEquals(SubtitleMatchTier.REJECTED, ranked.tier)
    }

    @Test
    fun repackProperAndPunctuationAreParsedDeterministically() {
        val parsed = parseSubtitleRelease("show_name_S01E02_1080p_WEB-DL_REPACK_PROPER_x265-GROUP.srt")
        assertEquals("show name", parsed.titleStem)
        assertEquals(1, parsed.seasonNumber)
        assertEquals(2, parsed.episodeNumber)
        assertEquals("web-dl", parsed.sourceType)
        assertEquals("hevc", parsed.videoCodec)
        assertEquals("group", parsed.releaseGroup)
        assertTrue(parsed.repack)
        assertTrue(parsed.proper)
    }

    @Test
    fun webDlVsWebRipAndBlurayVsWebRemainConflicts() {
        val webDl = fingerprint("Show.S01E02.1080p.WEB-DL-GROUP", 1, 2)
        val webRip = intelligence.score(webDl, candidate("Show.S01E02.1080p.WEBRip-GROUP"), 1, 2)
        val bluray = intelligence.score(webDl, candidate("Show.S01E02.1080p.BluRay-GROUP"), 1, 2)

        assertTrue(webRip.reasons.any { it.description.contains("vs") && it.limitation })
        assertTrue(bluray.reasons.any { it.description.contains("vs") && it.limitation })
        assertTrue(webRip.tier.priority >= SubtitleMatchTier.POOR_MATCH.priority)
        assertTrue(bluray.tier.priority >= SubtitleMatchTier.POOR_MATCH.priority)
    }

    @Test
    fun missingGroupAndFpsStayUnknownInsteadOfRejecting() {
        val result = intelligence.score(active, candidate("The Big Bang Theory S07E11 BluRay"), 7, 11)
        assertTrue(result.tier != SubtitleMatchTier.REJECTED)
        assertFalse(result.reasons.any { it.description.contains("FPS") })
    }

    @Test
    fun fps23976And24AreCompatibleBut25IsNot() {
        val fingerprint = fingerprint("Show.S01E01.1080p.BluRay.23.976fps-GROUP", 1, 1)
        val near = intelligence.score(fingerprint, candidate("Show.S01E01.BluRay.24fps-GROUP", fps = 24.0), 1, 1)
        val pal = intelligence.score(fingerprint, candidate("Show.S01E01.BluRay.25fps-GROUP", fps = 25.0), 1, 1)

        assertTrue(near.reasons.any { it.description == "Compatible FPS" })
        assertTrue(pal.reasons.any { it.description == "Conflicting FPS family" })
        assertTrue(near.subtitleMatchScore > pal.subtitleMatchScore)
    }

    @Test
    fun seasonPackIsNotFalselyRejectedAsWrongEpisode() {
        val pack = intelligence.score(active, candidate("The.Big.Bang.Theory.S07.1080p.BluRay-DEMAND"), 7, 11)
        assertTrue(pack.tier != SubtitleMatchTier.REJECTED)
    }

    @Test
    fun conflictingDirectorsCutIsPenalized() {
        val theatrical = fingerprint("Film.2024.1080p.BluRay.THEATRICAL-GROUP")
        val directors = intelligence.score(theatrical, candidate("Film.2024.1080p.BluRay.Directors.Cut-GROUP"))
        assertTrue(directors.reasons.any { it.description == "Conflicting cut/edition" })
        assertTrue(directors.tier.priority >= SubtitleMatchTier.GENERIC_MATCH.priority)
    }

    @Test
    fun missingRatingAndZeroVotesRemainDistinctMetadataStates() {
        val missing = candidate("Show.S07E11", rating = null, votes = null)
        val zeroVotes = candidate("Show.S07E11", rating = 0.0, votes = 0)
        assertEquals(null, missing.rating)
        assertEquals(null, missing.voteCount)
        assertTrue(intelligence.publicQualityScore(missing) >= 0)
        assertTrue(intelligence.publicQualityScore(zeroVotes) >= 0)
    }

    @Test
    fun trustedUploaderHelpsAndMachineTranslationHurtsWithinSameTier() {
        val trusted = candidate("Generic.S07E11", trusted = true)
        val machine = candidate("Generic.S07E11", machineTranslated = true)
        assertTrue(intelligence.publicQualityScore(trusted) > intelligence.publicQualityScore(machine))
    }

    @Test
    fun cacheSeparatesReleaseFingerprints() {
        val cache = SubtitleSearchCache(ttlMs = 1_000L)
        val a = SubtitleCacheKey("tt1", 1, 2, "en", "group-a")
        val b = SubtitleCacheKey("tt1", 1, 2, "en", "group-b")
        val result = intelligence.rank(active, listOf(candidate("Show.S07E11")), 7, 11)
        cache.put(a, result, nowMs = 100L)
        assertEquals(result, cache.get(a, nowMs = 200L))
        assertEquals(null, cache.get(b, nowMs = 200L))
        assertEquals(null, cache.get(a, nowMs = 1_200L))
    }

    @Test
    fun osHashUsesFileSizeAndLittleEndianWindows() {
        val zeros = ByteArray(64 * 1024)
        assertEquals("0000000000020000", calculateOpenSubtitlesHash(128L * 1024L, zeros, zeros))
        assertEquals(null, calculateOpenSubtitlesHash(1L, zeros, zeros))
    }

    @Test
    fun httpHashReadsOnlyTwoExactRangeWindowsAndRejectsRangeIgnoringServers() = runBlocking {
        val requestedRanges = Channel<String?>(Channel.UNLIMITED)
        val rangedClient = HttpClient(MockEngine { request ->
            requestedRanges.trySend(request.headers[HttpHeaders.Range])
            respond(
                content = ByteArray(64 * 1024),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentLength, (64 * 1024).toString()),
            )
        }) {
            install(HttpTimeout)
        }
        val hash = OpenSubtitlesHashService(rangedClient).calculateForHttp(
            "https://video.example/file.mkv",
            128L * 1024L,
        )
        val capturedRanges = buildSet {
            repeat(2) { requestedRanges.tryReceive().getOrNull()?.let(::add) }
        }
        assertEquals("0000000000020000", hash?.hash, "ranges=$capturedRanges")
        assertEquals(setOf("bytes=0-65535", "bytes=65536-131071"), capturedRanges)

        val rangeIgnoringClient = HttpClient(MockEngine {
            respond(ByteArray(64 * 1024), HttpStatusCode.OK)
        }) {
            install(HttpTimeout)
        }
        assertEquals(
            null,
            OpenSubtitlesHashService(rangeIgnoringClient).calculateForHttp(
                "https://video.example/file.mkv",
                128L * 1024L,
            ),
        )
    }

    @Test
    fun subtitleValidationRequiresCuesAndFlagsRadicalRuntimeMismatch() {
        val valid = validateSubtitleText(
            "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n2\n00:40:00,000 --> 00:40:02,000\nBye",
            videoDurationMs = 2_500_000L,
        )
        assertIs<SubtitleValidationResult.Valid>(valid)
        assertFalse(valid.runtimeSuspicious)
        assertIs<SubtitleValidationResult.Valid>(
            validateSubtitleText("WEBVTT\n\n00:01.000 --> 00:03.250\nHello"),
        )
        assertIs<SubtitleValidationResult.Invalid>(validateSubtitleText("not a subtitle"))

        val suspicious = validateSubtitleText(
            (1..20).joinToString("\n\n") { index ->
                "$index\n00:00:${index.toString().padStart(2, '0')},000 --> 00:00:${(index + 1).toString().padStart(2, '0')},000\nLine"
            },
            videoDurationMs = 7_200_000L,
        )
        assertIs<SubtitleValidationResult.Valid>(suspicious)
        assertTrue(suspicious.runtimeSuspicious)
    }

    @Test
    fun openSubtitlesProviderMetadataRemainsNullableAndIsRetained() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<OsSearchResponse>(
            """{"data":[{"id":"10","attributes":{"subtitle_id":"20","language":"en","files":[{"file_id":30,"file_name":"Show.S01E02.srt"}],"download_count":42318,"new_download_count":120,"from_trusted":true,"hearing_impaired":false,"hd":true,"fps":23.976,"votes":182,"release":"Show.S01E02.1080p.BluRay-GROUP","upload_date":"2026-01-01","ai_translated":false,"machine_translated":false,"foreign_parts_only":false,"nb_cd":1,"ratings":9.4,"moviehash_match":true,"uploader":{"name":"Uploader","rank":"trusted"},"feature_details":{"title":"Show","season_number":1,"episode_number":2}}}]}""",
        )
        val attributes = response.data.single().attributes
        assertEquals(182, attributes.votes)
        assertEquals(23.976, attributes.fps)
        assertEquals(true, attributes.movieHashMatch)
        assertEquals("Uploader", attributes.uploader?.name)
        assertEquals(null, Json { ignoreUnknownKeys = true }.decodeFromString<OsSubtitleAttributes>("{}").votes)
    }

    private fun fingerprint(release: String, season: Int? = null, episode: Int? = null): SourceProfile =
        SourceProfile.from(
            ParsedStream(
                addonName = "Panda",
                quality = release,
                title = release,
                size = "1.4 GB",
                codec = release,
                source = "real-debrid",
                isCached = true,
            ),
            seasonNumber = season,
            episodeNumber = episode,
        )

    private fun candidate(
        release: String,
        rating: Double? = 8.0,
        votes: Int? = 20,
        downloads: Int? = 1_000,
        movieHashMatch: Boolean? = null,
        fps: Double? = null,
        trusted: Boolean? = false,
        machineTranslated: Boolean? = false,
    ) = SubtitleMetadata(
        subtitleId = release,
        subtitleFileId = release.hashCode(),
        provider = "OpenSubtitles.com",
        language = "en",
        subtitleFilename = "$release.srt",
        releaseName = release,
        fps = fps,
        rating = rating,
        voteCount = votes,
        downloadCount = downloads,
        recentDownloadCount = null,
        trustedUploader = trusted,
        uploaderName = null,
        uploaderRank = null,
        hearingImpaired = false,
        forced = false,
        hd = true,
        aiTranslated = false,
        machineTranslated = machineTranslated,
        uploadDate = null,
        movieHashMatch = movieHashMatch,
    )
}
