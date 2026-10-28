package com.torve.data.addon

/** Set by each host before DI, identically in Debug and Release. */
object ExtensionRuntimePolicy {
    /** Apple hosts require a manifest installed on that device, never an Android/account restore. */
    var restorePandaFromAccount: Boolean = true
    /** Android upgrades keep manifests explicitly installed by the previous app on this device. */
    var preserveLegacyLocalAppInstalls: Boolean = false
    /** User-installed manifest URLs are used exactly as supplied, without injecting credentials. */
    var userManagedOnly: Boolean = false
    /** Hosts opt in before initializing DI. Other platforms retain their existing catalogue. */
    var optionalMetadataExtensions: Boolean = false
}

internal const val DEVICE_MANIFEST_INSTALL = "device"

internal fun isPandaExtension(id: String?, manifestUrl: String): Boolean =
    id.equals("com.torve.panda", ignoreCase = true) || runCatching {
        io.ktor.http.Url(manifestUrl).host.equals("panda.torve.app", ignoreCase = true)
    }.getOrDefault(false)

internal fun permitsLocalExtension(id: String?, manifestUrl: String, installedFrom: String): Boolean =
    ExtensionRuntimePolicy.restorePandaFromAccount || !isPandaExtension(id, manifestUrl) ||
        installedFrom == DEVICE_MANIFEST_INSTALL ||
        (ExtensionRuntimePolicy.preserveLegacyLocalAppInstalls && installedFrom == "app")
