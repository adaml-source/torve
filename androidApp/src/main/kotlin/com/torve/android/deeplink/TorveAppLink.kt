package com.torve.android.deeplink

import android.net.Uri
import java.util.Locale

enum class TorveAppLinkTarget {
    HOME,
    HELP,
    SETUP,
    ACCOUNT,
}

data class TorveAppLink(
    val target: TorveAppLinkTarget,
    val uri: Uri,
)

object TorveAppLinkParser {
    private const val TORVE_HOST = "torve.app"

    fun parse(uri: Uri?): TorveAppLink? {
        if (uri == null) return null
        if (!uri.isHierarchical) return null
        if (uri.scheme != "https") return null
        if (!uri.host.equals(TORVE_HOST, ignoreCase = true)) return null

        val segments = uri.pathSegments
            .map { it.substringBefore('.').lowercase(Locale.US) }
            .filter { it.isNotBlank() }

        val normalizedSegments = if (segments.firstOrNull() == "app") {
            segments.drop(1)
        } else {
            segments
        }

        val target = when (normalizedSegments.firstOrNull()) {
            null -> TorveAppLinkTarget.HOME
            "help" -> TorveAppLinkTarget.HELP
            "setup" -> TorveAppLinkTarget.SETUP
            "account" -> TorveAppLinkTarget.ACCOUNT
            else -> TorveAppLinkTarget.HOME
        }
        return TorveAppLink(target = target, uri = uri)
    }
}
