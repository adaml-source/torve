package com.torve.data.channels

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppleEpgStreamingParserTest {
    private fun db(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }
    private fun reader(xml: String, chunkSize: Int = 3): suspend (ByteArray) -> Int {
        val bytes = xml.encodeToByteArray(); var cursor = 0
        return { target ->
            if (cursor == bytes.size) -1 else {
                val count = minOf(chunkSize, target.size, bytes.size - cursor)
                bytes.copyInto(target, 0, cursor, cursor + count); cursor += count; count
            }
        }
    }
    private fun xml(programmes: String = "<programme channel='news' start='20261010060000 +0000' stop='20261010070000 +0000'><title>München &amp; Berlin &#x1F4FA;</title></programme>") =
        "<?xml version='1.0'?><!DOCTYPE tv SYSTEM 'xmltv.dtd'><tv><channel id='news'><display-name><![CDATA[News & Culture]]></display-name><icon src='https://example.com/icon.png'/></channel>$programmes</tv>"

    @Test fun splitUtf8EntitiesAndCdataKeepArtworkAndTitlesAndMapCanonicalKeys() = runTest {
        val database = db()
        val stats = EpgParser().parseXmlTvToDbStreaming(reader(xml(), 1), database, "user", "playlist", 2,
            0, Long.MAX_VALUE, setOf("playlist::news"), { _, name -> if (name == "News & Culture") "playlist::news" else null }, 75)
        assertEquals(1, stats.programmesKept)
        val programme = database.torveQueries.getAllEpgProgrammeRowsForPlaylistGeneration("user", "playlist", 2).executeAsOne()
        assertEquals("playlist::news", programme.epg_channel_key)
        assertEquals("München & Berlin 📺", programme.title)
        assertEquals(1, stats.channelsMatched)
    }
    @Test fun truncatedFeedRollsBackOnlyItsStagedGeneration() = runTest {
        val database = db()
        EpgParser().parseXmlTvToDbStreaming(reader(xml()), database, "user", "playlist", 1, 0, Long.MAX_VALUE, null, null, 75)
        val failure = runCatching {
            EpgParser().parseXmlTvToDbStreaming(reader(xml().dropLast(5)), database, "user", "playlist", 2, 0, Long.MAX_VALUE, null, null, 75)
        }
        assertTrue(failure.isFailure)
        assertEquals(1L, database.torveQueries.countEpgProgrammesForPlaylistGeneration("user", "playlist", 1).executeAsOne())
        assertEquals(0L, database.torveQueries.countEpgProgrammesForPlaylistGeneration("user", "playlist", 2).executeAsOne())
    }
    @Test fun programmeWindowChannelFilterAndPerChannelCapAreEnforced() = runTest {
        val database = db()
        val programme = "<programme channel='news' start='20261010060000 +0000' stop='20261010070000 +0000'><title>News</title></programme>"
        val stats = EpgParser().parseXmlTvToDbStreaming(reader(xml(programme.repeat(4)), 512), database, "user", "playlist", 1,
            0, Long.MAX_VALUE, setOf("news"), null, 50, maxProgrammesPerChannel = 2)
        assertEquals(2, stats.programmesKept); assertEquals(2, stats.programmesSkippedByCap)
        val filtered = EpgParser().parseXmlTvToDbStreaming(reader(xml()), database, "user", "playlist", 2,
            0, Long.MAX_VALUE, setOf("other"), null, 50)
        assertEquals(0, filtered.programmesKept); assertEquals(1, filtered.programmesSkippedByChannelFilter)
        val outside = EpgParser().parseXmlTvToDbStreaming(reader(xml()), database, "user", "playlist", 3,
            0, 1, null, null, 50)
        assertEquals(1, outside.programmesSkippedByWindow)
    }
    @Test fun declaredEntitiesAndHtmlAreRejectedWithoutNetworkResolution() = runTest {
        for (document in listOf("<html></html>", "<!DOCTYPE tv [<!ENTITY x SYSTEM 'file:///secret'>]><tv></tv>", "<tv><channel id='x'></tv>")) {
            assertTrue(runCatching { EpgParser().parseXmlTvToDbStreaming(reader(document), db(), "user", "playlist", 1,
                0, Long.MAX_VALUE, null, null, 75) }.isFailure)
        }
    }
    @Test fun totalCapStopsScanningWithoutMaterializingTheFeed() = runTest {
        val stats = EpgParser().parseXmlTvToDbStreaming(reader(xml("<programme channel='news' start='20261010060000 +0000' stop='20261010070000 +0000'><title>News</title></programme>".repeat(50))),
            db(), "user", "playlist", 1, 0, Long.MAX_VALUE, null, null, 75, maxProgrammesTotal = 3)
        assertTrue(stats.abortedByGlobalCap); assertEquals(3, stats.programmesKept)
    }

    @Test fun programmeDetailsSurviveChunkedXmlAndWindowedSqlReads() = runTest {
        val database = db()
        val programme = "<programme channel='news' start='20261010060000 +0000' stop='20261010070000 +0000'>" +
            "<title>Local guide programme</title><sub-title><![CDATA[München & Berlin]]></sub-title>" +
            "<desc>Original programme details &amp; café &#x1F4FA;.</desc></programme>"
        EpgParser().parseXmlTvToDbStreaming(reader(xml(programme), 1), database, "user", "playlist", 2,
            0, Long.MAX_VALUE, null, null, 75)
        val row = database.torveQueries.getEpgProgrammesForChannelWindowLimited(
            "user", "playlist", 2, "news", 0, Long.MAX_VALUE, 240).executeAsOne()
        assertEquals("München & Berlin", row.sub_title)
        assertEquals("Original programme details & café 📺.", row.description)
        assertEquals(row.description, database.torveQueries.getEpgProgrammesForPlaylistWindowLimited(
            "user", "playlist", 2, 0, Long.MAX_VALUE, 240).executeAsOne().description)
    }

    @Test fun descriptionMemoryIsBoundedAndDoesNotLeakIntoTheNextProgramme() = runTest {
        val database = db()
        val first = "<programme channel='news' start='20261010060000 +0000' stop='20261010070000 +0000'>" +
            "<title>First</title><desc>${"x".repeat(20_000)}</desc></programme>"
        val second = "<programme channel='news' start='20261010070000 +0000' stop='20261010080000 +0000'>" +
            "<title>Second</title></programme>"
        EpgParser().parseXmlTvToDbStreaming(reader(xml(first + second), 7), database, "user", "playlist", 2,
            0, Long.MAX_VALUE, null, null, 75)
        val rows = database.torveQueries.getAllEpgProgrammeRowsForPlaylistGeneration("user", "playlist", 2).executeAsList()
        assertEquals(2048, rows.first().description?.length)
        assertNull(rows.last().description)
        assertNull(rows.last().sub_title)
    }

    @Test fun detailsMigrationKeepsSchedulesAndPreferencesAndRefreshesOldGuideData() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, """CREATE TABLE iptv_epg_programme (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL DEFAULT '',
                playlist_id TEXT NOT NULL, generation_id INTEGER NOT NULL, channel_id TEXT NOT NULL,
                epg_channel_key TEXT NOT NULL, xmltv_channel_id TEXT, start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL, title TEXT NOT NULL)""", 0)
            driver.execute(null, "CREATE TABLE preference (user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (user_id, key))", 0)
            driver.execute(null, "INSERT INTO iptv_epg_programme VALUES (1,'user','playlist',2,'news','news','news',1,2,'Retained')", 0)
            driver.execute(null, "INSERT INTO preference VALUES ('user','appearance','dark'),('user','epg_active_generation_playlist','2'),('user','epg_load_state_playlist','old')", 0)
            TorveDatabase.Schema.migrate(driver, TorveDatabase.Schema.version - 1, TorveDatabase.Schema.version)
            val database = TorveDatabase(driver)
            val row = database.torveQueries.getAllEpgProgrammeRowsForPlaylistGeneration("user", "playlist", 2).executeAsOne()
            assertEquals("Retained", row.title)
            assertNull(row.description)
            assertEquals("dark", database.torveQueries.getPreference("user", "appearance").executeAsOne())
            assertEquals("2", database.torveQueries.getPreference("user", "epg_active_generation_playlist").executeAsOne())
            assertNull(database.torveQueries.getPreference("user", "epg_load_state_playlist").executeAsOneOrNull())
        } finally { driver.close() }
    }
}
