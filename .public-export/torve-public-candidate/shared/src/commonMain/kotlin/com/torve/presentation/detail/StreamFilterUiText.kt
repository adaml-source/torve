package com.torve.presentation.detail

object StreamFilterUiText {
    const val ALL_HIDDEN_MESSAGE = "All streams were hidden by your filters."
    const val ADJUST_REGEX_HINT = "Adjust Regex Patterns in Settings to see more results."
    const val MANAGE_FILTERS_ON_MOBILE_OR_DESKTOP_HINT = "Manage filters on mobile or desktop."

    fun hiddenCountMessage(hiddenCount: Int): String? {
        if (hiddenCount <= 0) return null
        val noun = if (hiddenCount == 1) "stream" else "streams"
        return "$hiddenCount $noun hidden by filters"
    }

    fun hiddenCountMessage(hiddenCount: Int, premiumFeedbackEnabled: Boolean): String? =
        if (premiumFeedbackEnabled) hiddenCountMessage(hiddenCount) else null

    fun allHiddenMessage(visibleCount: Int, hiddenCount: Int): String? =
        if (visibleCount == 0 && hiddenCount > 0) ALL_HIDDEN_MESSAGE else null

    fun allHiddenHint(visibleCount: Int, hiddenCount: Int): String? =
        if (visibleCount == 0 && hiddenCount > 0) ADJUST_REGEX_HINT else null

    fun visibleErrorMessage(error: String?, premiumFeedbackEnabled: Boolean): String? = when {
        error == null -> null
        premiumFeedbackEnabled -> error
        error == ALL_HIDDEN_MESSAGE -> "No streams found"
        else -> error
    }

    fun visibleHint(hint: String?, premiumFeedbackEnabled: Boolean): String? =
        hint?.takeIf { premiumFeedbackEnabled }
}
