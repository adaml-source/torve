package com.torve.android.files

/** One-shot handoff: credentials never appear in navigation or resume records. */
internal object PendingAuthenticatedPlayback {
    private data class Pending(val url: String, val owner: String, val headers: Map<String, String>, val expiresAt: Long)
    private var pending: Pending? = null
    @Synchronized fun stage(url: String, owner: String, headers: Map<String, String>, now: Long = System.currentTimeMillis()) {
        pending = Pending(url, owner, headers.toMap(), now + 30_000)
    }
    @Synchronized fun consume(url: String, owner: String, now: Long = System.currentTimeMillis()): Map<String, String>? {
        val value = pending; pending = null
        return value?.takeIf { it.url == url && it.owner == owner && now < it.expiresAt }?.headers
    }
}
