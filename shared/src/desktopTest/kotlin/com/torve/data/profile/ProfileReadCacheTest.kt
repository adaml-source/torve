package com.torve.data.profile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import com.torve.domain.model.ContentRating
import kotlin.test.*

class ProfileReadCacheTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = TorveDatabase(driver).also { TorveDatabase.Schema.create(driver) }
    private val cache = ProfileReadCache(db)
    @AfterTest fun close() { driver.close() }
    private fun add(account: String, id: String, rating: String?, active: Long = 1) {
        db.torveQueries.insertProfile(account, id, id, 0, active, null, rating, 1)
    }

    @Test fun repeatedReadsReuseRowsUntilAnActualProfileWrite() {
        add("a", "default", "PG")
        val rows = cache.profiles("a")
        repeat(100) { assertSame(rows, cache.profiles("a")); assertEquals(ContentRating.PG, cache.ratingLimit("a", "default")) }
        db.torveQueries.updateProfileRating("G", "a", "default")
        assertNotSame(rows, cache.profiles("a"))
        assertEquals(ContentRating.G, cache.ratingLimit("a", "default"))
    }

    @Test fun switchingProfileAndAccountNeverReusesAnotherOwnersPolicy() {
        add("a", "default", "R"); add("a", "kids", "G", 0)
        add("b", "default", "PG_13")
        assertEquals("default", cache.activeProfileId("a"))
        db.torveQueries.setActiveProfile("kids", "a")
        assertEquals("kids", cache.activeProfileId("a"))
        assertEquals(ContentRating.G, cache.ratingLimit("a", "kids"))
        assertEquals(ContentRating.PG_13, cache.ratingLimit("b", "default"))
        assertNull(cache.activeProfileId("signed-out"))
        assertNull(cache.ratingLimit("signed-out", "default"))
        assertEquals(ContentRating.G, cache.ratingLimit("a", "kids"))
    }

    @Test fun transactionImportsAndDeletionsInvalidateImmediately() {
        assertNull(cache.activeProfileId("a"))
        db.transaction { add("a", "default", null); add("a", "child", "PG", 0) }
        assertEquals(2, cache.profiles("a").size)
        db.transaction { db.torveQueries.updateProfileRating("G", "a", "child") }
        assertEquals(ContentRating.G, cache.ratingLimit("a", "child"))
        db.torveQueries.deleteProfile("a", "child")
        assertNull(cache.ratingLimit("a", "child"))
        assertEquals(1, cache.profiles("a").size)
    }
}
