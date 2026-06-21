package com.torve.android.error

import android.content.Context
import com.torve.presentation.error.UserFacingError
import com.torve.presentation.error.defaultMessage

/**
 * Resolves a [UserFacingError] to a localized Android string resource.
 * Falls back to the built-in default English message when the resource
 * is not available.
 */
fun UserFacingError.resolve(context: Context): String {
    val resId = context.resources.getIdentifier(messageKey, "string", context.packageName)
    return if (resId != 0) context.getString(resId) else defaultMessage()
}

/**
 * Resolves a [UserFacingError.messageKey] string to its localized message.
 * Use this when the UI state stores the messageKey rather than the enum.
 */
fun resolveErrorKey(context: Context, messageKey: String?): String? {
    if (messageKey == null) return null
    val resId = context.resources.getIdentifier(messageKey, "string", context.packageName)
    return if (resId != 0) {
        context.getString(resId)
    } else {
        com.torve.presentation.error.defaultUserFacingMessages[messageKey]
            ?: com.torve.presentation.error.defaultUserFacingMessages["error_unknown"]
    }
}
