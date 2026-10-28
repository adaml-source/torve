package com.torve.data.profile

import com.torve.domain.model.*
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class FamilyCloudSyncTest {
    private class Memory : DeviceLocalSettingsRepository, SecureStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun putString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private class Cloud : FamilyPolicyRemote {
        var policy = FamilyConfiguration(accountId = "owner")
        var revision = 0L
        var pin = ""
        var offline = false
        var rejectUpdate = false
        override suspend fun fetch(): FamilyPolicySnapshot { check(!offline); return FamilyPolicySnapshot(policy, revision) }
        private fun proof() = FamilyParentProof(policy, revision, "grant-$revision", 1_000_000)
        override suspend fun update(configuration: FamilyConfiguration, expectedVersion: Long, pin: String?, grant: String?): FamilyParentProof {
            check(!offline && !rejectUpdate); check(expectedVersion == policy.version) { "stale" }
            if (policy.enabled) check(grant == "grant-$revision") { "Parent required" }
            if (pin != null) { this.pin = pin; revision++ }
            policy = configuration.copy(accountId = "owner", version = policy.version + 1)
            return proof()
        }
        override suspend fun authorize(pin: String): FamilyParentProof { check(!offline && pin == this.pin); return proof() }
        override suspend fun requestRecovery() = FamilyRecoveryDestination("o***@example.test")
        override suspend fun confirmRecovery(code: String, pin: String): FamilyParentProof {
            check(code == "123456");this.pin = pin;revision++;policy = policy.copy(version = policy.version + 1);return proof()
        }
        override suspend fun connections(profile: String, grant: String) = listOf("DEBRID_API_KEY_TORBOX")
        override suspend fun copyConnections(source: String, target: String, services: List<String>, replace: Boolean, grant: String) {}
    }
    private class Device(val scope: CoroutineScope, val cloud: Cloud, val prefs: Memory = Memory(), val secret: Memory = Memory()) {
        var account = "owner"; var profile = "default"
        val controls = FamilyControls(prefs, secret, null, SettingsRefreshNotifier(), lookup = { emptyList() }, remote = cloud,
            scope = scope, now = { 1000 }, accountId = { account }, profileId = { profile })
    }
    @Test fun rulesReachSecondDeviceAndAdultUnlockDoesNotTravel() = runTest {
        val cloud = Cloud(); val a = Device(backgroundScope, cloud); val b = Device(backgroundScope, cloud)
        a.controls.initialize();b.controls.initialize();a.controls.enable("2468", "2468")
        a.controls.saveProfile("child", true, KidsProfileRules(maximumAge = 6, allowedSources = setOf("catalogue"), blockedTitles = setOf("MOVIE:10")))
        b.controls.refreshRemote()
        assertEquals(a.controls.state.value.configuration, b.controls.state.value.configuration)
        assertEquals("true", b.prefs.values["profilepref_v1:child:metadata_tmdb_catalogue_enabled"])
        assertTrue(b.controls.state.value.needsUnlock);assertFalse(b.controls.canManage());assertNull(b.controls.parentGrant())
        b.controls.authorizeProfileSwitch("child");b.profile = "child";b.controls.updateSession()
        assertTrue(b.controls.state.value.isKids);assertFalse(b.controls.state.value.needsUnlock)
        assertFailsWith<IllegalStateException> { b.controls.saveProfile("child", false, KidsProfileRules()) }
    }
    @Test fun switchingToKidsDiscardsServerParentGrant() = runTest {
        val d = Device(backgroundScope, Cloud());d.controls.initialize();d.controls.enable("2468", "2468")
        d.controls.saveProfile("child", true, KidsProfileRules())
        assertNotNull(d.controls.parentGrant());assertTrue(d.controls.state.value.adultUnlocked)
        d.controls.authorizeProfileSwitch("child");d.profile = "child";d.controls.updateSession()
        assertNull(d.controls.parentGrant());assertFalse(d.controls.canManage())
    }
    @Test fun staleEditRefreshesPolicyAndDoesNotUndoOtherDevice() = runTest {
        val cloud=Cloud();val a=Device(backgroundScope,cloud);val b=Device(backgroundScope,cloud)
        a.controls.initialize();a.controls.enable("2468","2468");b.controls.initialize();b.controls.authorize("2468")
        a.controls.saveProfile("child",true,KidsProfileRules(maximumAge=6))
        assertFailsWith<IllegalStateException> { b.controls.saveProfile("child",true,KidsProfileRules(maximumAge=16)) }
        assertEquals(6,b.controls.state.value.configuration.kids.getValue("child").maximumAge)
        assertEquals(6,cloud.policy.kids.getValue("child").maximumAge)
        assertEquals("false", b.prefs.values["profilepref_v1:child:metadata_tmdb_catalogue_enabled"])
    }
    @Test fun offlineColdStartKeepsRestrictionsAndRejectsParentMutations() = runTest {
        val cloud=Cloud();val a=Device(backgroundScope,cloud);a.controls.initialize();a.controls.enable("2468","2468")
        a.controls.saveProfile("child",true,KidsProfileRules())
        cloud.offline=true
        val restarted=Device(backgroundScope,cloud,a.prefs,a.secret);restarted.profile="child";restarted.controls.initialize()
        assertTrue(restarted.controls.state.value.isKids);assertFalse(restarted.controls.canManage());assertNotNull(restarted.controls.state.value.syncError)
        assertFailsWith<IllegalStateException> { restarted.controls.authorize("2468") }
        assertFalse(restarted.secret.values.containsKey("family_parent_pin_v1"))
    }
    @Test fun newOfflineDeviceFailsClosedAndRecoveryRelocksOtherDevice() = runTest {
        val cloud=Cloud();cloud.offline=true;val offline=Device(backgroundScope,cloud);offline.controls.initialize()
        assertTrue(offline.controls.state.value.needsUnlock)
        cloud.offline=false
        val a=Device(backgroundScope,cloud);val b=Device(backgroundScope,cloud)
        a.controls.initialize();a.controls.enable("2468","2468");b.controls.initialize();b.controls.authorize("2468")
        assertTrue(b.controls.state.value.adultUnlocked)
        a.controls.recoverWithEmailCode("123456","5678","5678");b.controls.refreshRemote()
        assertTrue(b.controls.state.value.needsUnlock);assertNull(b.controls.parentGrant())
    }
    @Test fun failedLegacyMigrationStaysRestrictedAndRetriesWithoutTurningControlsOff() = runTest {
        val cloud = Cloud(); cloud.rejectUpdate = true
        val prefs = Memory(); val secret = Memory()
        prefs.values["family_device_configuration_v1"] = "{\"enabled\":true,\"accountId\":\"owner\",\"kids\":{\"child\":{\"maximumAge\":6}},\"version\":100}"
        secret.values["family_parent_pin_v1"] = "2468"
        val d = Device(backgroundScope,cloud,prefs,secret);d.profile="child";d.controls.initialize()
        assertTrue(d.controls.state.value.isKids);assertFalse(d.controls.canManage());assertFalse(cloud.policy.enabled)
        cloud.rejectUpdate=false;d.controls.refreshRemote()
        assertTrue(cloud.policy.enabled);assertEquals(6,cloud.policy.kids.getValue("child").maximumAge)
        assertNull(secret.values["family_parent_pin_v1"]);assertTrue(d.controls.state.value.isKids)
    }
    @Test fun accountChangeNeverCarriesPolicyOrGrantIntoAnotherAccount() = runTest {
        val d=Device(backgroundScope,Cloud());d.controls.initialize();d.controls.enable("2468","2468")
        d.account="other";d.controls.updateSession()
        assertFalse(d.controls.state.value.loaded);assertNull(d.controls.parentGrant());assertFalse(d.controls.canManage())
    }
}
