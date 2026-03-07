package com.torve.presentation.detail

import com.torve.data.metadata.TmdbMappers
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PersonUiState())
    val state: StateFlow<PersonUiState> = _state.asStateFlow()

    fun loadPerson(personId: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val person = metadataRepo.getPersonDetail(personId)
                val credits = metadataRepo.getPersonCredits(personId)
                _state.update {
                    it.copy(
                        isLoading = false,
                        personName = person.name,
                        profileUrl = TmdbMappers.profileUrl(person.profilePath),
                        biography = person.biography,
                        knownFor = person.knownForDepartment ?: "",
                        credits = credits,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }
}
