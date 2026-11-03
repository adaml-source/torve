package com.torve.android.ui.tv

import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.CursorWindow
import android.database.CursorWindowAllocationException
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import coil3.SingletonImageLoader
import com.torve.db.TorveDatabase
import org.junit.Assert.*
import org.junit.Test

class TvMemoryBudgetTest {
    private fun delegate(schema: Boolean = false): SupportSQLiteOpenHelper {
        val callback = if (schema) AndroidSqliteDriver.Callback(TorveDatabase.Schema)
        else object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE preference(user_id TEXT, key TEXT, value TEXT)")
                db.execSQL("CREATE TABLE metadata(id INTEGER PRIMARY KEY, value TEXT)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration
            .builder(InstrumentationRegistry.getInstrumentation().targetContext).name(null).callback(callback).build())
    }

    private fun bounded(delegate: SupportSQLiteOpenHelper, allocate: (Long) -> CursorWindow): SupportSQLiteOpenHelper =
        Class.forName("com.torve.platform.BoundedCursorOpenHelper")
            .getDeclaredConstructor(SupportSQLiteOpenHelper::class.java, Function1::class.java)
            .newInstance(delegate, allocate) as SupportSQLiteOpenHelper

    private fun window(cursor: Cursor): CursorWindow {
        var unwrapped = cursor
        while (unwrapped is CursorWrapper) unwrapped = unwrapped.wrappedCursor
        return (unwrapped as AbstractWindowedCursor).window!!
    }

    @Test fun actualHistoryQueryAndEveryDatabaseOverloadAvoidTheDefaultTwoMiBAllocation() {
        val allocations = mutableListOf<Long>()
        val helper = bounded(delegate(schema = true)) { bytes ->
            allocations += bytes
            if (bytes > 128L * 1024) throw CursorWindowAllocationException("Simulated 2 MiB allocation failure")
            CursorWindow("HistoryRegression", bytes)
        }
        val driver = AndroidSqliteDriver(openHelper = helper)
        try {
            val queries = TorveDatabase(driver).torveQueries
            queries.transaction {
                repeat(2_000) { index -> queries.insertHistory("fixture", "history$index", "media$index", "movie",
                    "History title $index " + "x".repeat(300), null, null, index.toLong(), 1000, null, null, null) }
            }
            val history = queries.getAllHistory("fixture").executeAsList()
            assertEquals(2_000, history.size)
            assertEquals("history1999", history.first().id)
            assertEquals("history0", history.last().id)
            val db = helper.readableDatabase
            val cursors = listOf(db.query("SELECT COUNT(*) FROM watch_history"),
                db.query("SELECT COUNT(*) FROM watch_history WHERE user_id = ?", arrayOf("fixture")),
                db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM watch_history")),
                db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM watch_history"), CancellationSignal()))
            cursors.forEach { cursor -> cursor.use {
                assertTrue(it.moveToFirst()); assertEquals(2_000, it.getInt(0))
                assertFalse(window(it).putString("x".repeat(80_000), 0, 0))
            } }
            assertTrue(allocations.isNotEmpty())
            assertTrue(allocations.all { it <= 128L * 1024 })
        } finally { driver.close() }
    }

    @Test fun largeRowsGrowOnlyWhenNeededAndKeepCountAndBidirectionalNavigation() {
        val allocations = mutableListOf<Long>()
        val helper = bounded(delegate()) { bytes -> allocations += bytes; CursorWindow("LargeRows", bytes) }
        try {
            val db = helper.writableDatabase
            listOf("small", "x".repeat(200_000), "other", "y".repeat(600_000)).forEachIndexed { index, value ->
                db.execSQL("INSERT INTO metadata VALUES (?,?)", arrayOf<Any>(index, value))
            }
            db.query("SELECT value FROM metadata ORDER BY id").use { cursor ->
                assertEquals(4, cursor.count)
                assertTrue(cursor.moveToFirst()); assertEquals("small", cursor.getString(0))
                assertTrue(cursor.moveToNext()); assertEquals(200_000, cursor.getString(0).length)
                assertTrue(cursor.moveToNext()); assertEquals("other", cursor.getString(0))
                assertTrue(cursor.moveToNext()); assertEquals(600_000, cursor.getString(0).length)
                assertTrue(cursor.moveToPrevious()); assertEquals("other", cursor.getString(0))
                assertTrue(cursor.moveToPosition(1)); assertEquals(200_000, cursor.getString(0).length)
                assertTrue(cursor.moveToLast()); assertEquals(600_000, cursor.getString(0).length)
                assertTrue(cursor.move(-3)); assertEquals("small", cursor.getString(0))
                assertFalse(cursor.moveToPrevious()); assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.count)
            }
            assertTrue(allocations.first() == 128L * 1024)
            assertTrue(allocations.any { it > 128L * 1024 })
            assertTrue(allocations.all { it <= 2L * 1024 * 1024 })
        } finally { helper.close() }
    }

    @Test fun preferenceSlicesKeepTheirUnicodeContentsWithinTheSmallBudget() {
        val helper = bounded(delegate()) { CursorWindow("PreferenceSlice", it) }
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO preference VALUES ('owner','test',?)", arrayOf("🎬".repeat(20_000)))
            db.query(SimpleSQLiteQuery("""SELECT substr(value, CAST(? AS INTEGER), CAST(? AS INTEGER)) AS part,
                length(value) AS characters FROM preference WHERE user_id = ? AND key = ?""",
                arrayOf("1", "16384", "owner", "test"))).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("🎬".repeat(16_384), cursor.getString(0))
                assertEquals(20_000L, cursor.getLong(1))
                assertFalse(window(cursor).putString("x".repeat(80_000), 0, 0))
            }
        } finally { helper.close() }
    }

    @Test fun failedInitialAllocationFallsBackToASmallerWindow() {
        val allocations = mutableListOf<Long>()
        val helper = bounded(delegate()) { bytes ->
            allocations += bytes
            if (bytes >= 128L * 1024) throw CursorWindowAllocationException("Injected allocation failure")
            CursorWindow("Fallback", bytes)
        }
        try {
            helper.readableDatabase.query("SELECT 42").use { assertTrue(it.moveToFirst()); assertEquals(42, it.getInt(0)) }
            assertEquals(listOf(128L * 1024, 64L * 1024), allocations)
        } finally { helper.close() }
    }

    @Test fun unrecoverableAllocationClosesTheCursorAndDoesNotBreakLaterReads() {
        val delegate = delegate()
        var captured: Cursor? = null
        val db = delegate.writableDatabase
        val capturingDb = object : SupportSQLiteDatabase by db {
            override fun query(query: androidx.sqlite.db.SupportSQLiteQuery): Cursor = db.query(query).also { captured = it }
        }
        val capturingHelper = object : SupportSQLiteOpenHelper by delegate {
            override val readableDatabase: SupportSQLiteDatabase get() = capturingDb
            override val writableDatabase: SupportSQLiteDatabase get() = capturingDb
        }
        var shouldFail = true
        val allocations = mutableListOf<Long>()
        val helper = bounded(capturingHelper) { bytes ->
            allocations += bytes
            if (shouldFail) throw CursorWindowAllocationException("Injected exhausted allocations")
            CursorWindow("Recovered", bytes)
        }
        try {
            try { helper.readableDatabase.query("SELECT 42"); fail("Expected the injected allocation failure") }
            catch (_: CursorWindowAllocationException) { }
            assertTrue(captured!!.isClosed)
            assertEquals(listOf(128L * 1024, 64L * 1024, 32L * 1024), allocations)
            shouldFail = false
            helper.readableDatabase.query("SELECT 43").use { assertTrue(it.moveToFirst()); assertEquals(43, it.getInt(0)) }
        } finally { helper.close() }
    }

    @Test fun repeatedHistoryReadsReleaseTheirCursorWindowsAndFileDescriptors() {
        val helper = bounded(delegate()) { CursorWindow("RepeatedRead", it) }
        try {
            val db = helper.writableDatabase
            repeat(20) { db.query("SELECT 1").use { assertTrue(it.moveToFirst()) } }
            val before = java.io.File("/proc/self/fd").list()!!.size
            repeat(1_000) {
                val cursor = db.query("SELECT 1")
                cursor.use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                assertTrue(cursor.isClosed)
            }
            val after = java.io.File("/proc/self/fd").list()!!.size
            assertTrue("Repeated reads leaked descriptors: before=$before after=$after", after <= before + 32)
        } finally { helper.close() }
    }

    @Test fun tvArtworkCacheHasFixedBudgetIndependentOfLargeHeap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(24L * 1024 * 1024, SingletonImageLoader.get(context).memoryCache!!.maxSize)
    }
}
