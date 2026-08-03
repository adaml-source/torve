package com.torve.presentation.profile

import com.torve.domain.model.ContentRating
import com.torve.domain.model.UserProfile
import com.torve.domain.repository.ProfileRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileViewModelTest {
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
        profiles.removeAll { it.id == id }
    }

    private fun update(id: String, transform: (UserProfile) -> UserProfile) {
        val index = profiles.indexOfFirst { it.id == id }
        if (index >= 0) profiles[index] = transform(profiles[index])
    }
}
