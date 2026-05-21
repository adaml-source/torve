package com.torve.data.trakt.api

import com.torve.data.trakt.TraktTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenRefreshLogicTest {

    @Test
    fun executeWithTokenRefresh_retriesOnceAfterUnauthorized() = kotlinx.coroutines.test.runTest {
        var executeCalls = 0
        var refreshCalls = 0

        val result = executeWithTokenRefresh(
            initial = TraktTokens(
                accessToken = "expired",
                refreshToken = "refresh_1",
                expiresIn = 3600,
                createdAt = 0L,
            ),
            execute = { token ->
                executeCalls += 1
                if (token == "expired") error("401 Unauthorized")
                "ok"
            },
            refresh = {
                refreshCalls += 1
                TraktTokens(
                    accessToken = "fresh",
                    refreshToken = "refresh_2",
                    expiresIn = 3600,
                    createdAt = 1L,
                )
            },
            isUnauthorized = { "401" in (it.message ?: "") },
        )

        assertEquals("ok", result)
        assertEquals(2, executeCalls)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun executeWithTokenRefresh_doesNotRefreshForNonUnauthorizedErrors() = kotlinx.coroutines.test.runTest {
        var refreshCalls = 0
        assertFailsWith<IllegalStateException> {
            executeWithTokenRefresh(
                initial = TraktTokens(
                    accessToken = "token",
                    refreshToken = "refresh",
                    expiresIn = 3600,
                    createdAt = 0L,
                ),
                execute = { throw IllegalStateException("network down") },
                refresh = {
                    refreshCalls += 1
                    error("should not refresh")
                },
                isUnauthorized = { false },
            )
        }
        assertEquals(0, refreshCalls)
    }

    @Test
    fun executeWithTokenRefresh_missingTokensRequireTraktConnection() = kotlinx.coroutines.test.runTest {
        assertFailsWith<TraktAuthorizationRequiredException> {
            executeWithTokenRefresh(
                initial = null,
                execute = { "unused" },
                refresh = { error("should not refresh") },
                isUnauthorized = { true },
            )
        }
    }

    @Test
    fun executeWithTokenRefresh_failedRefreshRequiresTraktConnection() = kotlinx.coroutines.test.runTest {
        assertFailsWith<TraktAuthorizationRequiredException> {
            executeWithTokenRefresh(
                initial = TraktTokens(
                    accessToken = "expired",
                    refreshToken = "revoked",
                    expiresIn = 3600,
                    createdAt = 0L,
                ),
                execute = { error("401 Unauthorized") },
                refresh = { error("invalid_grant") },
                isUnauthorized = { "401" in (it.message ?: "") },
            )
        }
    }
}
