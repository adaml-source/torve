package com.torve.data.auth

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

/** An asynchronous personal operation keeps the identity it started with. */
internal class PersonalDataScope(val account: String, val profile: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PersonalDataScope>
    fun check(ids: UserIdProvider) {
        if (ids.currentUserId() != account || ids.currentProfileId() != profile)
            throw CancellationException("The active account or profile changed")
    }
}

internal suspend fun UserIdProvider.checkPersonalScope() {
    currentCoroutineContext()[PersonalDataScope]?.check(this)
}

internal suspend fun capturePersonalDataScope(ids: UserIdProvider): PersonalDataScope {
    val inherited = currentCoroutineContext()[PersonalDataScope]
    inherited?.check(ids)
    return inherited ?: PersonalDataScope(ids.currentUserId(), ids.currentProfileId())
}

/** Platform workers use the same profile-change guard as shared view models. */
suspend fun <T> UserIdProvider.withPersonalDataScope(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(capturePersonalDataScope(this)) {
        val value = block()
        checkPersonalScope()
        value
    }
