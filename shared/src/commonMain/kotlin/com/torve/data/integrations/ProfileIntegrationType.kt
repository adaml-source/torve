package com.torve.data.integrations

/** The deployed integration API and database both limit identifiers to 50 characters. */
internal fun profileIntegrationType(type: String, profileId: String): String =
    if (profileId == "default" || "::p:" in type || "::profile:" in type) type
    else "$type::p:$profileId".also { require(it.length <= 50) { "Connection identifier is too long" } }

internal fun baseIntegrationType(type: String): String =
    type.substringBefore("::p:").substringBefore("::profile:")

internal fun integrationProfileId(type: String): String = when {
    "::p:" in type -> type.substringAfter("::p:")
    "::profile:" in type -> type.substringAfter("::profile:")
    else -> "default"
}
