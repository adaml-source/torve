package com.torve.data.trakt

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.platform.torveVerboseLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.max
import kotlin.random.Random

enum class TraktRequestBucket(val logName: String) {
    AUTHENTICATED_GET("authenticated_get"),
    UNAUTHENTICATED_GET("unauthenticated_get"),
    AUTHENTICATED_MUTATION("authenticated_mutation"),
    OAUTH_REFRESH("oauth_refresh"),
}

open class TraktException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class TraktRateLimitedException(
    val retryAfterSeconds: Long?,
    val bucket: TraktRequestBucket,
    val status: Int = 429,
) : TraktException(
    "Trakt rate limited ${bucket.logName}" +
        retryAfterSeconds?.let { " for $it seconds" }.orEmpty() +
        " (HTTP $status).",
)

class TraktNetworkException(
    cause: Throwable? = null,
) : TraktException("Network error while connecting to Trakt.", cause)

class TraktServerException(
    val status: Int,
) : TraktException("Trakt server error (HTTP $status).")

class TraktDecodeException(
    cause: Throwable? = null,
) : TraktException("Trakt response could not be decoded.", cause)

class TraktUnknownException(
    val status: Int,
) : TraktException("Trakt request failed (HTTP $status).")

data class TraktRateLimiterConfig(
    val getWindowMs: Long = 5L * 60L * 1000L,
    val authenticatedGetLimit: Int = 800,
    val unauthenticatedGetLimit: Int = 120,
    val authenticatedGetSpacingMs: Long = 0L,
    val unauthenticatedGetSpacingMs: Long = 500L,
    val mutationSpacingMs: Long = 1_000L,
    val defaultRateLimitCooldownMs: Long = 60_000L,
    val maxGetRetryAfterMs: Long = 15L * 60L * 1000L,
    val maxMutationRetryAfterMs: Long = 5L * 60L * 1000L,
)

internal data class TraktRawResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

fun interface TraktDiagnosticsLogger {
    fun log(event: String, fields: Map<String, String>)

    object Stdout : TraktDiagnosticsLogger {
        override fun log(event: String, fields: Map<String, String>) {
            val renderedFields = fields.entries.joinToString(separator = " ") { (key, value) ->
                "$key=$value"
            }
            torveVerboseLog { "trakt.$event $renderedFields" }
        }
    }
}

class TraktRateLimiter(
    private val config: TraktRateLimiterConfig = TraktRateLimiterConfig(),
    private val clock: Clock = Clock.System,
    private val diagnostics: TraktDiagnosticsLogger = TraktDiagnosticsLogger.Stdout,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val requestTimestamps = mutableMapOf<TraktRequestBucket, ArrayDeque<Long>>()
    private val blockedUntilMs = mutableMapOf<TraktRequestBucket, Long>()
    private val nextGetAllowedAtMs = mutableMapOf<TraktRequestBucket, Long>()
    private var nextMutationAllowedAtMs: Long = 0L

    suspend fun <T> run(bucket: TraktRequestBucket, block: suspend () -> T): T {
        if (bucket == TraktRequestBucket.AUTHENTICATED_MUTATION) {
            return mutex.withLock {
                checkCooldownLocked(bucket)
                paceMutationLocked()
                recordRequestLocked(bucket)
                block()
            }
        }

        mutex.withLock {
            checkCooldownLocked(bucket)
            paceGetLocked(bucket)
            recordRequestLocked(bucket)
        }
        return block()
    }

    suspend fun markRateLimited(
        bucket: TraktRequestBucket,
        retryAfterHeader: String?,
    ): TraktRateLimitedException {
        val retryAfterSeconds = parseRetryAfterSeconds(retryAfterHeader, clock.now().toEpochMilliseconds())
        val requestedCooldownMs = retryAfterSeconds?.toCooldownMs() ?: config.defaultRateLimitCooldownMs
        val maxCooldownMs = maxRetryAfterMs(bucket)
        val cooldownMs = requestedCooldownMs.coerceAtMost(maxCooldownMs)
        val wasCapped = requestedCooldownMs > cooldownMs
        val effectiveRetryAfterSeconds = ((cooldownMs + 999L) / 1_000L).coerceAtLeast(0L)
        val blockedUntil = clock.now().toEpochMilliseconds() + cooldownMs
        mutex.withLock {
            blockedUntilMs[bucket] = max(blockedUntilMs[bucket] ?: 0L, blockedUntil)
        }
        diagnostics.log(
            "requests.rate_limited",
            mapOf(
                "bucket" to bucket.logName,
                "retry_after_seconds" to effectiveRetryAfterSeconds.toString(),
                "capped" to wasCapped.toString(),
            ),
        )
        return TraktRateLimitedException(effectiveRetryAfterSeconds, bucket)
    }

    suspend fun resetForAccountChange() {
        mutex.withLock {
            requestTimestamps.clear()
            blockedUntilMs.clear()
            nextGetAllowedAtMs.clear()
            nextMutationAllowedAtMs = 0L
        }
    }

    private suspend fun paceGetLocked(bucket: TraktRequestBucket) {
        val spacingMs = when (bucket) {
            TraktRequestBucket.AUTHENTICATED_GET -> config.authenticatedGetSpacingMs
            TraktRequestBucket.UNAUTHENTICATED_GET -> config.unauthenticatedGetSpacingMs
            else -> 0L
        }.coerceAtLeast(0L)
        if (spacingMs <= 0L) return

        val now = clock.now().toEpochMilliseconds()
        val waitMs = (nextGetAllowedAtMs[bucket] ?: 0L) - now
        if (waitMs > 0L) delayMs(waitMs)
        nextGetAllowedAtMs[bucket] = clock.now().toEpochMilliseconds() + spacingMs
    }

    private suspend fun paceMutationLocked() {
        val now = clock.now().toEpochMilliseconds()
        val waitMs = nextMutationAllowedAtMs - now
        if (waitMs > 0L) delayMs(waitMs)
        nextMutationAllowedAtMs = clock.now().toEpochMilliseconds() + config.mutationSpacingMs
    }

    private fun checkCooldownLocked(bucket: TraktRequestBucket) {
        val now = clock.now().toEpochMilliseconds()
        val until = blockedUntilMs[bucket] ?: return
        if (until > now) {
            val retryAfterSeconds = ((until - now) + 999L) / 1_000L
            throw TraktRateLimitedException(retryAfterSeconds, bucket)
        }
        blockedUntilMs.remove(bucket)
    }

    private fun recordRequestLocked(bucket: TraktRequestBucket) {
        val limit = when (bucket) {
            TraktRequestBucket.AUTHENTICATED_GET -> config.authenticatedGetLimit
            TraktRequestBucket.UNAUTHENTICATED_GET -> config.unauthenticatedGetLimit
            else -> return
        }
        val now = clock.now().toEpochMilliseconds()
        val windowStart = now - config.getWindowMs
        val timestamps = requestTimestamps.getOrPut(bucket) { ArrayDeque() }
        while (timestamps.firstOrNull()?.let { it < windowStart } == true) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= limit) {
            val blockedUntil = timestamps.first() + config.getWindowMs
            blockedUntilMs[bucket] = blockedUntil
            val retryAfterSeconds = ((blockedUntil - now) + 999L) / 1_000L
            throw TraktRateLimitedException(retryAfterSeconds, bucket)
        }
        timestamps.addLast(now)
        diagnostics.log(
            "requests.total",
            mapOf("bucket" to bucket.logName),
        )
    }

    private fun maxRetryAfterMs(bucket: TraktRequestBucket): Long =
        when (bucket) {
            TraktRequestBucket.AUTHENTICATED_GET,
            TraktRequestBucket.UNAUTHENTICATED_GET -> config.maxGetRetryAfterMs
            TraktRequestBucket.AUTHENTICATED_MUTATION,
            TraktRequestBucket.OAUTH_REFRESH -> config.maxMutationRetryAfterMs
        }.coerceAtLeast(0L)

    private fun Long.toCooldownMs(): Long =
        if (this > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else this * 1_000L
}

fun interface TraktAuthScopeProvider {
    suspend fun currentAuthenticatedScope(): String

    suspend fun resetForAccountChange() = Unit
}

class InMemoryTraktAuthScopeProvider(
    private val scopeFactory: () -> String = ::newTraktConnectionScopeId,
) : TraktAuthScopeProvider {
    private val mutex = Mutex()
    private var scopeId: String? = null

    override suspend fun currentAuthenticatedScope(): String =
        mutex.withLock {
            scopeId ?: scopeFactory().also { scopeId = it }
        }

    override suspend fun resetForAccountChange() {
        mutex.withLock { scopeId = null }
    }
}

class PersistedTraktAuthScopeProvider(
    private val secretStore: IntegrationSecretStore,
    private val scopeFactory: () -> String = ::newTraktConnectionScopeId,
) : TraktAuthScopeProvider {
    private val mutex = Mutex()

    override suspend fun currentAuthenticatedScope(): String =
        mutex.withLock {
            secretStore.get(IntegrationSecretKey.TRAKT_CONNECTION_SCOPE)
                ?.takeIf { it.isNotBlank() }
                ?: scopeFactory().also { generated ->
                    secretStore.put(IntegrationSecretKey.TRAKT_CONNECTION_SCOPE, generated)
                }
        }

    override suspend fun resetForAccountChange() {
        mutex.withLock {
            secretStore.remove(IntegrationSecretKey.TRAKT_CONNECTION_SCOPE)
        }
    }
}

internal fun traktRequestCoalescingKey(
    method: String,
    authScope: String,
    path: String,
    query: List<Pair<String, String>>,
): String {
    val queryKey = query.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString("&") { "${it.first}=${it.second}" }
    return listOf(method, authScope, path, queryKey).joinToString("|")
}

internal fun parseRetryAfterSeconds(value: String?, nowMs: Long = Clock.System.now().toEpochMilliseconds()): Long? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
    return parseHttpDateRetryAfter(trimmed, nowMs)
}

private fun newTraktConnectionScopeId(): String {
    val bytes = Random.nextBytes(16)
    return bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun parseHttpDateRetryAfter(value: String, nowMs: Long): Long? {
    val match = Regex("""^[A-Za-z]{3},\s+(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})\s+(\d{2}):(\d{2}):(\d{2})\s+GMT$""")
        .matchEntire(value)
        ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = when (match.groupValues[2].lowercase()) {
        "jan" -> 1
        "feb" -> 2
        "mar" -> 3
        "apr" -> 4
        "may" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "oct" -> 10
        "nov" -> 11
        "dec" -> 12
        else -> return null
    }
    val year = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null
    return runCatching {
        val retryAt = LocalDateTime(year, month, day, hour, minute, second)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
        ((retryAt - nowMs) + 999L) / 1_000L
    }.getOrNull()?.coerceAtLeast(0L)
}
