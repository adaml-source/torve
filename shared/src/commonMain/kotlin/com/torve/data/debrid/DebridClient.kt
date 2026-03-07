package com.torve.data.debrid

import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.TranscodeUrls
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

class DebridClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        const val RD_BASE = "https://api.real-debrid.com/rest/1.0"
        const val RD_OAUTH = "https://api.real-debrid.com/oauth/v2"
        const val AD_BASE = "https://api.alldebrid.com/v4"
        const val PM_BASE = "https://www.premiumize.me/api"
        const val TB_BASE = "https://api.torbox.app/v1/api"

        const val RD_CLIENT_ID = "X245A4XAIBGVM"
        const val AD_AGENT = "torve"
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
        return provider == DebridServiceType.REAL_DEBRID || provider == DebridServiceType.ALL_DEBRID
    }

    suspend fun getDeviceCode(provider: DebridServiceType): DeviceCodeInfo? {
        return when (provider) {
            DebridServiceType.REAL_DEBRID -> rdGetDeviceCode()
            DebridServiceType.ALL_DEBRID -> adGetDeviceCode()
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
            when (provider) {
                DebridServiceType.REAL_DEBRID -> rdCheckCache(apiKey, infoHashes)
                DebridServiceType.ALL_DEBRID -> adCheckCache(apiKey, infoHashes)
                DebridServiceType.PREMIUMIZE -> pmCheckCache(apiKey, infoHashes)
                DebridServiceType.TORBOX -> tbCheckCache(apiKey, infoHashes)
            }
        } catch (_: Exception) {
            emptyMap()
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
            DebridServiceType.REAL_DEBRID -> rdResolveStream(apiKey, infoHash, fileIdx)
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
                val file = rdUnrestrictLink(apiKey, url)
                val transcode = if (file.streamable) rdGetTranscodeUrls(apiKey, file.id) else null
                ResolvedStream(
                    url = file.download,
                    service = provider,
                    fileName = file.filename,
                    mimeType = file.mimeType,
                    transcodeUrls = transcode,
                )
            }
            DebridServiceType.ALL_DEBRID -> {
                val resp: AdResponse<AdUnlockData> = httpClient.get("$AD_BASE/link/unlock") {
                    parameter("agent", AD_AGENT)
                    parameter("apikey", apiKey)
                    parameter("link", url)
                }.body()
                val data = resp.data ?: throw Exception("AllDebrid unlock failed")
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
                        append("apikey", apiKey)
                        append("src", url)
                    },
                ).body()
                if (resp.status != "success" || resp.content.isEmpty()) {
                    throw Exception("Premiumize failed to unrestrict link")
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
                val downloadId = createResp.data?.id ?: throw Exception("TorBox webdl failed")
                // Get the download link
                val linkResp: TbResponse<TbDownloadLinkData> =
                    httpClient.get("$TB_BASE/webdl/requestdl") {
                        header("Authorization", "Bearer $apiKey")
                        parameter("web_id", downloadId)
                    }.body()
                val downloadUrl = linkResp.data?.data ?: throw Exception("TorBox: no download link")
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
        val resp: RdTokenResponse = httpClient.submitForm(
            url = "$RD_OAUTH/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("code", refreshToken)
                append("grant_type", "http://oauth.net/grant_type/device/1.0")
            },
        ).body()
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
        val resp: RdAddMagnetResponse = httpClient.submitForm(
            url = "$RD_BASE/torrents/addMagnet",
            formParameters = Parameters.build { append("magnet", magnet) },
        ) {
            header("Authorization", "Bearer $apiKey")
        }.body()
        return resp.id
    }

    private suspend fun rdSelectFiles(apiKey: String, torrentId: String, files: String = "all") {
        httpClient.submitForm(
            url = "$RD_BASE/torrents/selectFiles/$torrentId",
            formParameters = Parameters.build { append("files", files) },
        ) {
            header("Authorization", "Bearer $apiKey")
        }
    }

    private suspend fun rdGetTorrentInfo(apiKey: String, torrentId: String): RdTorrentInfoResponse {
        return httpClient.get("$RD_BASE/torrents/info/$torrentId") {
            header("Authorization", "Bearer $apiKey")
        }.body()
    }

    private suspend fun rdUnrestrictLink(apiKey: String, link: String): UnrestrictedFile {
        val resp: RdUnrestrictResponse = httpClient.submitForm(
            url = "$RD_BASE/unrestrict/link",
            formParameters = Parameters.build { append("link", link) },
        ) {
            header("Authorization", "Bearer $apiKey")
        }.body()
        return UnrestrictedFile(
            id = resp.id,
            filename = resp.filename,
            mimeType = resp.mimeType,
            download = resp.download,
            streamable = resp.streamable == 1,
        )
    }

    private suspend fun rdGetTranscodeUrls(apiKey: String, fileId: String): TranscodeUrls? {
        return try {
            val resp: RdTranscodeResponse = httpClient.get("$RD_BASE/streaming/transcode/$fileId") {
                header("Authorization", "Bearer $apiKey")
            }.body()
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

        // 2. Select files
        rdSelectFiles(apiKey, torrentId, "all")

        // 3. Poll until ready
        var links: List<String> = emptyList()
        for (attempt in 0 until 30) {
            val info = rdGetTorrentInfo(apiKey, torrentId)
            if (info.status == "downloaded" && info.links.isNotEmpty()) {
                links = info.links
                break
            }
            if (info.status in listOf("error", "dead", "magnet_error")) {
                throw Exception("Real-Debrid download failed: ${info.status}")
            }
            delay(2000)
        }

        if (links.isEmpty()) {
            throw Exception("Download timed out — no links available")
        }

        // 4. Unrestrict the right link
        val linkIndex = if (fileIdx != null && fileIdx < links.size) fileIdx else 0
        val file = rdUnrestrictLink(apiKey, links[linkIndex])

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
        val data = resp.data ?: throw Exception("AllDebrid device code failed")
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
            ?: throw Exception("AllDebrid magnet upload failed")

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

        if (links.isEmpty()) throw Exception("AllDebrid download timed out")

        // 3. Unlock the link
        val targetLink = if (fileIdx != null && fileIdx < links.size) links[fileIdx] else links[0]
        val unlockResp: AdResponse<AdUnlockData> = httpClient.get("$AD_BASE/link/unlock") {
            parameter("agent", AD_AGENT)
            parameter("apikey", apiKey)
            parameter("link", targetLink.link)
        }.body()

        val data = unlockResp.data ?: throw Exception("AllDebrid unlock failed")
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
                parameter("apikey", apiKey)
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

    private suspend fun pmResolveStream(
        apiKey: String,
        infoHash: String,
    ): ResolvedStream {
        val magnet = "magnet:?xt=urn:btih:$infoHash"

        // Premiumize directdl handles cached torrents
        val resp: PmDirectDlResponse = httpClient.submitForm(
            url = "$PM_BASE/transfer/directdl",
            formParameters = Parameters.build {
                append("apikey", apiKey)
                append("src", magnet)
            },
        ).body()

        if (resp.status != "success" || resp.content.isEmpty()) {
            throw Exception("Premiumize failed to resolve stream")
        }

        // Pick the largest video file
        val videoFile = resp.content
            .filter { it.link.isNotBlank() }
            .maxByOrNull { it.size }
            ?: throw Exception("Premiumize: no downloadable files")

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

        val torrentId = createResp.data?.id ?: throw Exception("TorBox create download failed")

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
                    ?: throw Exception("TorBox: no download link")

                return ResolvedStream(
                    url = downloadUrl,
                    service = DebridServiceType.TORBOX,
                    fileName = targetFile.name,
                    fileSize = targetFile.size,
                )
            }
            delay(2000)
        }

        throw Exception("TorBox download timed out")
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
            parameter("apikey", apiKey)
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun extractError(e: Exception, provider: String): String {
        val message = e.message ?: "Unknown error"
        return when {
            "401" in message || "403" in message -> "Invalid $provider API key"
            "timeout" in message.lowercase() -> "Cannot reach $provider — check your connection"
            else -> "$provider error: $message"
        }
    }

    private fun currentTimeMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}
