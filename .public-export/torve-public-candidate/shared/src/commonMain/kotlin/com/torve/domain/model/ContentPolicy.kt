package com.torve.domain.model

import kotlinx.serialization.Serializable

enum class ContentAgeBand {
    UNKNOWN,
    UNDER_18,
    ADULT;

    companion object {
        fun fromBackend(value: String?): ContentAgeBand = when (value?.trim()?.uppercase()) {
            "ADULT", "OVER_18", "18_PLUS" -> ADULT
            "UNDER_18", "MINOR" -> UNDER_18
            else -> UNKNOWN
        }
    }
}

enum class ContentAccessContext {
    DEFAULT_DISCOVERY,
    GLOBAL_RECOMMENDATION,
    DIRECT_SEARCH,
    SEARCH_SUGGESTION,
    DETAIL_PAGE,
    SIMILAR_OR_MORE_LIKE_THIS,
    HISTORY_DERIVED,
    LIBRARY_OR_WATCHLIST,
    ADDON_SHELF,
    ACCELERATION_OR_INVENTORY,
}

enum class ContentFilterAction {
    ALLOW_FULL,
    ALLOW_PLACEHOLDER,
    HIDE,
    STUB_DETAIL,
}

enum class ContentSourceType {
    TMDB,
    TRAKT,
    MDBLIST,
    ADDON,
    LOCAL_LIBRARY,
    BACKEND_ACCELERATION,
}

enum class SensitiveClassification {
    SAFE,
    SENSITIVE,
    UNKNOWN,
}

@Serializable
data class AddonPolicyFlags(
    val installable: Boolean = true,
    val shelfEligible: Boolean = true,
    val catalogQueryable: Boolean = true,
)

data class ContentPolicyState(
    val enforcementEnabled: Boolean = false,
    val isSignedIn: Boolean = false,
    val isLoading: Boolean = false,
    val ageBand: ContentAgeBand = ContentAgeBand.UNKNOWN,
    val adultEligible: Boolean = false,
    val sensitiveMaterialEnabled: Boolean = false,
    val sensitiveMaterialPolicyVersion: String? = null,
    val currentPolicyVersion: String? = null,
    val policyStateVersion: String? = null,
    val lastError: String? = null,
) {
    val isLocked: Boolean
        get() = enforcementEnabled && (!isSignedIn || ageBand != ContentAgeBand.ADULT || !adultEligible || !sensitiveMaterialEnabled)

    val adultEnabled: Boolean
        get() = enforcementEnabled && isSignedIn && ageBand == ContentAgeBand.ADULT && adultEligible && sensitiveMaterialEnabled

    companion object {
        fun unrestricted(): ContentPolicyState = ContentPolicyState(enforcementEnabled = false)

        fun lockedBootstrap(
            enforcementEnabled: Boolean,
            isSignedIn: Boolean = false,
        ): ContentPolicyState = ContentPolicyState(
            enforcementEnabled = enforcementEnabled,
            isSignedIn = isSignedIn,
            isLoading = enforcementEnabled && isSignedIn,
            ageBand = ContentAgeBand.UNKNOWN,
            adultEligible = false,
            sensitiveMaterialEnabled = false,
        )
    }
}

data class ContentFilterDecision(
    val action: ContentFilterAction,
    val classification: SensitiveClassification,
    val reason: String? = null,
)

const val LOCKED_CONTENT_TITLE = "Sensitive content hidden"
const val LOCKED_CONTENT_MESSAGE = "This item is hidden by your content setting."
const val LOCKED_SEARCH_MESSAGE = "Some results are hidden by your content setting."
const val STUB_DETAIL_TITLE = "Content not available"
const val STUB_DETAIL_MESSAGE = "This item is not available with your current content setting."
