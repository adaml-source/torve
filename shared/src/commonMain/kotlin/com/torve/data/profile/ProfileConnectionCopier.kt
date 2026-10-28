package com.torve.data.profile

import com.torve.data.account.*
import com.torve.data.auth.*
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.*
import kotlinx.serialization.json.*

class ProfileConnectionCopier(private val database: TorveDatabase, private val ids: UserIdProvider,
    private val secrets: IntegrationSecretStore, private val family: FamilyControls, private val auth: AuthClient,
    private val api: AccountSettingsApi) {
    private val keys = listOf(IntegrationSecretKey.DEBRID_API_KEY_TORBOX, IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID,
        IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID, IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE,
        IntegrationSecretKey.TRAKT_TOKENS, IntegrationSecretKey.SIMKL_ACCESS_TOKEN, IntegrationSecretKey.JELLYFIN_API_KEY,
        IntegrationSecretKey.PLEX_ACCESS_TOKEN, IntegrationSecretKey.MDBLIST_API_KEY, IntegrationSecretKey.OMDB_API_KEY,
        IntegrationSecretKey.CLAUDE_API_KEY, IntegrationSecretKey.CHATGPT_API_KEY, IntegrationSecretKey.GEMINI_API_KEY,
        IntegrationSecretKey.PERPLEXITY_API_KEY, IntegrationSecretKey.DEEPSEEK_API_KEY)
    private suspend fun connectionValue(secure: ProfileScopedSecretStore, key: IntegrationSecretKey, profile: String): String? {
        val current = secure.readForProfile(key, profile)?.takeIf { it.isNotBlank() }
        if (current != null || key != IntegrationSecretKey.OMDB_API_KEY) return current
        val owner = profileDataUserId(ids.currentUserId(), profile)
        val legacy = database.torveQueries.getPreference(owner, "omdb_api_key").executeAsOneOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        secure.writeForProfile(key, profile, legacy, IntegrationStorageMode.DEVICE_ONLY)
        database.torveQueries.deletePreference(owner, "omdb_api_key")
        return legacy
    }
    suspend fun choices(source: String): List<String> = ids.withPersonalDataScope {
        family.requireParent()
        val secure = secrets as? ProfileScopedSecretStore ?: error("Connection copying is unavailable on this device.")
        val local = keys.filter { connectionValue(secure, it, source)?.isNotBlank() == true }.map { it.name }
        (local + family.connectionChoices(source)).distinct()
    }
    suspend fun copy(source: String, target: String, services: List<String>, replace: Boolean, sync: Boolean) = ids.withPersonalDataScope {
        family.requireParent()
        require(source != target && services.isNotEmpty()) { "Choose two profiles and at least one connection." }
        require(services.all { value -> keys.any { it.name == value } }) { "Unsupported connection." }
        val q = database.torveQueries
        val account = ids.currentUserId()
        require(q.getProfile(account, source).executeAsOneOrNull() != null && q.getProfile(account, target).executeAsOneOrNull() != null) { "Profile is no longer available." }
        val secure = secrets as? ProfileScopedSecretStore ?: error("Connection copying is unavailable on this device.")
        val selected = services.map { IntegrationSecretKey.valueOf(it) }
        if (!replace) check(selected.none { connectionValue(secure, it, target)?.isNotBlank() == true }) { "The destination already has a connection. Select Replace existing to continue." }
        val sourcePerson = profileDataUserId(account, source)
        val targetPerson = profileDataUserId(account, target)
        fun pref(key: String) = q.getPreference(sourcePerson, key).executeAsOneOrNull()
        val values = selected.associateWith { connectionValue(secure, it, source) }.toMutableMap()
        val sourceClientId = pref("trakt_client_id").orEmpty()
        val sourceClientSecret = secure.readForProfile(IntegrationSecretKey.TRAKT_CLIENT_SECRET, source).orEmpty()
        val oauthIdentity = if (sourceClientId.isNotBlank() && sourceClientSecret.isNotBlank())
            mapOf("client_id" to sourceClientId, "client_secret" to sourceClientSecret) else emptyMap()
        if (sync) {
            val existing = family.connectionChoices(source).toSet()
            val token = auth.getValidAccessToken() ?: error("Sign in to copy connections across devices.")
            // Explicit Sync consent promotes only the selected connection, never the complete secure store.
            for ((key, raw) in values) if (key.name !in existing && !raw.isNullOrBlank()) {
                val credentials = when (key) {
                    IntegrationSecretKey.TRAKT_TOKENS -> Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content } + oauthIdentity
                    IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID -> buildMap {
                        put("api_key", raw)
                        listOf("refresh_token" to IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN, "client_id" to IntegrationSecretKey.DEBRID_RD_CLIENT_ID, "client_secret" to IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET).forEach { (name, extra) ->
                            secure.readForProfile(extra, source)?.let { put(name, it) }
                        }
                    }
                    IntegrationSecretKey.JELLYFIN_API_KEY -> buildMap { put("api_key", raw); pref("jellyfin_server_url")?.let { put("server_url", it) }; pref("jellyfin_selected_user_id")?.let { put("user_id", it) } }
                    else -> mapOf("api_key" to raw)
                }
                val type = com.torve.data.integrations.profileIntegrationType(key.name, source)
                check(api.saveIntegration(token, type, SaveIntegrationRequest(integrationType = type, storageMode = "account", credentials = credentials,
                    config = buildMap { put("torve_profile_id", source)
                        if (key == IntegrationSecretKey.OMDB_API_KEY) pref("rating_display_prefs")?.let { put("rating_display_prefs", it) }
                    }, displayIdentifier = connectionLabel(key.name)))) { "Could not save the source connection. Nothing was copied to the destination." }
            }
            if (IntegrationSecretKey.TRAKT_TOKENS in selected && oauthIdentity.isNotEmpty()) {
                val current = api.getIntegrationCredentials(token, com.torve.data.integrations.profileIntegrationType("TRAKT_TOKENS", source))
                    ?: error("The source Trakt connection could not be loaded.")
                api.rotateTraktTokens(token, source, current["refreshToken"] ?: current["refresh_token"].orEmpty(), current + oauthIdentity)
            }
            if (IntegrationSecretKey.OMDB_API_KEY in selected && "OMDB_API_KEY" in existing) {
                val type = com.torve.data.integrations.profileIntegrationType("OMDB_API_KEY", source)
                val credentials = api.getIntegrationCredentials(token, type) ?: error("OMDb connection could not be loaded.")
                check(api.saveIntegration(token, type, SaveIntegrationRequest(integrationType = type, storageMode = "account", credentials = credentials,
                    config = buildMap { put("torve_profile_id", source); pref("rating_display_prefs")?.let { put("rating_display_prefs", it) } }, displayIdentifier = "OMDb"))) { "OMDb settings could not be copied." }
            }
            family.copyConnections(source, target, services, replace)
            for (key in selected) {
                // Sync consent applies to both ends. The canonical account credential can be
                // newer than the local slot after another device refreshes an OAuth connection.
                val local = values[key]
                if (!local.isNullOrBlank()) secure.writeForProfile(key, source, local, IntegrationStorageMode.ACCOUNT)
                if (key == IntegrationSecretKey.OMDB_API_KEY) {
                    val credentials = api.getIntegrationCredentials(token, com.torve.data.integrations.profileIntegrationType(key.name, target))
                        ?: error("OMDb was copied, but could not be loaded. Refresh the connection to retry.")
                    values[key] = credentials["api_key"] ?: credentials["apikey"] ?: credentials["token"]
                    check(!values[key].isNullOrBlank()) { "The copied OMDb connection has no API key." }
                }
                if (key == IntegrationSecretKey.TRAKT_TOKENS) {
                    val credentials = api.getIntegrationCredentials(token, com.torve.data.integrations.profileIntegrationType(key.name, target))
                        ?: error("Trakt was copied, but could not be loaded. Refresh the connection to retry.")
                    val normalized = mapOf(
                        "accessToken" to (credentials["accessToken"] ?: credentials["access_token"].orEmpty()),
                        "refreshToken" to (credentials["refreshToken"] ?: credentials["refresh_token"].orEmpty()),
                        "expiresIn" to (credentials["expiresIn"] ?: credentials["expires_in"] ?: "0"),
                        "createdAt" to (credentials["createdAt"] ?: credentials["created_at"] ?: "0"))
                    values[key] = JsonObject(normalized.mapValues { JsonPrimitive(it.value) }).toString()
                    secure.writeForProfile(key, source, values.getValue(key)!!, IntegrationStorageMode.ACCOUNT)
                    val id = credentials["client_id"] ?: sourceClientId
                    val secret = credentials["client_secret"] ?: sourceClientSecret
                    q.setPreference(targetPerson, "trakt_client_id", id)
                    if (secret.isNotBlank()) secure.writeForProfile(IntegrationSecretKey.TRAKT_CLIENT_SECRET, target, secret, IntegrationStorageMode.ACCOUNT)
                    else secure.removeForProfile(IntegrationSecretKey.TRAKT_CLIENT_SECRET, target)
                }
            }
        }
        ids.checkPersonalScope(); family.requireParent()
        for ((key, raw) in values) {
            if (raw.isNullOrBlank()) { check(sync) { "This connection is available from your account only. Turn on account sync to copy it." }; continue }
            secure.writeForProfile(key, target, raw, if (sync) IntegrationStorageMode.ACCOUNT else IntegrationStorageMode.DEVICE_ONLY)
            if (key == IntegrationSecretKey.TRAKT_TOKENS && !sync) {
                q.setPreference(targetPerson, "trakt_client_id", sourceClientId)
                if (sourceClientSecret.isNotBlank()) secure.writeForProfile(IntegrationSecretKey.TRAKT_CLIENT_SECRET, target, sourceClientSecret, IntegrationStorageMode.DEVICE_ONLY)
                else secure.removeForProfile(IntegrationSecretKey.TRAKT_CLIENT_SECRET, target)
            }
            if (key == IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID) {
                listOf(IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN, IntegrationSecretKey.DEBRID_RD_CLIENT_ID, IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET).forEach { extra ->
                    secure.readForProfile(extra, source)?.let { secure.writeForProfile(extra, target, it, if (sync) IntegrationStorageMode.ACCOUNT else IntegrationStorageMode.DEVICE_ONLY) }
                }
            }
            val preferences = when (key) {
                IntegrationSecretKey.DEBRID_API_KEY_TORBOX, IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID, IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE -> emptyList()
                IntegrationSecretKey.JELLYFIN_API_KEY -> listOf("jellyfin_server_url", "jellyfin_selected_user_id", "app_feature_jellyfin_enabled")
                IntegrationSecretKey.PLEX_ACCESS_TOKEN -> listOf("plex_server_url")
                IntegrationSecretKey.OMDB_API_KEY -> listOf("rating_display_prefs")
                else -> emptyList()
            }
            preferences.forEach { name -> pref(name)?.let { q.setPreference(targetPerson, name, it) } }
            val provider = when (key) {
                IntegrationSecretKey.DEBRID_API_KEY_TORBOX -> "TORBOX"
                IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID -> "REAL_DEBRID"
                IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID -> "ALL_DEBRID"
                IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE -> "PREMIUMIZE"
                else -> null
            }
            if (provider != null) q.setPreference(targetPerson, "debrid_provider", provider)
        }
    }
}

fun connectionLabel(key: String): String = when (key) {
    "DEBRID_API_KEY_TORBOX" -> "TorBox"
    "DEBRID_API_KEY_REAL_DEBRID" -> "Real-Debrid"
    "DEBRID_API_KEY_ALL_DEBRID" -> "AllDebrid"
    "DEBRID_API_KEY_PREMIUMIZE" -> "Premiumize"
    "TRAKT_TOKENS" -> "Trakt"
    "SIMKL_ACCESS_TOKEN" -> "SIMKL"
    "JELLYFIN_API_KEY" -> "Jellyfin"
    "PLEX_ACCESS_TOKEN" -> "Plex"
    "MDBLIST_API_KEY" -> "MDBList"
    "OMDB_API_KEY" -> "OMDb"
    else -> key.removeSuffix("_API_KEY").lowercase().replaceFirstChar { it.uppercase() }
}
