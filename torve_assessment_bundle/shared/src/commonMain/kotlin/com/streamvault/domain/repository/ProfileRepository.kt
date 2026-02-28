package com.streamvault.domain.repository

import com.streamvault.domain.model.ContentRating
import com.streamvault.domain.model.UserProfile

interface ProfileRepository {
    suspend fun getAllProfiles(): List<UserProfile>
    suspend fun getActiveProfile(): UserProfile?
    suspend fun getProfile(id: String): UserProfile?
    suspend fun createProfile(profile: UserProfile)
    suspend fun setActiveProfile(id: String)
    suspend fun updateName(id: String, name: String)
    suspend fun updatePin(id: String, pin: String?)
    suspend fun updateContentRating(id: String, rating: ContentRating?)
    suspend fun deleteProfile(id: String)
}
