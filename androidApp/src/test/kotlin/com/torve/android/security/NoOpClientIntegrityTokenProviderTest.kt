package com.torve.android.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoOpClientIntegrityTokenProviderTest {
    @Test
    fun noOpIntegrityProviderFailsSoft() = runBlocking {
        assertEquals("none", NoOpClientIntegrityTokenProvider.providerName)
        assertNull(NoOpClientIntegrityTokenProvider.requestIntegrityToken("nonce"))
    }
}
