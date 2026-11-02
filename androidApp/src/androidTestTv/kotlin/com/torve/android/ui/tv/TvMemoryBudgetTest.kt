package com.torve.android.ui.tv

import android.database.AbstractWindowedCursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import org.junit.Assert.*
import org.junit.Test

class TvMemoryBudgetTest {
    @Test fun preferenceSlicesUseSmallWindowsWhileMetadataKeepsItsNormalCapacity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val delegate = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE preference(user_id TEXT, key TEXT, value TEXT)")
                        db.execSQL("CREATE TABLE metadata(value TEXT)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        val helper = Class.forName("com.torve.platform.PreferenceWindowOpenHelper")
            .getDeclaredConstructor(SupportSQLiteOpenHelper::class.java)
            .newInstance(delegate) as SupportSQLiteOpenHelper
        try {
            val db = helper.writableDatabase
            val value = "🎬".repeat(20_000)
            db.execSQL("INSERT INTO preference VALUES ('owner','test',?)", arrayOf(value))
            db.execSQL("INSERT INTO metadata VALUES (?)", arrayOf("x".repeat(200_000)))
            db.query(SimpleSQLiteQuery("""SELECT substr(value, CAST(? AS INTEGER), CAST(? AS INTEGER)) AS part,
                length(value) AS characters FROM preference WHERE user_id = ? AND key = ?""",
                arrayOf("1", "16384", "owner", "test"))).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("🎬".repeat(16_384), cursor.getString(0))
                assertEquals(20_000L, cursor.getLong(1))
                val window = (cursor as AbstractWindowedCursor).window!!
                assertFalse("Settings must not allocate the default 2 MiB window", window.putString("x".repeat(80_000), 0, 0))
            }
            db.query("SELECT value FROM metadata").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(200_000, cursor.getString(0).length)
            }
        } finally { helper.close() }
    }

    @Test fun tvArtworkCacheHasFixedBudgetIndependentOfLargeHeap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(24L * 1024 * 1024, SingletonImageLoader.get(context).memoryCache!!.maxSize)
    }
}
