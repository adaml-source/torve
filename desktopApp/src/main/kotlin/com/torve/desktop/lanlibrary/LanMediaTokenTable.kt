package com.torve.desktop.lanlibrary

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory mapping from a manifest entry's opaque id to the real
 * filesystem path the LAN HTTP server should stream from. Tokens are
 * issued per-resolution and verified before any byte hits the wire.
 *
 * Design choices:
 *   - The mapping `id → path` is established at manifest-build time.
 *   - The mapping `(id, token) → path` is established only when the
 *     publisher is actively serving - issued via [issueAccessToken],
 *     consumed via [resolveTokenForId].
 *   - Tokens have a short TTL (default 30 minutes) so a leak via logs
 *     doesn't grant indefinite access.
 *   - The publisher can revoke all tokens via [clearAll] (e.g. on
 *     sign-out or when the download folder allowlist changes).
 */
class LanMediaTokenTable(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    private val tokenTtlMs: Long = DEFAULT_TOKEN_TTL_MS,
) {
    /** id → canonical absolute path. Never exposed in any manifest. */
    private val idToPath: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    /** (id, token) → expiresAtEpochMs. Token is opaque, base-32-ish. */
    private val tokenExpiry: ConcurrentHashMap<TokenKey, Long> = ConcurrentHashMap()

    fun registerEntry(id: String, file: File) {
        require(id.isNotBlank()) { "id required" }
        val canonical = runCatching { file.canonicalFile.absolutePath }.getOrNull()
            ?: error("could not canonicalize ${file.absolutePath}")
        idToPath[id] = canonical
    }

    /** Drop a single entry (and any of its outstanding tokens). */
    fun unregisterEntry(id: String) {
        idToPath.remove(id)
        tokenExpiry.keys.removeAll { it.id == id }
    }

    /** Drop everything - used on sign-out / allowlist change. */
    fun clearAll() {
        idToPath.clear()
        tokenExpiry.clear()
    }

    /**
     * Issue a fresh token for [id]. Returns null if the id isn't
     * registered.
     */
    fun issueAccessToken(id: String): String? {
        if (idToPath[id] == null) return null
        val token = freshToken()
        tokenExpiry[TokenKey(id, token)] = nowMs() + tokenTtlMs
        return token
    }

    /**
     * Validate a `(id, token)` pair and return the path if both:
     *   - the id is still registered
     *   - the token is still valid (matches and hasn't expired)
     *
     * Side effect: lazily prunes expired tokens.
     */
    fun resolveTokenForId(id: String, token: String): File? {
        val path = idToPath[id] ?: return null
        val key = TokenKey(id, token)
        val expiresAt = tokenExpiry[key] ?: return null
        if (expiresAt < nowMs()) {
            tokenExpiry.remove(key)
            return null
        }
        return File(path)
    }

    /** Expose for diagnostics - count of currently-registered ids. */
    fun registeredEntryCount(): Int = idToPath.size

    private fun freshToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        // Url-safe alphabet, no padding. Reuses the protocol-side
        // shape, but kept independent so the two layers can evolve
        // separately.
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(ALPHABET[v shr 4])
            sb.append(ALPHABET[v and 0x0F])
        }
        return sb.toString()
    }

    private data class TokenKey(val id: String, val token: String)

    companion object {
        const val TOKEN_BYTES = 24
        const val DEFAULT_TOKEN_TTL_MS = 30L * 60_000L
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuv"
    }
}
