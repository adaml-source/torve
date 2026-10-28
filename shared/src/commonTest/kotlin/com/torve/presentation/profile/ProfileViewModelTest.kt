package com.torve.presentation.profile

import com.torve.domain.model.ContentRating
import com.torve.domain.model.UserProfile
import com.torve.domain.repository.ProfileRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileViewModelTest {
    @Test fun deletingAProfileBlocksDuplicateRequestsAndReleasesTheBusyState() = runTest {
        val repository = MemoryProfileRepository(mutableListOf(UserProfile("default", "Default", isActive = true),
            UserProfile("guest", "Guest")))
        val release = CompletableDeferred<Unit>()
        repository.beforeDelete = { release.await() }
        val viewModel = ProfileViewModel(repository, this)
        runCurrent()
        viewModel.requestProfileManagement("guest"); runCurrent()
        viewModel.deleteProfile("guest"); viewModel.deleteProfile("guest"); runCurrent()
        assertEquals(1, repository.deleteCalls)
        assertTrue(viewModel.state.value.isSavingProfile)
        release.complete(Unit); runCurrent()
        assertFalse(viewModel.state.value.isSavingProfile)
        assertEquals(null, viewModel.state.value.editingProfile)
        assertEquals(listOf("default"), repository.getAllProfiles().map { it.id })
        viewModel.deleteProfile("default"); runCurrent()
        assertEquals(1, repository.deleteCalls)
        assertFalse(viewModel.state.value.isSavingProfile)
    }
    @Test fun protectedProfileCannotBeEditedDeletedOrUnlockedWithoutItsPin() = runTest {
        val repository = MemoryProfileRepository(mutableListOf(UserProfile("default", "Default", isActive = true),
            UserProfile("adult", "Adult", pin = "1234")))
        val viewModel = ProfileViewModel(repository, this)
        runCurrent()
        viewModel.setProfilePin("adult", null); viewModel.deleteProfile("adult")
        viewModel.updateProfileName("adult", "Bypassed"); runCurrent()
        assertEquals("1234", repository.getProfile("adult")?.pin)
        assertEquals("Adult", repository.getProfile("adult")?.name)
        viewModel.requestProfileManagement("adult"); runCurrent()
        assertTrue(viewModel.state.value.pinPromptForManagement)
        viewModel.verifyPinAndSwitch("adult", "1234"); runCurrent()
        assertEquals("default", repository.getActiveProfile()?.id)
        viewModel.saveProfile("adult", "Renamed", ContentRating.PG_13, "4321", true); runCurrent()
        assertEquals("Renamed", repository.getProfile("adult")?.name)
        assertEquals("4321", repository.getProfile("adult")?.pin)
        viewModel.setProfilePin("adult", null); runCurrent()
        assertEquals("4321", repository.getProfile("adult")?.pin)
    }
    @Test
    fun pinEntryLocksAfterRepeatedFailuresAndRecoversAfterTimeout() = runTest {
        var now = 1_000L
        val repository = MemoryProfileRepository(
            mutableListOf(
                UserProfile("adult", "Adult", isActive = true),
                UserProfile("child", "Child", pin = "1234", maxContentRating = ContentRating.PG_13),
            ),
        )
        val viewModel = ProfileViewModel(repository, scope = this, nowMs = { now })
        runCurrent()

        viewModel.switchProfile("child")
        runCurrent()
        repeat(ProfileViewModel.MAX_PIN_ATTEMPTS) {
            viewModel.verifyPinAndSwitch("child", "0000")
            runCurrent()
        }
        assertTrue(viewModel.state.value.pinError.orEmpty().contains("Too many"))

        viewModel.verifyPinAndSwitch("child", "1234")
        runCurrent()
        assertEquals("adult", repository.getActiveProfile()?.id)

        now += ProfileViewModel.PIN_LOCKOUT_MS
        viewModel.verifyPinAndSwitch("child", "1234")
        runCurrent()
        assertEquals("child", repository.getActiveProfile()?.id)
    }

    @Test
    fun profileNamesAndPinsAreValidatedBeforePersistence() = runTest {
        val repository = MemoryProfileRepository(mutableListOf(UserProfile("default", "Default", isActive = true)))
        val viewModel = ProfileViewModel(repository, scope = this, nowMs = { 99L })
        runCurrent()

        viewModel.createProfile("   ")
        runCurrent()
        assertEquals(1, repository.getAllProfiles().size)

        viewModel.setProfilePin("default", "12ab")
        runCurrent()
        assertEquals(null, repository.getProfile("default")?.pin)
        assertTrue(viewModel.state.value.error.orEmpty().contains("4 digits"))
    }
}

private class MemoryProfileRepository(
    private val profiles: MutableList<UserProfile>,
) : ProfileRepository {
    var beforeDelete: (suspend () -> Unit)? = null
    var deleteCalls = 0
    override suspend fun getAllProfiles(): List<UserProfile> = profiles.toList()
    override suspend fun getActiveProfile(): UserProfile? = profiles.firstOrNull(UserProfile::isActive)
    override suspend fun getProfile(id: String): UserProfile? = profiles.firstOrNull { it.id == id }
    override suspend fun createProfile(profile: UserProfile) {
        profiles += profile
    }
    override suspend fun setActiveProfile(id: String) {
        profiles.indices.forEach { index ->
            profiles[index] = profiles[index].copy(isActive = profiles[index].id == id)
        }
    }
    override suspend fun updateName(id: String, name: String) = update(id) { it.copy(name = name) }
    override suspend fun updatePin(id: String, pin: String?) = update(id) { it.copy(pin = pin) }
    override suspend fun updateContentRating(id: String, rating: ContentRating?) =
        update(id) { it.copy(maxContentRating = rating) }
    override suspend fun deleteProfile(id: String) {
        deleteCalls++
        beforeDelete?.invoke()
        profiles.removeAll { it.id == id }
    }

    private fun update(id: String, transform: (UserProfile) -> UserProfile) {
        val index = profiles.indexOfFirst { it.id == id }
        if (index >= 0) profiles[index] = transform(profiles[index])
    }
}
