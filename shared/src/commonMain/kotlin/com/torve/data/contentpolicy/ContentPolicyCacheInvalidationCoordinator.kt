package com.torve.data.contentpolicy

import com.torve.data.mdblist.RatingsEnricher
import com.torve.db.TorveDatabase
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock

class ContentPolicyCacheInvalidationCoordinator(
    private val database: TorveDatabase,
    private val ratingsEnricher: RatingsEnricher,
    private val prefsRepo: PreferencesRepository,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
    private val addonPolicyRepository: AddonPolicyRepository,
) {
    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    suspend fun invalidate(policyStateVersion: String?, force: Boolean = false) {
        val normalizedVersion = policyStateVersion?.ifBlank { null } ?: SIGNED_OUT_VERSION
        val lastInvalidatedVersion = prefsRepo.getString(KEY_LAST_INVALIDATED_POLICY_VERSION)
        if (!force && lastInvalidatedVersion == normalizedVersion) {
            println("[PolicyInvalidate] skip (same version=$normalizedVersion, force=$force)")
            return
        }

        println("[PolicyInvalidate] FIRING version=$normalizedVersion force=$force previous=$lastInvalidatedVersion")
        database.torveQueries.deleteAllMetadataCache()
        ratingsEnricher.clearPersistentCache()
        prefsRepo.remove(KEY_AVAILABILITY_CACHE)
        prefsRepo.setString(KEY_LAST_INVALIDATED_POLICY_VERSION, normalizedVersion)
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
        _events.tryEmit(normalizedVersion)
    }

    suspend fun clearAllPolicyCaches() {
        addonPolicyRepository.clear()
        invalidate(policyStateVersion = SIGNED_OUT_VERSION, force = true)
    }

    private companion object {
        const val KEY_LAST_INVALIDATED_POLICY_VERSION = "content_policy_last_invalidated_state_version"
        const val KEY_AVAILABILITY_CACHE = "availability_cache_v1"
        const val SIGNED_OUT_VERSION = "signed_out"
    }
}
