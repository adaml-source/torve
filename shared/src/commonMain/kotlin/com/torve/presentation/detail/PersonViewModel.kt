package com.torve.presentation.detail

import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.metadata.TmdbMappers
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class PersonUiState(
    val isLoading: Boolean = true,
    val personName: String = "",
    val profileUrl: String? = null,
    val biography: String = "",
    val knownFor: String = "",
    val credits: List<MediaItem> = emptyList(),
    val error: String? = null,
)

class PersonViewModel(
    private val metadataRepo: MetadataRepository,
    private val ratingsEnricher: RatingsEnricher? = null,
    private val prefsRepo: PreferencesRepository? = null,
    private val integrationSecretStore: IntegrationSecretStore? = null,
    private val family: com.torve.data.profile.FamilyControls? = null,
) {
    private var generation = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = family?.let { com.torve.data.profile.ProfilePolicyStateFlow(it, PersonUiState()) } ?: MutableStateFlow(PersonUiState())
    val state: StateFlow<PersonUiState> = _state.asStateFlow()

    fun close() { generation++; scope.cancel() }

    fun loadPerson(personId: Int) {
        val request = ++generation
        scope.launch {
            _state.value = PersonUiState()
            try {
                val person = metadataRepo.getPersonDetail(personId)
                val credits = metadataRepo.getPersonCredits(personId)
                val hydratedCredits = ratingsEnricher?.hydrateListFromCache(credits) ?: credits
                if (request != generation) return@launch
                _state.update {
                    it.copy(
                        isLoading = false,
                        personName = person.name,
                        profileUrl = TmdbMappers.profileUrl(person.profilePath),
                        biography = person.biography,
                        knownFor = person.knownForDepartment ?: "",
                        credits = hydratedCredits,
                    )
                }
                enrichCredits(credits, request)
            } catch (e: Exception) {
                if (request != generation) return@launch
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.CONTENT_LOAD_FAILED.messageKey) }
            }
        }
    }

    private fun enrichCredits(credits: List<MediaItem>, request: Int) {
        val enricher = ratingsEnricher ?: return
        if (credits.isEmpty()) return
        scope.launch {
            val apiKey = try {
                integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo?.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) {
                MdbListApi.DEFAULT_API_KEY
            }
            for (batch in credits.chunked(12)) {
                if (request != generation) return@launch
                val enriched = try { enricher.enrichList(batch, apiKey) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { continue }
                if (request != generation) return@launch
                _state.update { state -> state.copy(credits = mergeCreditRatings(state.credits, enriched)) }
            }
        }
    }

    private fun mergeCreditRatings(
        current: List<MediaItem>,
        enriched: List<MediaItem>,
    ): List<MediaItem> {
        val byStableId = enriched.associateBy { "${it.type.name}:${it.tmdbId ?: it.id}" }
        return current.map { item ->
            byStableId["${item.type.name}:${item.tmdbId ?: item.id}"]?.copy(creditCharacter = item.creditCharacter, creditJob = item.creditJob, creditIsCrew = item.creditIsCrew, genreIds = item.genreIds) ?: item
        }
    }
}
