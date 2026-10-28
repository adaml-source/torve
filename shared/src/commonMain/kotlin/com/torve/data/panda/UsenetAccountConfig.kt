package com.torve.data.panda

import com.torve.data.account.AccountSettingsApi
import com.torve.data.integrations.baseIntegrationType
import com.torve.data.integrations.integrationProfileId
import kotlinx.coroutines.CancellationException

/** Private account reference; opening settings must never install an extension. */
internal class UsenetAccountConfig(
    val configId: String?,
    val manifestToken: String?,
    val managementToken: String? = null,
)

/** Discover the active profile's saved setup even when this device has no local token. */
internal suspend fun AccountSettingsApi.getUsenetAccountConfig(
    accessToken: String,
    profileId: String,
): UsenetAccountConfig? {
    val integration = getIntegrations(accessToken)
        .filter {
            integrationProfileId(it.integrationType) == profileId &&
                baseIntegrationType(it.integrationType).lowercase() in setOf("panda", "panda_token") &&
                it.storageMode == "account"
        }
        .sortedBy { if (baseIntegrationType(it.integrationType).equals("PANDA_TOKEN", true)) 0 else 1 }
        .firstOrNull() ?: return null
    val credentials = getIntegrationCredentials(accessToken, integration.integrationType)
        ?: error("Saved Usenet credentials could not be loaded")
    fun value(key: String) = credentials[key]?.trim()?.takeIf { it.isNotEmpty() }
    val reference = UsenetAccountConfig(value("config_id"), value("token"), value("management_token"))
    check(reference.configId != null || reference.manifestToken != null) {
        "Saved Usenet configuration reference is unavailable"
    }
    return reference
}

/** Account auth takes precedence over stale device credentials; all fallback reads stay read-only. */
internal suspend fun PandaApiClient.readUsenetConfig(
    reference: UsenetAccountConfig,
    accountToken: String?,
): PandaConfigRecord {
    var failure: Exception? = null
    val id = reference.configId
    if (id != null) {
        for (bearer in listOfNotNull(accountToken, reference.managementToken).filter { it.isNotBlank() }.distinct()) {
            try { return getConfigAsManager(id, bearer) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
    }
    reference.manifestToken?.let { return getConfig(it) }
    throw failure ?: IllegalStateException("Sign in to load your saved Usenet settings")
}
