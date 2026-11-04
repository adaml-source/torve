package com.torve.android.ui.tv

import android.annotation.SuppressLint
import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Exercise the library-owned database paths missed by the SQLDelight-only protection. */
@SuppressLint("RestrictedApi")
class TvWorkManagerCursorWindowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertSmallWindow(cursor: Cursor) = cursor.use {
        assertTrue(it.moveToFirst())
        var raw = it
        while (raw is CursorWrapper) raw = raw.wrappedCursor
        val window = (raw as AbstractWindowedCursor).window!!
        assertFalse("A small query must not receive Fire OS's 2 MiB default window",
            window.putString("x".repeat(150_000), window.startPosition, 0))
    }

    @Test fun frameworkQueriesAndDatabaseOpenCallbacksAreProtectedWithoutTorvesOpenHelper() {
        var callbackChecked = false
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration
            .builder(context).name(null).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    assertSmallWindow(db.query("SELECT 1"))
                    callbackChecked = true
                }
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            assertTrue(callbackChecked)
            assertSmallWindow(db.query("SELECT 1"))
            assertSmallWindow(db.query("SELECT ?", arrayOf(1)))
            assertSmallWindow(db.query(SimpleSQLiteQuery("SELECT 1")))
            assertSmallWindow(db.query(SimpleSQLiteQuery("SELECT 1"), CancellationSignal()))
        } finally { helper.close() }
    }

    @Test fun actualWorkManagerRoomQueriesUseSmallWindows() {
        val manager = WorkManager.getInstance(context) as WorkManagerImpl
        val database = manager.workDatabase
        val db = database.openHelper.writableDatabase
        // Neither WorkManager nor Room is constructed through Torve's database factory.
        assertSmallWindow(db.query("SELECT 1"))
        assertSmallWindow(db.query("SELECT ?", arrayOf(1)))
        assertSmallWindow(db.query(SimpleSQLiteQuery("SELECT 1")))
        assertSmallWindow(db.query(SimpleSQLiteQuery("SELECT 1"), CancellationSignal()))
        assertSmallWindow(database.query(SimpleSQLiteQuery("SELECT 1")))
        assertTrue(manager.getWorkInfosByTag("torve-cursor-window-regression-no-jobs")
            .get(15, TimeUnit.SECONDS).isEmpty())
    }

    @Test fun repeatedWorkManagerTransactionsRefreshRoomInvalidationsAndReleaseTheirWindows() {
        val manager = WorkManager.getInstance(context) as WorkManagerImpl
        val database = manager.workDatabase
        val refreshed = CountDownLatch(1)
        val observer = object : InvalidationTracker.Observer("Preference") {
            override fun onInvalidated(tables: Set<String>) { refreshed.countDown() }
        }
        database.invalidationTracker.addObserver(observer)
        val db = database.openHelper.writableDatabase
        val key = "torve_cursor_window_regression"
        val before = java.io.File("/proc/self/fd").list()!!.size
        try {
            repeat(500) { index ->
                database.runInTransaction {
                    db.execSQL("INSERT OR REPLACE INTO Preference (`key`, long_value) VALUES (?, ?)",
                        arrayOf<Any>(key, index))
                }
                assertSmallWindow(database.query(SimpleSQLiteQuery(
                    "SELECT long_value FROM Preference WHERE `key` = ?", arrayOf(key))))
            }
            assertTrue("Room's background invalidation reader did not complete",
                refreshed.await(15, TimeUnit.SECONDS))
            val after = java.io.File("/proc/self/fd").list()!!.size
            assertTrue("WorkManager cursor windows leaked descriptors: $before -> $after", after <= before + 32)
        } finally {
            database.invalidationTracker.removeObserver(observer)
            database.runInTransaction { db.execSQL("DELETE FROM Preference WHERE `key` = ?", arrayOf(key)) }
        }
    }
}
