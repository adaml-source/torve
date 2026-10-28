package com.torve.data.replays

import kotlinx.datetime.Clock

/** Ephemeral, exact-URL header handoff. Neither signed URLs nor headers are saved. */
internal class ReplayHeaderHandoff(private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private data class Pending(val owner: String, val stream: ResolvedReplay, val at: Long)
    private var pending: Pending? = null
    fun stage(owner: String, stream: ResolvedReplay) { pending = Pending(owner, stream, now()) }
    fun peek(owner: String, url: String): Map<String, String>? {
        val value = pending ?: return null
        if (value.owner != owner || now() - value.at !in 0..60_000) { pending = null; return null }
        return value.stream.headers.takeIf { value.stream.url == url }
    }
    fun consume(owner: String, url: String): Map<String, String>? = peek(owner, url)?.also { pending = null }
}
internal object PendingReplayHeaders {
    val value = ReplayHeaderHandoff()
}
