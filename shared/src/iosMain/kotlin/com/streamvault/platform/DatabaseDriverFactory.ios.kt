package com.streamvault.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.streamvault.db.StreamVaultDatabase

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(StreamVaultDatabase.Schema, "streamvault.db")
}
