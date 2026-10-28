package com.torve.data.channels

/** Provider account failures carry no server address, username or password. */
class XtreamAccountRejected(val inactive: Boolean) : IllegalStateException("Xtream account rejected")

internal fun requireActiveXtreamAccount(account: XtreamAuthInfo) {
    if (account.status.trim().lowercase() in setOf("expired", "disabled", "banned", "inactive")) {
        throw XtreamAccountRejected(inactive = true)
    }
    if (!account.isAuthenticated) throw XtreamAccountRejected(inactive = false)
}
