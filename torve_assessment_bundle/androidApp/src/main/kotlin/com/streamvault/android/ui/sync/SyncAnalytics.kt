package com.streamvault.android.ui.sync

interface SyncAnalytics {
    fun track(event: String, attributes: Map<String, String> = emptyMap())
}

object NoOpSyncAnalytics : SyncAnalytics {
    override fun track(event: String, attributes: Map<String, String>) = Unit
}
