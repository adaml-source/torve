package com.torve.data.profile

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.random.Random

@Serializable
data class CloudLibraryBackup(val id: String, @SerialName("created_at") val createdAt: String,
    val platform: String, val reason: String, @SerialName("byte_count") val byteCount: Long,
    val counts: Map<String, Int> = emptyMap())

interface CloudBackupIdentity {
    fun account(): String?
    fun dataOwner(): String
    suspend fun token(expectedAccount: String): String?
}

interface CloudBackupPayloads {
    suspend fun export(expectedAccount: String): String
    suspend fun restore(payload: String, expectedAccount: String)
}

class CloudBackupFailure(message: String, val retryable: Boolean = false) : IllegalStateException(message)

/** The account snapshot contract is shared with Apple; credentials never enter the payload. */
class CloudLibraryBackups(private val http: HttpClient, private val identity: CloudBackupIdentity,
    private val library: CloudBackupPayloads, private val endpoint: String = "https://api.torve.app") {
    private val guardedHttp = http.config { followRedirects = false }
    private val json = Json { ignoreUnknownKeys = true }
    private data class Owner(val account: String, val data: String)
    private fun capture(): Owner = Owner(identity.account()?.takeIf { it.isNotBlank() }
        ?: throw CloudBackupFailure("Sign in to use cloud backup."), identity.dataOwner())
    private fun check(owner: Owner) {
        if (identity.account() != owner.account || identity.dataOwner() != owner.data)
            throw CloudBackupFailure("Your account or profile changed. Try again.")
    }

    suspend fun list(): List<CloudLibraryBackup> {
        val owner = capture()
        val response = request(owner, HttpMethod.Get)
        return response["backups"]?.jsonArray.orEmpty().take(10).map { json.decodeFromJsonElement(it) }
    }

    suspend fun create(platform: String, reason: String = "manual", requestId: String = newRequestId()): CloudLibraryBackup =
        create(capture(), platform, reason, requestId)

    private suspend fun create(owner: Owner, platform: String, reason: String, requestId: String): CloudLibraryBackup {
        require(platform in setOf("android", "firetv"))
        require(reason in setOf("manual", "automatic", "pre_restore"))
        val raw = library.export(owner.account)
        check(owner)
        require(raw.encodeToByteArray().size <= MAX_BYTES) { "Backup is too large." }
        // The export service applies the same allowlist and protected-profile rules as Apple.
        val body = buildJsonObject {
            put("request_id", requestId); put("platform", platform); put("reason", reason)
            put("payload", json.parseToJsonElement(raw))
        }
        val response = request(owner, HttpMethod.Post, body = body)
        return json.decodeFromJsonElement(response)
    }

    /** A failed safety snapshot prevents every local mutation. */
    suspend fun restore(backupId: String, platform: String): CloudLibraryBackup {
        require(backupId.matches(Regex("[0-9a-fA-F-]{36}")))
        val owner = capture()
        val envelope = request(owner, HttpMethod.Get, "/$backupId")
        val payload = envelope["payload"] as? JsonObject
            ?: throw CloudBackupFailure("This backup could not be opened.")
        val safety = create(owner, platform, "pre_restore", newRequestId())
        check(owner)
        library.restore(payload.toString(), owner.account)
        check(owner)
        return safety
    }

    private suspend fun request(owner: Owner, method: HttpMethod, path: String = "", body: JsonObject? = null): JsonObject = withTimeout(45_000L) {
        check(owner)
        val token = identity.token(owner.account)
            ?: throw CloudBackupFailure("Sign in again to use cloud backup.")
        check(owner)
        val response = guardedHttp.request("${endpoint.trimEnd('/')}/me/backups$path") {
            this.method = method
            expectSuccess = false
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.CacheControl, "no-store")
            contentType(ContentType.Application.Json)
            if (body != null) setBody(body.toString())
        }
        check(owner)
        if (response.status.value !in 200..299) throw CloudBackupFailure(when (response.status.value) {
            401 -> "Sign in again to use cloud backup."
            403 -> "Confirm your account email before using cloud backup."
            413 -> "Your backup exceeds the current size limit."
            422 -> "This backup contains unsupported data. Update Torve and try again."
            else -> "Cloud backup is temporarily unavailable. Please try again."
        }, retryable = response.status.value >= 500 || response.status.value in setOf(408, 429))
        if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_BYTES)
            throw CloudBackupFailure("Your backup exceeds the current size limit.")
        val bytes = response.bodyAsChannel().readRemaining((MAX_BYTES + 1).toLong()).readBytes()
        if (bytes.size > MAX_BYTES) throw CloudBackupFailure("Your backup exceeds the current size limit.")
        check(owner)
        json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }

    companion object {
        private const val MAX_BYTES = 2_000_000
        private fun newRequestId(): String {
            val hex = Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            return "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}
