package com.torve.data.debrid

import com.torve.data.acceleration.AccelerationApi
import com.torve.data.acceleration.HashAvailabilityObservationDto
import com.torve.data.acceleration.extractInventoryItems
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.TranscodeUrls
import com.torve.domain.model.apiValue
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Callback to refresh an expired RD OAuth token.
 * Returns the new access token, or null if refresh is not possible.
 */
class RdAuthException(message: String) : Exception(message)
class DebridMissingException(message: String) : Exception(message)
class DebridNeedsReconnectException(message: String) : Exception(message)
class DebridNoCachedStreamException(message: String) : Exception(message)
class DebridServiceUnavailableException(message: String) : Exception(message)

fun interface RdTokenRefresher {
    suspend fun refresh(): String?
}

class DebridClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val accelerationApi: AccelerationApi? = null,
    var rdTokenRefresher: RdTokenRefresher? = null,
) {
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
    }

    private fun pmAccessToken(credential: String): String? =
        credential.takeIf { it.startsWith(PM_OAUTH_PREFIX) }?.removePrefix(PM_OAUTH_PREFIX)

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
        return try {
            val result = when (provider) {
                DebridServiceType.REAL_DEBRID -> rdCheckCache(apiKey, infoHashes)
                DebridServiceType.ALL_DEBRID -> adCheckCache(apiKey, infoHashes)
                DebridServiceType.PREMIUMIZE -> pmCheckCache(apiKey, infoHashes)
                DebridServiceType.TORBOX -> tbCheckCache(apiKey, infoHashes)
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
            result
        } catch (_: Exception) {
            emptyMap()
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
    ): ResolvedStream {
        return when (provider) {
            DebridServiceType.REAL_DEBRID -> {
                try {
                    rdResolveStream(apiKey, infoHash, fileIdx)
                } catch (e: RdAuthException) {
                    // Token expired — try refresh and retry once
                    val newKey = rdTokenRefresher?.refresh()
                    if (newKey != null) {
                        println("TORVE_RD: token refreshed, retrying resolve")
                        rdResolveStream(newKey, infoHash, fileIdx)
                    } else {
                        throw DebridNeedsReconnectException("Real-Debrid needs reconnecting. Open Panda settings.")
                    }
                }
            }
            DebridServiceType.ALL_DEBRID -> adResolveStream(apiKey, infoHash, fileIdx)
            DebridServiceType.PREMIUMIZE -> pmResolveStream(apiKey, infoHash)
            DebridServiceType.TORBOX -> tbResolveStream(apiKey, infoHash, fileIdx)
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
                        println("TORVE_RD: token refreshed, retrying unrestrict")
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
        println("TORVE_RD: token refresh HTTP ${rawResp.status.value} body=$bodyText")
        if (rawResp.status.value !in 200..299) {
            throw Exception("Token refresh failed (${rawResp.status.value}): $bodyText")
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
        println("TORVE_RD: addMagnet HTTP ${rawResp.status.value} body=$bodyText")
        if (rawResp.status.value == 401) {
            throw RdAuthException("Session token expired (HTTP 401)")
        }
        val resp: RdAddMagnetResponse = json.decodeFromString(bodyText)
        return resp.id
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
        val rawResp = httpClient.get("$RD_BASE/torrents/info/$torrentId") {
            header("Authorization", "Bearer $apiKey")
        }
        if (rawResp.status.value == 401) {
            throw RdAuthException("Session token expired (HTTP 401, torrentInfo)")
        }
        val bodyText = rawResp.bodyAsText()
        if (torrentId.isBlank()) {
            println("TORVE_RD: getTorrentInfo HTTP ${rawResp.status.value} (blank torrentId!) body=${bodyText.take(200)}")
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
        fileIdx: Int?,
    ): ResolvedStream {
        val magnet = "magnet:?xt=urn:btih:$infoHash"

        // 1. Add magnet
        val torrentId = rdAddMagnet(apiKey, magnet)
        println("TORVE_RD: addMagnet done torrentId=$torrentId fileIdx=$fileIdx")

        // 2. Get torrent info to find file IDs, then select the target file
        val initialInfo = rdGetTorrentInfo(apiKey, torrentId)
        println("TORVE_RD: initialInfo status=${initialInfo.status} files=${initialInfo.files.size} links=${initialInfo.links.size}")

        // Select specific file if possible; fall back to "all" if no fileIdx
        val filesToSelect = if (fileIdx != null && initialInfo.files.isNotEmpty()) {
            // RD file IDs are 1-based; fileIdx from addons is typically 0-based
            val rdFileId = initialInfo.files.getOrNull(fileIdx)?.id
                ?: (fileIdx + 1) // Fallback: assume 1-based offset
            rdFileId.toString()
        } else if (initialInfo.files.isNotEmpty()) {
            // No fileIdx — select the largest video file
            val videoExts = setOf("mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v")
            val largestVideo = initialInfo.files
                .filter { f -> videoExts.any { ext -> f.path.lowercase().endsWith(".$ext") } }
                .maxByOrNull { it.bytes }
            largestVideo?.id?.toString() ?: "all"
        } else {
            "all"
        }
        println("TORVE_RD: selectFiles=$filesToSelect")
        rdSelectFiles(apiKey, torrentId, filesToSelect)

        // 3. Poll until ready
        var links: List<String> = emptyList()
        for (attempt in 0 until 30) {
            val info = rdGetTorrentInfo(apiKey, torrentId)
            println("TORVE_RD: poll #$attempt status=${info.status} links=${info.links.size}")
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
        println("TORVE_RD: unrestricted filename=${file.filename} download=${file.download.take(80)}")

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
        fileIdx: Int?,
    ): ResolvedStream {
        val magnet = "magnet:?xt=urn:btih:$infoHash"

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
    ): ResolvedStream {
        val magnet = "magnet:?xt=urn:btih:$infoHash"

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
        fileIdx: Int?,
    ): ResolvedStream {
        val magnet = "magnet:?xt=urn:btih:$infoHash"

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
        val respText = httpClient.get("$RD_BASE/torrents/instantAvailability/$hashPath") {
            header("Authorization", "Bearer $apiKey")
        }.bodyAsText()

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
}
