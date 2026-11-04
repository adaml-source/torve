package com.torve.platform

import android.annotation.SuppressLint
import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.CursorWindow
import android.database.CursorWindowAllocationException
import android.database.CursorWrapper
import android.database.sqlite.SQLiteBlobTooBigException
import android.os.Build
import android.os.CancellationSignal
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery

/** Small reads never need Fire OS's default 2 MiB allocation. Large individual rows can grow. */
internal class BoundedCursorOpenHelper @JvmOverloads constructor(
    private val delegate: SupportSQLiteOpenHelper,
    private val allocateWindow: ((Long) -> CursorWindow)? = null,
) : SupportSQLiteOpenHelper by delegate {
    override val writableDatabase: SupportSQLiteDatabase
        get() = delegate.writableDatabase.withBoundedWindows(allocateWindow)
    override val readableDatabase: SupportSQLiteDatabase
        get() = delegate.readableDatabase.withBoundedWindows(allocateWindow)
}

@SuppressLint("NewApi") // Invoked only after the API 28 check below.
private fun allocateQueryWindow(bytes: Long) = CursorWindow("TorveQuery", bytes)

/** Entry point called by the AndroidX SQLite dependency instrumentation in every Android flavor. */
fun boundSqliteCursor(cursor: Cursor): Cursor =
    if (cursor is BoundedWindowCursor) cursor else cursor.withBoundedWindow(::allocateQueryWindow)

private fun Cursor.applyWindowPolicy(allocate: ((Long) -> CursorWindow)?): Cursor =
    if (allocate == null) boundSqliteCursor(this) else withBoundedWindow(allocate)

private fun SupportSQLiteDatabase.withBoundedWindows(allocate: ((Long) -> CursorWindow)?): SupportSQLiteDatabase =
    object : SupportSQLiteDatabase by this {
        override fun query(query: String): Cursor = query(SimpleSQLiteQuery(query))

        override fun query(query: String, bindArgs: Array<out Any?>): Cursor =
            query(SimpleSQLiteQuery(query, bindArgs))

        override fun query(query: SupportSQLiteQuery): Cursor =
            this@withBoundedWindows.query(query).applyWindowPolicy(allocate)

        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
            this@withBoundedWindows.query(query, cancellationSignal).applyWindowPolicy(allocate)
    }

private fun Cursor.withBoundedWindow(allocate: (Long) -> CursorWindow): Cursor {
    if (Build.VERSION.SDK_INT < 28) return this
    // An explicitly supplied allocator is used by allocation-failure regression tests.
    val raw = if (this is BoundedWindowCursor) wrappedCursor else this
    if (raw !is AbstractWindowedCursor) return this
    return try {
        BoundedWindowCursor(raw, allocate)
    } catch (failure: Throwable) {
        close()
        throw failure
    }
}

private class BoundedWindowCursor(
    private val cursor: AbstractWindowedCursor,
    private val allocate: (Long) -> CursorWindow,
) : CursorWrapper(cursor) {
    private var windowBytes = 128L * 1024L

    init {
        // A failed allocation must not leave a live cursor or another large window behind.
        while (true) {
            try {
                cursor.window = allocate(windowBytes)
                break
            } catch (failure: CursorWindowAllocationException) {
                if (windowBytes <= MIN_WINDOW_BYTES) throw failure
                windowBytes /= 2
            }
        }
    }

    override fun getCount(): Int = readWithWindow { cursor.count }
    override fun moveToPosition(position: Int): Boolean = readWithWindow { cursor.moveToPosition(position) }
    override fun move(offset: Int): Boolean = moveToPosition(position + offset)
    override fun moveToFirst(): Boolean = moveToPosition(0)
    override fun moveToLast(): Boolean = moveToPosition(count - 1)
    override fun moveToNext(): Boolean = moveToPosition(position + 1)
    override fun moveToPrevious(): Boolean = moveToPosition(position - 1)

    private inline fun <T> readWithWindow(read: () -> T): T {
        while (true) {
            try {
                return read()
            } catch (tooLarge: SQLiteBlobTooBigException) {
                if (windowBytes >= MAX_WINDOW_BYTES) {
                    close()
                    throw tooLarge
                }
                val nextBytes = (windowBytes * 2).coerceAtMost(MAX_WINDOW_BYTES)
                // Grow only for an individual row that cannot fit, never for the whole result set.
                // Do not fall back to the same size here: that would loop on oversized rows.
                try {
                    cursor.window = null
                    cursor.window = allocate(nextBytes)
                    windowBytes = nextBytes
                } catch (failure: Throwable) {
                    close()
                    throw failure
                }
            }
        }
    }

    private companion object {
        const val MIN_WINDOW_BYTES = 32L * 1024L
        const val MAX_WINDOW_BYTES = 2L * 1024L * 1024L
    }
}
