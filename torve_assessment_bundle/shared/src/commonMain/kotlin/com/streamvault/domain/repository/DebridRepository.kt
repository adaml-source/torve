package com.streamvault.domain.repository

import com.streamvault.domain.model.DebridAccount
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.ResolvedStream
import com.streamvault.domain.model.StreamPreferences
import com.streamvault.domain.model.StreamSource

interface DebridRepository {
    suspend fun authenticate(service: DebridServiceType, apiKey: String): DebridAccount
    suspend fun getActiveAccounts(): List<DebridAccount>
    suspend fun removeAccount(service: DebridServiceType)
    suspend fun checkCache(hashes: List<String>, service: DebridServiceType, apiKey: String): Map<String, Boolean>
    suspend fun resolveStream(source: StreamSource, service: DebridServiceType, apiKey: String): ResolvedStream
    suspend fun resolveBestStream(
        sources: List<StreamSource>,
        preferences: StreamPreferences,
    ): ResolvedStream?
}
