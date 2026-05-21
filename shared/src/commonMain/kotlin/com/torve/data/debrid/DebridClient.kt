package com.torve.data.debrid

import com.torve.data.acceleration.AccelerationApi
import com.torve.data.acceleration.HashAvailabilityObservationDto
import com.torve.data.acceleration.extractInventoryItems
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.TranscodeUrls
import com.torve.domain.model.apiValue
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.platform.TorveRuntimeDebug
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Callback to refresh an expired RD OAuth token.
 * Returns the new access token, or null if refresh is not possible.
 */
class RdAuthException(message: String) : Exception(message)
class DebridMissingException(message: String) : Exception(message)
class DebridNeedsReconnectException(message: String) : Exception(message)
class DebridNoCachedStreamException(message: String) : Exception(message)
class DebridServiceUnavailableException(message: String) : Exception(message)
class DebridSourceBlockedException(message: String) : Exception(message)

fun interface RdTokenRefresher {
    suspend fun refresh(): String?
}

class DebridClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val accelerationApi: AccelerationApi? = null,
    var rdTokenRefresher: RdTokenRefresher? = null,
) {
    private data class CacheCheckEntry(
        val cached: Boolean,
        val observedAt: Long,
    )

    private val cacheCheckMemory = mutableMapOf<String, CacheCheckEntry>()

    private inline fun debridDebugLog(message: () -> String) {
        if (TorveRuntimeDebug.verboseLoggingEnabled) {
            println(DiagnosticsRedactor.redact(message()))
        }
    }

    companion object {
        const val RD_BASE = "https://api.real-debrid.com/rest/1.0"
        const val RD_OAUTH = "https://api.real-debrid.com/oauth/v2"
        const val AD_BASE = "https://api.alldebrid.com/v4"
        const val PM_BASE = "https://www.premiumize.me/api"
        const val PM_OAUTH = "https://www.premiumize.me/token"
        const val TB_BASE = "https://api.torbox.app/v1/api"

        const val RD_CLIENT_ID = "X245A4XAIBGVM"
        const val AD_AGENT = "torve"
        const val PM_CLIENT_ID = "888228107"
        const val PM_OAUTH_PREFIX = "pm-oauth:"
        const val CACHE_CHECK_POSITIVE_TTL_MS = 30 * 60 * 1000L
        const val CACHE_CHECK_NEGATIVE_TTL_MS = 10 * 60 * 1000L
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "m4v", "mov", "webm", "ts")
        val DEFAULT_TORRENT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
        )
    }

    private fun pmAccessToken(credential: String): String? =
        credential.takeIf { it.startsWith(PM_OAUTH_PREFIX) }?.removePrefix(PM_OAUTH_PREFIX)

    private fun normalizeInfoHash(infoHash: String): String {
        val normalized = infoHash
            .trim()
            .removePrefix("urn:btih:")
            .removePrefix("btih:")
            .substringBefore('&')
            .substringBefore('?')
            .trim()
            .lowercase()
        if (!normalized.matches(Regex("^[a-f0-9]{40}$"))) {
            throw IllegalArgumentException("Invalid torrent hash.")
        }
        return normalized
    }

    private fun buildHashOnlyMagnet(infoHash: String, displayName: String?): String {
        val params = mutableListOf("xt=urn:btih:$infoHash")
        displayName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { params += "dn=${it.encodeURLParameter()}" }
        DEFAULT_TORRENT_TRACKERS.forEach { tracker ->
            params += "tr=${tracker.encodeURLParameter()}"
        }
        return "magnet:?" + params.joinToString("&")
    }

    private fun HttpRequestBuilder.pmAuthorize(credential: String) {
        val accessToken = pmAccessToken(credential)
        if (accessToken != null) {
            header("Authorization", "Bearer $accessToken")
        } else {
            parameter("apikey", credential)
        }
    }

    private fun ParametersBuilder.pmAuthorize(credential: String) {
        if (pmAccessToken(credential) == null) {
            append("apikey", credential)
        }
    }

    // -------------------------------------------------------------------------
    // Unified API
    // -------------------------------------------------------------------------

    suspend fun verifyApiKey(
        provider: DebridServiceType,
        apiKey: String,
    ): DebridResult {
        if (apiKey.isBlank()) return DebridResult(success = false, error = "API key is required")
        return when (provider) {
            DebridServiceType.REAL_DEBRID -> rdVerifyApiKey(apiKey)
            DebridServiceType.ALL_DEBRID -> adVerifyApiKey(apiKey)
            DebridServiceType.PREMIUMIZE -> pmVerifyApiKey(apiKey)
            DebridServiceType.TORBOX -> tbVerifyApiKey(apiKey)
        }
    }

    fun supportsDeviceAuth(provider: DebridServiceType): Boolean {
        return provider == DebridServiceType.REAL_DEBRID ||
            provider == DebridServiceType.ALL_DEBRID ||
            provider == DebridServiceType.PREMIUMIZE
    }

    suspend fun getDeviceCode(provider: DebridServiceType): DeviceCodeInfo? {
        return when (provider) {
            DebridServiceType.REAL_DEBRID -> rdGetDeviceCode()
            DebridServiceType.ALL_DEBRID -> adGetDeviceCode()
            DebridServiceType.PREMIUMIZE -> pmGetDeviceCode()
            else -> null
        }
    }

    suspend fun pollDeviceAuth(
        provider: DebridServiceType,
        deviceCode: String,
        userCode: String,
    ): DevicePollResult {
        return when (provider) {
            DebridServiceType.REAL_DEBRID -> {
                val creds = rdPollDeviceCode(deviceCode)
                if (creds != null) {
                    val tokens = rdExchangeToken(deviceCode, creds.first, creds.second)
                    DevicePollResult(done = true, apiKey = tokens.accessToken, oauthTokens = tokens)
                } else {
                    DevicePollResult(done = false)
                }
            }
            DebridServiceType.ALL_DEBRID -> {
                val key = adPollDeviceCode(userCode, deviceCode)
                if (key != null) DevicePollResult(done = true, apiKey = key)
                else DevicePollResult(done = false)
            }
            DebridServiceType.PREMIUMIZE -> {
                val token = pmPollDeviceCode(deviceCode)
                if (token != null) DevicePollResult(done = true, apiKey = "$PM_OAUTH_PREFIX$token")
                else DevicePollResult(done = false)
            }
            else -> DevicePollResult(done = false)
        }
    }

    /**
     * Batch check whether infoHashes are cached on the debrid service.
     * Returns a map of infoHash -> isCached.
     */
    suspend fun checkCache(
        provider: DebridServiceType,
        apiKey: String,
        infoHashes: List<String>,
    ): Map<String, Boolean> {
        if (infoHashes.isEmpty() || apiKey.isBlank()) return emptyMap()
        val normalizedHashes = infoHashes
            .mapNotNull { runCatching { normalizeInfoHash(it) }.getOrNull() }
            .distinct()
        if (normalizedHashes.isEmpty()) return emptyMap()
        val now = currentTimeMillis()
        val credentialKey = apiKey.hashCode()
        val cachedResults = mutableMapOf<String, Boolean>()
        val misses = normalizedHashes.filter { hash ->
            val key = cacheCheckKey(provider, credentialKey, hash)
            val entry = cacheCheckMemory[key]
            val ttl = if (entry?.cached == true) CACHE_CHECK_POSITIVE_TTL_MS else CACHE_CHECK_NEGATIVE_TTL_MS
            if (entry != null && now - entry.observedAt <= ttl) {
                cachedResults[hash] = entry.cached
                false
            } else {
                true
            }
        }
        if (misses.isEmpty()) return cachedResults
        return try {
            val result = when (provider) {
                DebridServiceType.REAL_DEBRID -> rdCheckCache(apiKey, misses)
                DebridServiceType.ALL_DEBRID -> adCheckCache(apiKey, misses)
                DebridServiceType.PREMIUMIZE -> pmCheckCache(apiKey, misses)
                DebridServiceType.TORBOX -> tbCheckCache(apiKey, misses)
            }
            result.forEach { (hash, cached) ->
                val normalized = runCatching { normalizeInfoHash(hash) }.getOrNull() ?: return@forEach
                cacheCheckMemory[cacheCheckKey(provider, credentialKey, normalized)] = CacheCheckEntry(
                    cached = cached,
                    observedAt = now,
                )
                cachedResults[normalized] = cached
            }
            accelerationApi?.reportHashes(
                providerType = provider.apiValue,
                observations = result.map { (infoHash, isCached) ->
                    HashAvailabilityObservationDto(
                        infohash = infoHash,
                        isCached = isCached,
                    )
                },
            )
            cachedResults
        } catch (e: Exception) {
            if (e is RdAuthException || e is DebridNeedsReconnectException || e is DebridServiceUnavailableException) {
                throw e
            }
            cachedResults
        }
    }

    suspend fun getInventoryItems(
        provider: DebridServiceType,
        apiKey: String,
    ): List<JsonObject> {
        if (apiKey.isBlank()) return emptyList()
        return try {
            when (provider) {
                DebridServiceType.REAL_DEBRID -> rdGetInventory(apiKey)
                DebridServiceType.TORBOX -> tbGetInventory(apiKey)
                DebridServiceType.ALL_DEBRID,
                DebridServiceType.PREMIUMIZE,
                -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Resolve a torrent infoHash to playable URLs via the chosen debrid service.
     */
    suspend fun resolveStream(
        provider: DebridServiceType,
        apiKey: String,
        infoHash: String,
        fileIdx: Int? = null,
        magnetUrl: String? = null,
        displayName: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): ResolvedStream {
        val normalizedHash = normalizeInfoHash(infoHash)
        val hashOnlyMagnet = buildHashOnlyMagnet(normalizedHash, displayName)
        val magnet = magnetUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: hashOnlyMagnet
        val magnetMode = if (magnetUrl.isNullOrBlank()) "hash_only" else "full_magnet"
        return when (provider) {
            DebridServiceType.REAL_DEBRID -> {
                try {
                    rdResolveStream(apiKey, normalizedHash, magnet, magnetMode, fileIdx, season, episode)
                } catch (e: RdAuthException) {
                    // Token expired — try refresh and retry once
                    val newKey = rdTokenRefresher?.refresh()
                    if (newKey != null) {
                        debridDebugLog { "TORVE_RD: token refreshed, retrying resolve" }
                        rdResolveStream(newKey, normalizedHash, magnet, magnetMode, fileIdx, season, episode)
                    } else {
                        throw DebridNeedsReconnectException("Real-Debrid needs reconnecting. Open Panda settings.")
                    }
                }
            }
            DebridServiceType.ALL_DEBRID -> adResolveStream(apiKey, normalizedHash, magnet, magnetMode, fileIdx)
            DebridServiceType.PREMIUMIZE -> pmResolveStream(apiKey, normalizedHash, magnet, magnetMode)
            DebridServiceType.TORBOX -> tbResolveStream(apiKey, normalizedHash, magnet, magnetMode, fileIdx)
        }
    }

    /**
     * Unrestrict a direct hoster URL.
     */
    suspend fun unrestrictUrl(
        provider: DebridServiceType,
        apiKey: String,
        url: String,
    ): ResolvedStream {
        return when (provider) {
            DebridServiceType.REAL_DEBRID -> {
                try {
                    rdUnrestrictUrlInternal(apiKey, url, provider)
                } catch (e: RdAuthException) {
                    val newKey = rdTokenRefresher?.refresh()
                    if (newKey != null) {
                        debridDebugLog { "TORVE_RD: token refreshed, retrying unrestrict" }
                        rdUnrestrictUrlInternal(newKey, url, provider)
                    } else {
                        throw DebridNeedsReconnectException("Real-Debrid needs reconnecting. Open Panda settings.")
                    }
                }
            }
            DebridServiceType.ALL_DEBRID -> {
                val resp: AdResponse<AdUnlockData> = httpClient.get("$AD_BASE/link/unlock") {
                    parameter("agent", AD_AGENT)
                    parameter("apikey", apiKey)
                    parameter("link", url)
                }.body()
                val data = resp.data ?: throw Exception("Service unlock failed")
                ResolvedStream(
                    url = data.link,
                    service = provider,
                    fileName = data.filename,
                )
            }
            DebridServiceType.PREMIUMIZE -> {
                val resp: PmDirectDlResponse = httpClient.submitForm(
                    url = "$PM_BASE/transfer/directdl",
                    formParameters = Parameters.build {
                        pmAuthorize(apiKey)
                        append("src", url)
                    },
                ) {
                    pmAccessToken(apiKey)?.let { header("Authorization", "Bearer $it") }
                }.body()
                if (resp.status != "success" || resp.content.isEmpty()) {
                    throw Exception("Failed to unrestrict link")
                }
                val file = resp.content.maxByOrNull { it.size } ?: resp.content.first()
                ResolvedStream(
                    url = file.streamLink ?: file.link,
                    service = provider,
                    fileName = file.path.substringAfterLast('/'),
                    fileSize = file.size,
                )
            }
            DebridServiceType.TORBOX -> {
                val createResp: TbResponse<TbTorrentData> = httpClient.post("$TB_BASE/webdl/createwebdownload") {
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody("""{"url":"$url"}""")
                }.body()
                val downloadId = createResp.data?.id ?: throw Exception("Download creation failed")
                // Get the download link
                val linkResp: TbResponse<TbDownloadLinkData> =
                    httpClient.get("$TB_BASE/webdl/requestdl") {
                        header("Authorization", "Bearer $apiKey")
                        parameter("web_id", downloadId)
                    }.body()
                val downloadUrl = linkResp.data?.data ?: throw Exception("No download link available")
                ResolvedStream(
                    url = downloadUrl,
                    service = provider,
                )
            }
        }
    }

    /**
     * Refresh an expired RD OAuth access token.
     */
    suspend fun rdRefreshAccessToken(
        refreshToken: String,
        clientId: String,
        clientSecret: String,
    ): RdOAuthTokens {
        val rawResp = httpClient.submitForm(
            url = "$RD_OAUTH/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("code", refreshToken)
                append("grant_type", "http://oauth.net/grant_type/device/1.0")
            },
        )
        val bodyText = rawResp.bodyAsText()
        debridDebugLog { "TORVE_RD: token refresh HTTP ${rawResp.status.value}" }
        if (rawResp.status.value !in 200..299) {
            throw Exception("Token refresh failed (${rawResp.status.value}).")
        }
        val resp: RdTokenResponse = json.decodeFromString(bodyText)
        return RdOAuthTokens(
            accessToken = resp.accessToken,
            refreshToken = resp.refreshToken.ifEmpty { refreshToken },
            clientId = clientId,
            clientSecret = clientSecret,
            expiresAt = currentTimeMillis() + resp.expiresIn * 1000L,
        )
    }

    // -------------------------------------------------------------------------
    // Real-Debrid
    // -------------------------------------------------------------------------

    private suspend fun rdVerifyApiKey(apiKey: String): DebridResult {
        return try {
            val user: RdUserResponse = httpClient.get("$RD_BASE/user") {
                header("Authorization", "Bearer $apiKey")
            }.body()
            DebridResult(
                success = true,
                user = DebridUser(
                    username = user.username,
                    email = user.email,
                    premium = user.type == "premium",
                    expiresAt = user.expiration,
                    points = user.points,
                ),
            )
        } catch (e: Exception) {
            DebridResult(success = false, error = extractError(e, "Real-Debrid"))
        }
    }

    private suspend fun rdGetDeviceCode(): DeviceCodeInfo {
        val resp: RdDeviceCodeResponse = httpClient.get("$RD_OAUTH/device/code") {
            parameter("client_id", RD_CLIENT_ID)
            parameter("new_credentials", "yes")
        }.body()
        return DeviceCodeInfo(
            deviceCode = resp.deviceCode,
            userCode = resp.userCode,
            verificationUrl = resp.verificationUrl,
            interval = resp.interval,
            expiresIn = resp.expiresIn,
        )
    }

    private suspend fun rdPollDeviceCode(deviceCode: String): Pair<String, String>? {
        return try {
            val resp: RdCredentialsResponse = httpClient.get("$RD_OAUTH/device/credentials") {
                parameter("client_id", RD_CLIENT_ID)
                parameter("code", deviceCode)
            }.body()
            if (resp.clientId != null && resp.clientSecret != null) {
                Pair(resp.clientId, resp.clientSecret)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun rdExchangeToken(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): RdOAuthTokens {
        val resp: RdTokenResponse = httpClient.submitForm(
            url = "$RD_OAUTH/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("code", deviceCode)
                append("grant_type", "http://oauth.net/grant_type/device/1.0")
            },
        ).body()
        return RdOAuthTokens(
            accessToken = resp.accessToken,
            refreshToken = resp.refreshToken,
            clientId = clientId,
            clientSecret = clientSecret,
            expiresAt = currentTimeMillis() + resp.expiresIn * 1000L,
        )
    }

    private suspend fun rdAddMagnet(apiKey: String, magnet: String): String {
        val rawResp = httpClient.submitForm(
            url = "$RD_BASE/torrents/addMagnet",
            formParameters = Parameters.build { append("magnet", magnet) },
        ) {
            header("Authorization", "Bearer $apiKey")
        }
        val bodyText = rawResp.bodyAsText()
        debridDebugLog { "TORVE_RD: addMagnet HTTP ${rawResp.status.value}" }
        if (rawResp.status.value == 401) {
            throw RdAuthException("Session token expired (HTTP 401)")
        }
        if (rawResp.status.value !in 200..299) {
            val rdError = runCatching { json.decodeFromString<RdErrorResponse>(bodyText) }.getOrNull()
            val errorName = rdError?.error.orEmpty()
            val errorCode = rdError?.errorCode
            if (rawResp.status.value == 451 || errorCode == 35 || errorName.equals("infringing_file", ignoreCase = true)) {
                throw DebridSourceBlockedException("Real-Debrid blocked this source. Try another source.")
            }
            if (rawResp.status.value in 500..599) {
                throw DebridServiceUnavailableException("Real-Debrid is unavailable right now. Try again later.")
            }
            throw Exception("Real-Debrid rejected this source (HTTP ${rawResp.status.value}). Try another source.")
        }
        val resp: RdAddMagnetResponse = json.decodeFromString(bodyText)
        return resp.id.takeIf { it.isNotBlank() }
            ?: throw Exception("Real-Debrid did not return a torrent id. Try another source.")
    }

    /**
     * Internal Real-Debrid hoster-URL unrestrict path. Lifted out of
     * [unrestrictUrl] so the auth-retry wrapper can call this twice
     * (once with the original key, once with a refreshed key) on 401.
     */
    private suspend fun rdUnrestrictUrlInternal(
        apiKey: String,
        url: String,
        provider: DebridServiceType,
    ): ResolvedStream {
        val file = rdUnrestrictLink(apiKey, url)
        val transcode = if (file.streamable) rdGetTranscodeUrls(apiKey, file.id) else null
        return ResolvedStream(
            url = file.download,
            service = provider,
            fileName = file.filename,
            mimeType = file.mimeType,
            transcodeUrls = transcode,
        )
    }

    private suspend fun rdSelectFiles(apiKey: String, torrentId: String, files: String = "all") {
        val rawResp = httpClient.submitForm(
            url = "$RD_BASE/torrents/selectFiles/$torrentId",
            formParameters = Parameters.build { append("files", files) },
        ) {
            header("Authorization", "Bearer $apiKey")
        }
        if (rawResp.status.value == 401) {
            throw RdAuthException("Session token expired (HTTP 401, selectFiles)")
        }
    }

    private suspend fun rdGetTorrentInfo(apiKey: String, torrentId: String): RdTorrentInfoResponse {
        if (torrentId.isBlank()) {
            throw Exception("Real-Debrid did not return a torrent id. Try another source.")
        }
        val rawResp = httpClient.get("$RD_BASE/torrents/info/$torrentId") {
            header("Authorization", "Bearer $apiKey")
        }
        if (rawResp.status.value == 401) {
            throw RdAuthException("Session token expired (HTTP 401, torrentInfo)")
        }
        val bodyText = rawResp.bodyAsText()
        if (rawResp.status.value !in 200..299) {
            if (rawResp.status.value in 500..599) {
                throw DebridServiceUnavailableException("Real-Debrid is unavailable right now. Try again later.")
            }
            throw Exception("Real-Debrid torrent lookup failed (HTTP ${rawResp.status.value}). Try another source.")
        }
        return json.decodeFromString(bodyText)
    }

    private suspend fun rdUnrestrictLink(apiKey: String, link: String): UnrestrictedFile {
        val rawResp = httpClient.submitForm(
            url = "$RD_BASE/unrestrict/link",
            formParameters = Parameters.build { append("link", link) },
        ) {
            header("Authorization", "Bearer $apiKey")
        }
        if (rawResp.status.value == 401) {
            throw RdAuthException("Session token expired (HTTP 401, unrestrictLink)")
        }
        val resp: RdUnrestrictResponse = json.decodeFromString(rawResp.bodyAsText())
        return UnrestrictedFile(
            id = resp.id,
            filename = resp.filename,
            mimeType = resp.mimeType,
            download = resp.download,
            streamable = resp.streamable == 1,
        )
    }

    private suspend fun rdGetTranscodeUrls(apiKey: String, fileId: String): TranscodeUrls? {
        // Don't swallow 401 here: it must propagate so the caller's
        // refresh-retry wrapper can do its job. Other errors (404 for
        // non-streamable files, transient 5xx, etc.) are still treated
        // as "no transcode available" since transcode is optional.
        val rawResp = try {
            httpClient.get("$RD_BASE/streaming/transcode/$fileId") {
                header("Authorization", "Bearer $apiKey")
            }
        } catch (_: Exception) {
            return null
        }
        if (rawResp.status.value == 401) {
            throw RdAuthException("Session token expired (HTTP 401, transcode)")
        }
        return try {
            val resp: RdTranscodeResponse = json.decodeFromString(rawResp.bodyAsText())
            TranscodeUrls(
                mp4 = resp.liveMP4?.full,
                hls = resp.apple?.full,
                webm = resp.h264WebM?.full,
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun rdResolveStream(
        apiKey: String,
        infoHash: String,
        magnet: String,
        magnetMode: String,
        fileIdx: Int?,
        season: Int?,
        episode: Int?,
    ): ResolvedStream {
        debridDebugLog { "TORVE_RD: resolve hash=${infoHash.redactedHash()} fileIdx=$fileIdx magnetMode=$magnetMode" }
        if (magnetMode == "hash_only") {
            val cached = try {
                rdCheckCache(apiKey, listOf(infoHash))[infoHash]
            } catch (e: Exception) {
                if (e is RdAuthException || e is DebridServiceUnavailableException) throw e
                debridDebugLog { "TORVE_RD: hashOnlyCacheCheck unknown hash=${infoHash.redactedHash()} reason=${e::class.simpleName}" }
                null
            }
            debridDebugLog { "TORVE_RD: hashOnlyCacheCheck hash=${infoHash.redactedHash()} cached=${cached ?: "unknown"}" }
            if (cached == false) {
                rdResolveExistingTorrentByHash(apiKey, infoHash, fileIdx, season, episode)?.let { return it }
                throw DebridNoCachedStreamException("Not cached on Real-Debrid.")
            }
            rdResolveExistingTorrentByHash(apiKey, infoHash, fileIdx, season, episode)?.let { return it }
        }

        // 1. Add magnet
        val torrentId = try {
            rdAddMagnet(apiKey, magnet)
        } catch (e: DebridSourceBlockedException) {
            rdResolveExistingTorrentByHash(apiKey, infoHash, fileIdx, season, episode)?.let { return it }
            throw e
        }
        debridDebugLog { "TORVE_RD: addMagnet accepted fileIdx=$fileIdx" }

        return rdResolveTorrentId(apiKey, infoHash, torrentId, fileIdx, season, episode, origin = "addMagnet")
    }

    private suspend fun rdResolveExistingTorrentByHash(
        apiKey: String,
        infoHash: String,
        fileIdx: Int?,
        season: Int?,
        episode: Int?,
    ): ResolvedStream? {
        val existingId = rdFindExistingTorrentId(apiKey, infoHash) ?: return null
        debridDebugLog { "TORVE_RD: existingTorrent match hash=${infoHash.redactedHash()}" }
        return rdResolveTorrentId(apiKey, infoHash, existingId, fileIdx, season, episode, origin = "inventory")
    }

    private suspend fun rdResolveTorrentId(
        apiKey: String,
        infoHash: String,
        torrentId: String,
        fileIdx: Int?,
        season: Int?,
        episode: Int?,
        origin: String,
    ): ResolvedStream {
        // Get torrent info to find file IDs, then select the target file
        val initialInfo = rdGetTorrentInfo(apiKey, torrentId)
        debridDebugLog { "TORVE_RD: initialInfo origin=$origin status=${initialInfo.status} files=${initialInfo.files.size} links=${initialInfo.links.size}" }

        // Select specific file if possible; fall back to "all" if no fileIdx
        val filesToSelect = if (fileIdx != null && initialInfo.files.isNotEmpty()) {
            // RD file IDs are 1-based; fileIdx from addons is typically 0-based
            val rdFileId = initialInfo.files.getOrNull(fileIdx)?.id
                ?: (fileIdx + 1) // Fallback: assume 1-based offset
            rdFileId.toString()
        } else if (season != null && episode != null && initialInfo.files.isNotEmpty()) {
            selectEpisodeVideoFile(initialInfo.files, season, episode)?.id?.toString()
                ?: selectLargestVideoFile(initialInfo.files)?.id?.toString()
                ?: "all"
        } else if (initialInfo.files.isNotEmpty()) {
            // No fileIdx — select the largest video file
            selectLargestVideoFile(initialInfo.files)?.id?.toString() ?: "all"
        } else {
            "all"
        }
        debridDebugLog { "TORVE_RD: selectFiles_count=${filesToSelect.split(',').size}" }
        rdSelectFiles(apiKey, torrentId, filesToSelect)

        // 3. Poll until ready
        var links: List<String> = emptyList()
        for (attempt in 0 until 30) {
            val info = rdGetTorrentInfo(apiKey, torrentId)
            debridDebugLog { "TORVE_RD: poll #$attempt status=${info.status} links=${info.links.size}" }
            if (info.status == "downloaded" && info.links.isNotEmpty()) {
                links = info.links
                break
            }
            if (info.status in listOf("error", "dead", "magnet_error")) {
                throw Exception("Download failed: ${info.status}")
            }
            // Cached torrents transition to "downloaded" within a few seconds of
            // file selection. Staying in "downloading" means RD is fetching it from
            // scratch — fail fast so the caller can try the next source rather than
            // blocking for up to 60 seconds per stream.
            if (attempt >= 3 && info.status == "downloading") {
                throw Exception("Not cached on Real-Debrid — try a different source.")
            }
            delay(2000)
        }

        if (links.isEmpty()) {
            throw Exception("Download timed out — no links available")
        }

        // 4. Unrestrict the first link (we selected only the target file)
        val file = rdUnrestrictLink(apiKey, links.first())
        debridDebugLog { "TORVE_RD: unrestricted hasFileName=${file.filename.isNotBlank()} streamable=${file.streamable}" }

        // 5. Get transcode URLs
        val transcode = if (file.streamable) rdGetTranscodeUrls(apiKey, file.id) else null

        return ResolvedStream(
            url = file.download,
            service = DebridServiceType.REAL_DEBRID,
            fileName = file.filename,
            mimeType = file.mimeType,
            transcodeUrls = transcode,
        )
    }

    private fun selectLargestVideoFile(files: List<RdTorrentFile>): RdTorrentFile? {
        return files
            .filter { file -> isVideoFile(file.path) && !isJunkVideoFile(file.path) }
            .maxByOrNull { it.bytes }
    }

    private fun selectEpisodeVideoFile(
        files: List<RdTorrentFile>,
        season: Int,
        episode: Int,
    ): RdTorrentFile? {
        val seasonPadded = season.toString().padStart(2, '0')
        val episodePadded = episode.toString().padStart(2, '0')
        val patterns = listOf(
            Regex("""(?i)\bs$seasonPadded[\s._-]*e$episodePadded\b"""),
            Regex("""(?i)\b$season[xX]$episodePadded\b"""),
            Regex("""(?i)\b$season[xX]$episode\b"""),
        )
        return files
            .filter { file -> isVideoFile(file.path) && !isJunkVideoFile(file.path) }
            .filter { file -> patterns.any { pattern -> pattern.containsMatchIn(file.path) } }
            .maxByOrNull { it.bytes }
    }

    private fun isVideoFile(path: String): Boolean {
        val lower = path.lowercase()
        return VIDEO_EXTENSIONS.any { ext -> lower.endsWith(".$ext") }
    }

    private fun isJunkVideoFile(path: String): Boolean {
        val lower = path.lowercase()
        return listOf("sample", "trailer", "extras", "featurette", "behind.the.scenes", "behind-the-scenes")
            .any { token -> lower.contains(token) }
    }

    // -------------------------------------------------------------------------
    // AllDebrid
    // -------------------------------------------------------------------------

    private suspend fun adVerifyApiKey(apiKey: String): DebridResult {
        return try {
            val resp: AdResponse<AdUserData> = httpClient.get("$AD_BASE/user") {
                parameter("agent", AD_AGENT)
                parameter("apikey", apiKey)
            }.body()
            if (resp.status == "success" && resp.data?.user != null) {
                val u = resp.data.user!!
                DebridResult(
                    success = true,
                    user = DebridUser(
                        username = u.username,
                        email = u.email,
                        premium = u.isPremium,
                        expiresAt = if (u.premiumUntil > 0) {
                            kotlinx.datetime.Instant.fromEpochSeconds(u.premiumUntil).toString()
                        } else null,
                    ),
                )
            } else {
                DebridResult(success = false, error = "Unknown AllDebrid error")
            }
        } catch (e: Exception) {
            DebridResult(success = false, error = extractError(e, "AllDebrid"))
        }
    }

    private suspend fun adGetDeviceCode(): DeviceCodeInfo {
        val resp: AdResponse<AdPinGetData> = httpClient.get("$AD_BASE/pin/get") {
            parameter("agent", AD_AGENT)
        }.body()
        val data = resp.data ?: throw Exception("Device code request failed")
        return DeviceCodeInfo(
            deviceCode = data.check,
            userCode = data.pin,
            verificationUrl = data.userUrl,
            interval = 5,
            expiresIn = data.expiresIn,
        )
    }

    private suspend fun adPollDeviceCode(pin: String, check: String): String? {
        return try {
            val resp: AdResponse<AdPinCheckData> = httpClient.get("$AD_BASE/pin/check") {
                parameter("agent", AD_AGENT)
                parameter("pin", pin)
                parameter("check", check)
            }.body()
            if (resp.data?.activated == true && resp.data.apikey != null) {
                resp.data.apikey
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun adResolveStream(
        apiKey: String,
        infoHash: String,
        magnet: String,
        magnetMode: String,
        fileIdx: Int?,
    ): ResolvedStream {
        debridDebugLog { "TORVE_AD: resolve hash=${infoHash.redactedHash()} fileIdx=$fileIdx magnetMode=$magnetMode" }
        if (magnetMode == "hash_only") {
            try {
                val cached = adCheckCache(apiKey, listOf(infoHash))[infoHash] == true
                debridDebugLog { "TORVE_AD: hashOnlyCacheCheck hash=${infoHash.redactedHash()} cached=$cached" }
            } catch (e: Exception) {
                debridDebugLog { "TORVE_AD: hashOnlyCacheCheck skipped hash=${infoHash.redactedHash()} reason=${e::class.simpleName}: ${DiagnosticsRedactor.redact(e.message)}" }
            }
        }

        // 1. Upload magnet
        val uploadResp: AdResponse<AdMagnetUploadData> = httpClient.get("$AD_BASE/magnet/upload") {
            parameter("agent", AD_AGENT)
            parameter("apikey", apiKey)
            parameter("magnets[]", magnet)
        }.body()

        val magnetId = uploadResp.data?.magnets?.firstOrNull()?.id
            ?: throw Exception("Upload failed")

        // 2. Poll status
        var links: List<AdLinkInfo> = emptyList()
        for (attempt in 0 until 30) {
            val statusResp: AdResponse<AdMagnetStatusData> =
                httpClient.get("$AD_BASE/magnet/status") {
                    parameter("agent", AD_AGENT)
                    parameter("apikey", apiKey)
                    parameter("id", magnetId)
                }.body()

            val info = statusResp.data?.magnets
            if (info != null && info.status == "Ready" && info.links.isNotEmpty()) {
                links = info.links
                break
            }
            delay(2000)
        }

        if (links.isEmpty()) throw Exception("Download timed out")

        // 3. Unlock the link
        val targetLink = if (fileIdx != null && fileIdx < links.size) links[fileIdx] else links[0]
        val unlockResp: AdResponse<AdUnlockData> = httpClient.get("$AD_BASE/link/unlock") {
            parameter("agent", AD_AGENT)
            parameter("apikey", apiKey)
            parameter("link", targetLink.link)
        }.body()

        val data = unlockResp.data ?: throw Exception("Service unlock failed")
        return ResolvedStream(
            url = data.link,
            service = DebridServiceType.ALL_DEBRID,
            fileName = data.filename,
            fileSize = data.size,
        )
    }

    // -------------------------------------------------------------------------
    // Premiumize
    // -------------------------------------------------------------------------

    private suspend fun pmVerifyApiKey(apiKey: String): DebridResult {
        return try {
            val resp: PmAccountResponse = httpClient.get("$PM_BASE/account/info") {
                pmAuthorize(apiKey)
            }.body()
            if (resp.status == "success") {
                DebridResult(
                    success = true,
                    user = DebridUser(
                        username = resp.customerId?.toString() ?: "User",
                        premium = (resp.premiumUntil ?: 0) > currentTimeMillis() / 1000,
                        expiresAt = resp.premiumUntil?.let {
                            kotlinx.datetime.Instant.fromEpochSeconds(it).toString()
                        },
                        points = resp.limitUsed,
                    ),
                )
            } else {
                DebridResult(success = false, error = resp.message ?: "Invalid API key")
            }
        } catch (e: Exception) {
            DebridResult(success = false, error = extractError(e, "Premiumize"))
        }
    }

    private suspend fun pmGetDeviceCode(): DeviceCodeInfo {
        val respText = httpClient.submitForm(
            url = PM_OAUTH,
            formParameters = Parameters.build {
                append("response_type", "device_code")
                append("client_id", PM_CLIENT_ID)
            },
        ).bodyAsText()
        val resp = json.decodeFromString<PmDeviceCodeResponse>(respText)
        val verificationUrl = resp.verificationUri
            ?.takeIf { it.isNotBlank() }
            ?: resp.verificationUrl?.takeIf { it.isNotBlank() }
            ?: "https://www.premiumize.me/device"
        return DeviceCodeInfo(
            deviceCode = resp.deviceCode,
            userCode = resp.userCode,
            verificationUrl = verificationUrl,
            interval = resp.interval,
            expiresIn = resp.expiresIn,
        )
    }

    private suspend fun pmPollDeviceCode(deviceCode: String): String? {
        return try {
            val respText = httpClient.submitForm(
                url = PM_OAUTH,
                formParameters = Parameters.build {
                    append("grant_type", "device_code")
                    append("client_id", PM_CLIENT_ID)
                    append("code", deviceCode)
                },
            ).bodyAsText()
            val resp = json.decodeFromString<PmDeviceTokenResponse>(respText)
            resp.accessToken?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun pmResolveStream(
        apiKey: String,
        infoHash: String,
        magnet: String,
        magnetMode: String,
    ): ResolvedStream {
        debridDebugLog { "TORVE_PM: resolve hash=${infoHash.redactedHash()} magnetMode=$magnetMode" }
        if (magnetMode == "hash_only") {
            try {
                val cached = pmCheckCache(apiKey, listOf(infoHash))[infoHash] == true
                debridDebugLog { "TORVE_PM: hashOnlyCacheCheck hash=${infoHash.redactedHash()} cached=$cached" }
            } catch (e: Exception) {
                debridDebugLog { "TORVE_PM: hashOnlyCacheCheck skipped hash=${infoHash.redactedHash()} reason=${e::class.simpleName}: ${DiagnosticsRedactor.redact(e.message)}" }
            }
        }

        // Premiumize directdl handles cached torrents
        val resp: PmDirectDlResponse = httpClient.submitForm(
            url = "$PM_BASE/transfer/directdl",
            formParameters = Parameters.build {
                pmAuthorize(apiKey)
                append("src", magnet)
            },
        ) {
            pmAccessToken(apiKey)?.let { header("Authorization", "Bearer $it") }
        }.body()

        if (resp.status != "success" || resp.content.isEmpty()) {
            throw Exception("Failed to resolve stream")
        }

        // Pick the largest video file
        val videoFile = resp.content
            .filter { it.link.isNotBlank() }
            .maxByOrNull { it.size }
            ?: throw Exception("No downloadable files found")

        return ResolvedStream(
            url = videoFile.streamLink ?: videoFile.link,
            service = DebridServiceType.PREMIUMIZE,
            fileName = videoFile.path.substringAfterLast('/'),
            fileSize = videoFile.size,
        )
    }

    // -------------------------------------------------------------------------
    // TorBox
    // -------------------------------------------------------------------------

    private suspend fun tbVerifyApiKey(apiKey: String): DebridResult {
        return try {
            val resp: TbResponse<TbUserData> = httpClient.get("$TB_BASE/user/me") {
                header("Authorization", "Bearer $apiKey")
            }.body()
            if (resp.success && resp.data != null) {
                DebridResult(
                    success = true,
                    user = DebridUser(
                        username = resp.data.email ?: "TorBox User",
                        email = resp.data.email,
                        premium = resp.data.plan > 0,
                        expiresAt = resp.data.premiumExpiresAt,
                    ),
                )
            } else {
                DebridResult(success = false, error = "Invalid API key")
            }
        } catch (e: Exception) {
            DebridResult(success = false, error = extractError(e, "TorBox"))
        }
    }

    private suspend fun tbResolveStream(
        apiKey: String,
        infoHash: String,
        magnet: String,
        magnetMode: String,
        fileIdx: Int?,
    ): ResolvedStream {
        debridDebugLog { "TORVE_TB: resolve hash=${infoHash.redactedHash()} fileIdx=$fileIdx magnetMode=$magnetMode" }
        if (magnetMode == "hash_only") {
            try {
                val cached = tbCheckCache(apiKey, listOf(infoHash))[infoHash] == true
                debridDebugLog { "TORVE_TB: hashOnlyCacheCheck hash=${infoHash.redactedHash()} cached=$cached" }
            } catch (e: Exception) {
                debridDebugLog { "TORVE_TB: hashOnlyCacheCheck skipped hash=${infoHash.redactedHash()} reason=${e::class.simpleName}: ${DiagnosticsRedactor.redact(e.message)}" }
            }
        }

        // 1. Create torrent
        val createResp: TbResponse<TbTorrentData> = httpClient.submitForm(
            url = "$TB_BASE/torrents/createtorrent",
            formParameters = Parameters.build {
                append("magnet", magnet)
            },
        ) {
            header("Authorization", "Bearer $apiKey")
        }.body()

        val torrentId = createResp.data?.id ?: throw Exception("Create download failed")

        // 2. Poll until ready
        for (attempt in 0 until 30) {
            val infoResp: TbResponse<TbTorrentInfoData> =
                httpClient.get("$TB_BASE/torrents/mylist") {
                    header("Authorization", "Bearer $apiKey")
                    parameter("id", torrentId)
                }.body()

            val info = infoResp.data
            if (info != null && info.downloadState == "downloaded" && info.files.isNotEmpty()) {
                // 3. Get download link
                val targetFile = if (fileIdx != null && fileIdx < info.files.size) {
                    info.files[fileIdx]
                } else {
                    info.files.maxByOrNull { it.size } ?: info.files.first()
                }

                val linkResp: TbResponse<TbDownloadLinkData> =
                    httpClient.get("$TB_BASE/torrents/requestdl") {
                        header("Authorization", "Bearer $apiKey")
                        parameter("torrent_id", torrentId)
                        parameter("file_id", targetFile.id)
                    }.body()

                val downloadUrl = linkResp.data?.data
                    ?: throw Exception("No download link available")

                return ResolvedStream(
                    url = downloadUrl,
                    service = DebridServiceType.TORBOX,
                    fileName = targetFile.name,
                    fileSize = targetFile.size,
                )
            }
            delay(2000)
        }

        throw Exception("Download timed out")
    }

    // -------------------------------------------------------------------------
    // Cache Check Implementations
    // -------------------------------------------------------------------------

    private suspend fun rdCheckCache(apiKey: String, hashes: List<String>): Map<String, Boolean> {
        // RD /torrents/instantAvailability/{hash1}/{hash2}/...
        val hashPath = hashes.joinToString("/")
        val rawResp = httpClient.get("$RD_BASE/torrents/instantAvailability/$hashPath") {
            header("Authorization", "Bearer $apiKey")
        }
        if (rawResp.status.value == 401) {
            throw RdAuthException("Session token expired (HTTP 401, instantAvailability)")
        }
        if (rawResp.status.value !in 200..299) {
            if (rawResp.status.value in 500..599) {
                throw DebridServiceUnavailableException("Real-Debrid is unavailable right now. Try again later.")
            }
            throw Exception("Real-Debrid cache check failed (HTTP ${rawResp.status.value}).")
        }
        val respText = rawResp.bodyAsText()

        val result = mutableMapOf<String, Boolean>()
        val parsed = json.parseToJsonElement(respText)
        if (parsed is kotlinx.serialization.json.JsonObject) {
            for (hash in hashes) {
                val entry = parsed[hash.lowercase()]
                    ?: parsed[hash.uppercase()]
                    ?: parsed[hash]
                // If there's a non-empty "rd" array, the hash is cached
                val isCached = if (entry is kotlinx.serialization.json.JsonObject) {
                    val rd = entry["rd"]
                    rd is kotlinx.serialization.json.JsonArray && rd.isNotEmpty()
                } else false
                result[hash] = isCached
            }
        }
        return result
    }

    private suspend fun rdGetInventory(apiKey: String): List<JsonObject> {
        val raw = httpClient.get("$RD_BASE/torrents") {
            header("Authorization", "Bearer $apiKey")
        }.bodyAsText()
        return extractInventoryItems(json.parseToJsonElement(raw))
    }

    private suspend fun rdFindExistingTorrentId(apiKey: String, infoHash: String): String? {
        return try {
            val normalized = normalizeInfoHash(infoHash).lowercase()
            rdGetInventory(apiKey).firstNotNullOfOrNull { item ->
                val hash = item["hash"]?.jsonPrimitive?.content?.let(::normalizeInfoHash)?.lowercase()
                if (hash == normalized) {
                    item["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            if (e is RdAuthException || e is DebridServiceUnavailableException) throw e
            debridDebugLog { "TORVE_RD: inventory lookup skipped hash=${infoHash.redactedHash()} reason=${e::class.simpleName}: ${DiagnosticsRedactor.redact(e.message)}" }
            null
        }
    }

    private suspend fun adCheckCache(apiKey: String, hashes: List<String>): Map<String, Boolean> {
        // AD /magnet/instant — magnets[]=hash1&magnets[]=hash2
        val respText = httpClient.get("$AD_BASE/magnet/instant") {
            parameter("agent", AD_AGENT)
            parameter("apikey", apiKey)
            hashes.forEach { parameter("magnets[]", it) }
        }.bodyAsText()

        val result = mutableMapOf<String, Boolean>()
        val parsed = json.parseToJsonElement(respText)
        if (parsed is kotlinx.serialization.json.JsonObject) {
            val data = parsed["data"]
            if (data is kotlinx.serialization.json.JsonObject) {
                val magnets = data["magnets"]
                if (magnets is kotlinx.serialization.json.JsonArray) {
                    magnets.forEachIndexed { index, element ->
                        if (index < hashes.size && element is kotlinx.serialization.json.JsonObject) {
                            val instant = element["instant"]
                            result[hashes[index]] = instant is kotlinx.serialization.json.JsonPrimitive && instant.content == "true"
                        }
                    }
                }
            }
        }
        return result
    }

    private suspend fun pmCheckCache(apiKey: String, hashes: List<String>): Map<String, Boolean> {
        // PM /cache/check — items[]=hash1&items[]=hash2
        val resp: PmCacheCheckResponse = httpClient.get("$PM_BASE/cache/check") {
            pmAuthorize(apiKey)
            hashes.forEach { parameter("items[]", it) }
        }.body()

        val result = mutableMapOf<String, Boolean>()
        resp.response.forEachIndexed { index, cached ->
            if (index < hashes.size) {
                result[hashes[index]] = cached
            }
        }
        return result
    }

    private suspend fun tbCheckCache(apiKey: String, hashes: List<String>): Map<String, Boolean> {
        // TB /torrents/checkcached — hash=hash1,hash2,hash3
        val hashParam = hashes.joinToString(",")
        val respText = httpClient.get("$TB_BASE/torrents/checkcached") {
            header("Authorization", "Bearer $apiKey")
            parameter("hash", hashParam)
            parameter("list_files", false)
        }.bodyAsText()

        val result = mutableMapOf<String, Boolean>()
        val parsed = json.parseToJsonElement(respText)
        if (parsed is kotlinx.serialization.json.JsonObject) {
            val data = parsed["data"]
            if (data is kotlinx.serialization.json.JsonObject) {
                for (hash in hashes) {
                    val entry = data[hash.lowercase()] ?: data[hash]
                    val isCached = entry is kotlinx.serialization.json.JsonArray && entry.isNotEmpty()
                    result[hash] = isCached
                }
            }
        }
        return result
    }

    private suspend fun tbGetInventory(apiKey: String): List<JsonObject> {
        val raw = httpClient.get("$TB_BASE/torrents/mylist") {
            header("Authorization", "Bearer $apiKey")
        }.bodyAsText()
        return extractInventoryItems(json.parseToJsonElement(raw))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun extractError(e: Exception, provider: String): String {
        val message = e.message ?: "Unknown error"
        return when {
            "401" in message || "403" in message -> "Invalid API key \u2014 please check your credentials"
            "timeout" in message.lowercase() -> "Cannot reach streaming service \u2014 check your connection"
            else -> "Could not connect to the service. Please try again."
        }
    }

    private fun currentTimeMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    private fun cacheCheckKey(
        provider: DebridServiceType,
        credentialKey: Int,
        infoHash: String,
    ): String = "${provider.name}:$credentialKey:$infoHash"
}

private fun String.redactedHash(): String {
    return if (length <= 10) "<hash>" else "${take(6)}...${takeLast(4)}"
}
