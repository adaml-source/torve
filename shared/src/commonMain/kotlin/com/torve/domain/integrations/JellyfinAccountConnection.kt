package com.torve.domain.integrations

internal data class JellyfinAccountPayload(
    val credentials: Map<String, String>,
    val config: Map<String, String>,
)

internal data class RestoredJellyfinAccountConnection(
    val serverUrl: String,
    val apiKey: String,
    val selectedUserId: String?,
)

internal fun buildJellyfinAccountPayload(
    serverUrl: String,
    apiKey: String,
    selectedUserId: String?,
): JellyfinAccountPayload? {
    val normalizedUrl = normalizeJellyfinServerUrl(serverUrl) ?: return null
    val normalizedApiKey = apiKey.trim().takeIf { it.isNotEmpty() } ?: return null
    val normalizedUserId = selectedUserId?.trim()?.takeIf { it.isNotEmpty() }
    val credentials = buildMap {
        put("api_key", normalizedApiKey)
        put("server_url", normalizedUrl)
        normalizedUserId?.let { put("selected_user_id", it) }
    }
    val config = buildMap {
        put("server_url", normalizedUrl)
        normalizedUserId?.let { put("selected_user_id", it) }
    }
    return JellyfinAccountPayload(credentials = credentials, config = config)
}

internal fun restoreJellyfinAccountConnection(
    credentials: Map<String, String>,
    config: Map<String, String>,
): RestoredJellyfinAccountConnection? {
    val apiKey = (
        credentials["api_key"]
            ?: credentials["token"]
            ?: credentials["value"]
            ?: credentials.values.singleOrNull()
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val serverUrl = normalizeJellyfinServerUrl(
        credentials["server_url"] ?: config["server_url"].orEmpty(),
    ) ?: return null
    val selectedUserId = (credentials["selected_user_id"] ?: config["selected_user_id"])
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return RestoredJellyfinAccountConnection(
        serverUrl = serverUrl,
        apiKey = apiKey,
        selectedUserId = selectedUserId,
    )
}

private fun normalizeJellyfinServerUrl(value: String): String? {
    val normalized = value.trim().trimEnd('/')
    return normalized.takeIf {
        it.startsWith("http://", ignoreCase = true) ||
            it.startsWith("https://", ignoreCase = true)
    }
}
