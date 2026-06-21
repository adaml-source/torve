package com.torve.domain.repository

import com.torve.domain.model.DebridAccount
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamSource

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
