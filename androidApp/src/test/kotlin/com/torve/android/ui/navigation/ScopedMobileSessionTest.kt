package com.torve.android.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class ScopedMobileSessionTest {
    @Test fun playerReturnKeepsTheSameSession() {
        var created = 0
        val session = ScopedMobileSession({ ++created }, {})
        assertEquals(session.get("account/profile"), session.get("account/profile"))
        assertEquals(1, created)
    }
    @Test fun profileSwitchReleasesPreviousSessionBeforeCreatingAnother() {
        val events = mutableListOf<String>()
        val session = ScopedMobileSession({ events += "create"; Any() }, { events += "release" })
        val first = session.get("profile-a")
        assertNotSame(first, session.get("profile-b"))
        assertEquals(listOf("create", "release", "create"), events)
    }
    @Test fun signOutDoesNotRetainAccountResults() {
        var released = 0
        val session = ScopedMobileSession({ Any() }, { released++ })
        val account = session.get("account")
        assertNotSame(account, session.get("signed-out"))
        assertEquals(1, released)
    }
    @Test fun clearingIsIdempotentAndReopeningCreatesAFreshSession() {
        var released = 0
        val session = ScopedMobileSession({ Any() }, { released++ })
        val first = session.get("owner")
        session.clear(); session.clear()
        assertEquals(1, released)
        assertNotSame(first, session.get("owner"))
    }
}
