package com.torve.platform

import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.CursorWindow
import android.os.Build
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery

/** Settings slices contain one small row; allocating Fire OS's default 2 MiB is wasteful. */
internal class PreferenceWindowOpenHelper(
    private val delegate: SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper by delegate {
    override val writableDatabase: SupportSQLiteDatabase
        get() = delegate.writableDatabase.withPreferenceWindows()
    override val readableDatabase: SupportSQLiteDatabase
        get() = delegate.readableDatabase.withPreferenceWindows()
}

private fun SupportSQLiteDatabase.withPreferenceWindows(): SupportSQLiteDatabase =
    object : SupportSQLiteDatabase by this {
        override fun query(query: SupportSQLiteQuery): Cursor =
            this@withPreferenceWindows.query(query).withPreferenceWindow(query.sql)

        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
            this@withPreferenceWindows.query(query, cancellationSignal).withPreferenceWindow(query.sql)
    }

private fun Cursor.withPreferenceWindow(sql: String): Cursor {
    // Other queries retain their normal window: metadata rows can be much larger.
    if (Build.VERSION.SDK_INT >= 28 && this is AbstractWindowedCursor &&
        sql.trimStart().startsWith("SELECT substr(value, CAST(", ignoreCase = true)
    ) {
        try {
            window = CursorWindow("TorvePreferenceSlice", 128L * 1024L)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    return this
}
