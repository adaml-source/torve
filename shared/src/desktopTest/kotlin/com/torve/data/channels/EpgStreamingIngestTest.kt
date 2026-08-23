package com.torve.data.channels

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class EpgStreamingIngestTest {
    private val m3u4uFixture = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="bundesliga.1">
            <display-name>Sky Sport Bundesliga HD</display-name>
          </channel>
          <programme start="20260823153000 +0200" stop="20260823173000 +0200" channel="bundesliga.1">
            <title>Bundesliga</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun binaryMimeStylePlainXmlUsesExactTvgIdDespiteDifferentVisibleName() = runTest {
        val result = ingest(m3u4uFixture.encodeToByteArray(), contentEncoding = "") { id, _ ->
            if (id == "bundesliga.1") "playlist::bundesliga.1" else null
        }

        assertEquals(1, result.stats.channelsSeen)
        assertEquals(1, result.stats.channelsMatched)
        assertEquals(1, result.stats.totalProgrammesSeen)
        assertEquals(1, result.stats.programmesKept)
        assertEquals(1L, result.db.torveQueries.countEpgProgrammesForPlaylistGeneration("user", "playlist", 7).executeAsOne())
        val row = result.db.torveQueries
            .getAllEpgProgrammeRowsForPlaylistGeneration("user", "playlist", 7)
            .executeAsOne()
        assertEquals("playlist::bundesliga.1", row.epg_channel_key)
        assertEquals(1_787_491_800_000L, row.start_time)
    }

    @Test
    fun gzipMagicIsDetectedWithoutXmlUrlOrXmlMime() = runTest {
        val compressed = gzip(m3u4uFixture.encodeToByteArray())
        val result = ingest(compressed, contentEncoding = "") { id, _ ->
            if (id == "bundesliga.1") "playlist::bundesliga.1" else null
        }

        assertTrue(result.transport.isGzipDetected)
        assertEquals(1, result.stats.programmesKept)
    }

    @Test
    fun alreadyDecodedBodyWithStaleGzipHeaderStillParsesAsXml() = runTest {
        val result = ingest(m3u4uFixture.encodeToByteArray(), contentEncoding = "gzip") { id, _ ->
            if (id == "bundesliga.1") "playlist::bundesliga.1" else null
        }

        assertTrue(!result.transport.isGzipDetected)
        assertEquals(1, result.stats.programmesKept)
    }

    @Test
    fun channelDisplayNameFallbackIsCachedForProgrammeRows() = runTest {
        val result = ingest(m3u4uFixture.encodeToByteArray(), contentEncoding = "") { id, displayName ->
            when {
                id == "expected-id" -> "playlist::expected-id"
                displayName == "Sky Sport Bundesliga HD" -> "playlist::name-match"
                else -> null
            }
        }

        assertEquals(1, result.stats.channelsMatched)
        assertEquals(1, result.stats.programmesKept)
        val row = result.db.torveQueries
            .getAllEpgProgrammeRowsForPlaylistGeneration("user", "playlist", 7)
            .executeAsOne()
        assertEquals("playlist::name-match", row.epg_channel_key)
    }

    @Test
    fun unmatchedGlobalProgrammesSkipBeforeTimestampParsing() = runTest {
        val xml = m3u4uFixture.replace(
            "<programme start=\"20260823153000 +0200\" stop=\"20260823173000 +0200\" channel=\"bundesliga.1\">",
            "<programme start=\"not-a-date\" stop=\"also-invalid\" channel=\"unrelated.global.channel\">",
        )
        val result = ingest(xml.encodeToByteArray(), contentEncoding = "") { id, _ ->
            if (id == "bundesliga.1") "playlist::bundesliga.1" else null
        }

        assertEquals(1, result.stats.programmesSkippedByNoMapping)
        assertEquals(0, result.stats.programmesSkippedByInvalidTime)
        assertEquals(0, result.stats.programmesKept)
    }

    @Test
    fun malformedXmlClearsOnlyStagedGeneration() = runTest {
        val db = freshDb()
        db.torveQueries.insertEpgProgramme(
            user_id = "user",
            playlist_id = "playlist",
            generation_id = 1,
            channel_id = "playlist::existing",
            epg_channel_key = "playlist::existing",
            xmltv_channel_id = "existing",
            start_time = 1,
            end_time = 2,
            title = "Existing guide",
        )
        val file = File.createTempFile("torve_epg_malformed_", ".xml")
        file.writeText("<tv><channel id=\"broken\">")
        try {
            val result = runCatching {
                GzipSupport.parseXmlTvAutoFromFileToDbOrNull(
                    tempFilePath = file.absolutePath,
                    parser = EpgParser(),
                    db = db,
                    userId = "user",
                    playlistId = "playlist",
                    generationId = 8,
                    windowStartMs = 0,
                    windowEndMs = Long.MAX_VALUE,
                    contentEncoding = "",
                    contentLength = file.length(),
                    maxUncompressedBytes = 1024 * 1024,
                    channelFilter = null,
                    resolveEpgChannelKey = { id, _ -> "playlist::$id" },
                    batchSize = 100,
                    maxProgrammesPerChannel = 240,
                    maxProgrammesTotal = 1_000,
                    onProgress = null,
                )
            }
            assertTrue(result.isFailure)
            assertEquals(0L, db.torveQueries.countEpgProgrammesForPlaylistGeneration("user", "playlist", 8).executeAsOne())
            assertEquals(1L, db.torveQueries.countEpgProgrammesForPlaylistGeneration("user", "playlist", 1).executeAsOne())
        } finally {
            file.delete()
        }
    }

    /**
     * Opt-in production-shape regression. The fixture paths are supplied by
     * the release verification job and are never committed with the test.
     */
    @Test
    fun largeAggregateXmltvFixtureStreamsAndKeepsOnlyPlaylistMatches() = runTest {
        fun fixturePath(property: String, environment: String, filename: String): String? =
            System.getProperty(property)
                ?: System.getenv(environment)
                ?: listOf(File(".codex-deploy", filename), File("../.codex-deploy", filename))
                    .firstOrNull(File::isFile)
                    ?.absolutePath
        val epgPath = fixturePath(
            "torve.epgLargeFixture",
            "TORVE_EPG_LARGE_FIXTURE",
            "torve_epg_large_fixture.payload",
        ) ?: return@runTest
        val m3uPath = fixturePath(
            "torve.m3uLargeFixture",
            "TORVE_M3U_LARGE_FIXTURE",
            "torve_m3u_large_fixture.m3u",
        ) ?: return@runTest
        val playlist = M3uParser().parse(File(m3uPath).readText(), playlistId = "playlist")
        val exactMapping = playlist.channels
            .mapNotNull { channel -> channel.tvgId?.trim()?.takeIf(String::isNotEmpty)?.let { it to "playlist::$it" } }
            .toMap()
        val db = freshDb()
        val started = TimeSource.Monotonic.markNow()
        val result = requireNotNull(
            GzipSupport.parseXmlTvAutoFromFileToDbOrNull(
                tempFilePath = epgPath,
                parser = EpgParser(),
                db = db,
                userId = "user",
                playlistId = "playlist",
                generationId = 9,
                windowStartMs = 1_786_665_600_000L,
                windowEndMs = 1_787_788_800_000L,
                contentEncoding = "",
                contentLength = File(epgPath).length(),
                maxUncompressedBytes = 768L * 1024L * 1024L,
                channelFilter = exactMapping.values.toSet(),
                resolveEpgChannelKey = { id, _ -> exactMapping[id.trim()] },
                batchSize = 200,
                maxProgrammesPerChannel = 240,
                maxProgrammesTotal = 150_000,
                onProgress = null,
            ),
        )

        println(
            "TorvePerfTest: largeEpg parseMs=${started.elapsedNow().inWholeMilliseconds} " +
                "bytesParsed=${result.bytesParsed} channels=${result.stats.channelsSeen} " +
                "matched=${result.stats.channelsMatched} programmes=${result.stats.totalProgrammesSeen} " +
                "saved=${result.stats.programmesKept} decompressionReadMs=${result.decompressionAndReadDurationMs}",
        )
        val bundesligaPlaylistIds = exactMapping.keys.count { it.contains("bundesliga", ignoreCase = true) }
        val bundesligaProgrammeRows = db.torveQueries
            .getAllEpgProgrammeRowsForPlaylistGeneration("user", "playlist", 9)
            .executeAsList()
            .count { it.xmltv_channel_id?.contains("bundesliga", ignoreCase = true) == true }
        println(
            "TorvePerfTest: bundesliga playlistIds=$bundesligaPlaylistIds savedProgrammes=$bundesligaProgrammeRows",
        )
        assertTrue(result.isGzipDetected)
        assertEquals(422_758_334L, result.bytesParsed)
        assertEquals(15_616, result.stats.channelsSeen)
        assertEquals(1_188_046, result.stats.totalProgrammesSeen)
        assertEquals(64, result.stats.channelsMatched)
        assertTrue(result.stats.programmesKept > 0)
        assertTrue(!result.stats.abortedByGlobalCap)
        if (bundesligaPlaylistIds > 0) assertTrue(bundesligaProgrammeRows > 0)
    }

    private suspend fun ingest(
        body: ByteArray,
        contentEncoding: String,
        resolver: (String, String?) -> String?,
    ): IngestResult {
        val db = freshDb()
        val file = File.createTempFile("torve_epg_ingest_", ".payload")
        file.writeBytes(body)
        try {
            val transport = requireNotNull(
                GzipSupport.parseXmlTvAutoFromFileToDbOrNull(
                    tempFilePath = file.absolutePath,
                    parser = EpgParser(),
                    db = db,
                    userId = "user",
                    playlistId = "playlist",
                    generationId = 7,
                    windowStartMs = 1_700_000_000_000L,
                    windowEndMs = 1_900_000_000_000L,
                    contentEncoding = contentEncoding,
                    contentLength = file.length(),
                    maxUncompressedBytes = 16L * 1024L * 1024L,
                    channelFilter = null,
                    resolveEpgChannelKey = resolver,
                    batchSize = 100,
                    maxProgrammesPerChannel = 240,
                    maxProgrammesTotal = 1_000,
                    onProgress = null,
                ),
            )
            return IngestResult(db, transport, transport.stats)
        } finally {
            file.delete()
        }
    }

    private fun freshDb(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val file = File.createTempFile("torve_epg_gzip_", ".gz")
        return try {
            FileOutputStream(file).use { output ->
                GZIPOutputStream(output).use { it.write(bytes) }
            }
            file.readBytes()
        } finally {
            file.delete()
        }
    }

    private data class IngestResult(
        val db: TorveDatabase,
        val transport: EpgStreamIngestResult,
        val stats: EpgDbParseStats,
    )
}
