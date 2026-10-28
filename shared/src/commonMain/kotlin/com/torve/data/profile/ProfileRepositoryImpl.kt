package com.torve.data.profile

import com.torve.data.auth.UserIdProvider
import com.torve.db.TorveDatabase
import com.torve.domain.model.ContentRating
import com.torve.domain.model.UserProfile
import com.torve.domain.repository.ProfileRepository

class ProfileRepositoryImpl(
    private val database: TorveDatabase,
    private val userIdProvider: UserIdProvider,
    private val notifier: com.torve.presentation.settings.SettingsRefreshNotifier? = null,
    private val syncProvider: (() -> HouseholdSyncService)? = null,
    private val familyProvider: (() -> FamilyControls)? = null,
    private val restoreConnections: suspend () -> Unit = {},
) : ProfileRepository {

    override suspend fun getAllProfiles(): List<UserProfile> {
        return database.torveQueries.getAllProfiles(userId = userIdProvider.currentUserId())
            .executeAsList().map { it.toDomain() }
    }

    override suspend fun getActiveProfile(): UserProfile? {
        return database.torveQueries.getActiveProfile(userId = userIdProvider.currentUserId())
            .executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getProfile(id: String): UserProfile? {
        return database.torveQueries.getProfile(
            userId = userIdProvider.currentUserId(),
            profileId = id,
        ).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun createProfile(profile: UserProfile) {
        familyProvider?.invoke()?.initialize()
        familyProvider?.invoke()?.requireParent()
        database.torveQueries.insertProfile(
            user_id = userIdProvider.currentUserId(),
            id = profile.id,
            name = profile.name,
            avatar_index = profile.avatarIndex.toLong(),
            is_active = if (profile.isActive) 1L else 0L,
            pin = profile.pin,
            max_content_rating = profile.maxContentRating?.name,
            created_at = profile.createdAt,
        )
    }

    override suspend fun setActiveProfile(id: String) {
        val previous = userIdProvider.currentProfileId()
        familyProvider?.invoke()?.initialize()
        familyProvider?.invoke()?.authorizeProfileSwitch(id)
        require(getProfile(id) != null) { "Profile is no longer available" }
        database.torveQueries.setActiveProfile(
            profileId = id,
            userId = userIdProvider.currentUserId(),
        )
        familyProvider?.invoke()?.updateSession()
        notifier?.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
        syncProvider?.invoke()?.requestRefresh(force = true)
        if (previous != id) {
            val owner = com.torve.data.auth.PersonalDataScope(userIdProvider.currentUserId(), id)
            kotlinx.coroutines.withContext(owner) { restoreConnections() }
        }
    }

    override suspend fun updateName(id: String, name: String) {
        familyProvider?.invoke()?.initialize()
        familyProvider?.invoke()?.requireParent()
        database.torveQueries.updateProfileName(
            name = name,
            userId = userIdProvider.currentUserId(),
            profileId = id,
        )
    }

    override suspend fun updatePin(id: String, pin: String?) {
        familyProvider?.invoke()?.initialize()
        familyProvider?.invoke()?.requireParent()
        database.torveQueries.updateProfilePin(
            pin = pin,
            userId = userIdProvider.currentUserId(),
            profileId = id,
        )
        syncProvider?.invoke()?.queuePinUpdate(id)
    }

    override suspend fun updateContentRating(id: String, rating: ContentRating?) {
        familyProvider?.invoke()?.initialize()
        familyProvider?.invoke()?.requireParent()
        database.torveQueries.updateProfileRating(
            max_content_rating = rating?.name,
            userId = userIdProvider.currentUserId(),
            profileId = id,
        )
        familyProvider?.invoke()?.updateSession()
        notifier?.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun deleteProfile(id: String) {
        familyProvider?.invoke()?.initialize()
        familyProvider?.invoke()?.requireParent()
        require(id != "default") { "The primary profile cannot be deleted" }
        database.torveQueries.deleteProfile(
            userId = userIdProvider.currentUserId(),
            profileId = id,
        )
    }

    private fun com.torve.db.User_profile.toDomain(): UserProfile {
        return UserProfile(
            id = id,
            name = name,
            avatarIndex = avatar_index.toInt(),
            isActive = is_active == 1L,
            pin = pin,
            maxContentRating = ContentRating.fromString(max_content_rating),
            createdAt = created_at,
        )
    }
}
