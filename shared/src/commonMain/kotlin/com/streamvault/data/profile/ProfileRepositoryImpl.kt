package com.streamvault.data.profile

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.ContentRating
import com.streamvault.domain.model.UserProfile
import com.streamvault.domain.repository.ProfileRepository

class ProfileRepositoryImpl(
    private val database: StreamVaultDatabase,
) : ProfileRepository {

    override suspend fun getAllProfiles(): List<UserProfile> {
        return database.streamVaultQueries.getAllProfiles().executeAsList().map { it.toDomain() }
    }

    override suspend fun getActiveProfile(): UserProfile? {
        return database.streamVaultQueries.getActiveProfile().executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getProfile(id: String): UserProfile? {
        return database.streamVaultQueries.getProfile(id).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun createProfile(profile: UserProfile) {
        database.streamVaultQueries.insertProfile(
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
        database.streamVaultQueries.setActiveProfile(id)
    }

    override suspend fun updateName(id: String, name: String) {
        database.streamVaultQueries.updateProfileName(name, id)
    }

    override suspend fun updatePin(id: String, pin: String?) {
        database.streamVaultQueries.updateProfilePin(pin, id)
    }

    override suspend fun updateContentRating(id: String, rating: ContentRating?) {
        database.streamVaultQueries.updateProfileRating(rating?.name, id)
    }

    override suspend fun deleteProfile(id: String) {
        database.streamVaultQueries.deleteProfile(id)
    }

    private fun com.streamvault.db.User_profile.toDomain(): UserProfile {
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
