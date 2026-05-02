package com.torve.presentation.setup

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Drives the credential-first setup wizard.
 *
 * For each [SetupIntent] the user can take exactly the path they care
 * about — a debrid-only user never sees an IPTV step, a Plex-only user
 * never sees a debrid step. The coordinator persists per-intent progress
 * so a partially-completed setup resumes cleanly across launches.
 *
 * Validation is delegated to [IntentValidator]s registered at construction
 * (typically via DI). The coordinator never touches credential plaintext
 * itself — validators wrap existing clients (`DebridClient.verifyApiKey`,
 * `LibraryOverlayService.testConnection`, projections of
 * `ChannelsUiState` / `PandaSetupUiState`) so the new wizard and the
 * existing provider-health checkers stay aligned by construction.
 *
 * Public surface:
 *   - [state]            — observe per-intent state map.
 *   - [summary]          — derived "Ready to watch" summary.
 *   - [load]             — hydrate from prefs at startup. Idempotent.
 *   - [beginIntent]      — mark an intent IN_PROGRESS without validating.
 *   - [validate]         — run an intent's validator and persist the result.
 *   - [reset]            — back to NOT_STARTED for a single intent.
 *   - [resetAll]         — clear everything (used on sign-out / "start over").
 */
class SetupWizardCoordinator(
    private val prefs: PreferencesRepository,
    private val validators: Map<SetupIntent, IntentValidator>,
    /**
     * Live provider-health rows. Used by the [summary] derivation so the
     * wizard and the provider-health UI never disagree on what's ready.
     * Defaults to an empty hot flow when the caller doesn't have one wired
     * yet (early DI ordering / tests).
     */
    healthRows: StateFlow<List<ProviderHealthEntry>> =
        MutableStateFlow(emptyList<ProviderHealthEntry>()).asStateFlow(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _state = MutableStateFlow<Map<SetupIntent, SetupIntentState>>(emptyMap())
    val state: StateFlow<Map<SetupIntent, SetupIntentState>> = _state.asStateFlow()

    val summary: StateFlow<ReadyToWatchSummary> = combine(_state, healthRows) { st, rows ->
        ReadyToWatchSummary.from(st, rows)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ReadyToWatchSummary.from(emptyMap(), healthRows.value),
    )

    /**
     * Read the persisted state from prefs, downgrading any VALIDATING rows
     * back to IN_PROGRESS — a process death mid-validate must not leave
     * the wizard stuck on a spinner forever.
     */
    suspend fun load() {
        mutex.withLock {
            val raw = runCatching { prefs.getString(KEY_PER_INTENT_STATE) }.getOrNull()
            val persisted: PersistedShape? = if (raw.isNullOrBlank()) {
                null
            } else {
                runCatching { json.decodeFromString(PersistedShape.serializer(), raw) }
                    .getOrNull()
            }
            val sanitized = persisted?.entries?.associate { entry ->
                entry.intent to entry.copy(
                    status = if (entry.status == SetupIntentStatus.VALIDATING) {
                        SetupIntentStatus.IN_PROGRESS
                    } else entry.status,
                )
            }.orEmpty()
            _state.value = sanitized
        }
    }

    /**
     * Seeds READY status in-memory for intents the admission snapshot has
     * already detected — DEBRID when a provider is connected, IPTV when a
     * playlist exists in the local DB. Called immediately after [load] so
     * the hub banner reflects detected paths on first show without requiring
     * the user to open the guided wizard.
     *
     * Contract:
     * - Only promotes NOT_STARTED intents; never downgrades a persisted status.
     * - NOT persisted — hints are re-seeded from live admission state each
     *   launch so they always track the real source-of-truth.
     */
    suspend fun applyAdmissionHints(hasVodPath: Boolean, hasLivePath: Boolean) {
        mutex.withLock {
            val current = _state.value.toMutableMap()
            var changed = false
            if (hasVodPath) {
                val existing = current[SetupIntent.DEBRID]?.status ?: SetupIntentStatus.NOT_STARTED
                if (existing == SetupIntentStatus.NOT_STARTED) {
                    current[SetupIntent.DEBRID] = SetupIntentState(
                        intent = SetupIntent.DEBRID,
                        status = SetupIntentStatus.READY,
                        message = "Detected from your Torve account.",
                        updatedAtMs = nowMs(),
                    )
                    changed = true
                }
            }
            if (hasLivePath) {
                val existing = current[SetupIntent.IPTV]?.status ?: SetupIntentStatus.NOT_STARTED
                if (existing == SetupIntentStatus.NOT_STARTED) {
                    current[SetupIntent.IPTV] = SetupIntentState(
                        intent = SetupIntent.IPTV,
                        status = SetupIntentStatus.READY,
                        message = "Detected from your Torve account.",
                        updatedAtMs = nowMs(),
                    )
                    changed = true
                }
            }
            if (changed) _state.value = current
            // No persist() — hints are re-seeded from admission on every cold
            // start and must not survive coordinator reloads as persisted state.
        }
    }

    /**
     * Mark the intent as IN_PROGRESS without running the validator. Use
     * this when the user opens the path's detail screen — it lets the
     * "Ready to watch" summary distinguish "user is working on this"
     * from "user hasn't visited it yet".
     *
     * No-op if the intent is already past NOT_STARTED — re-entering an
     * already-validated screen shouldn't reset its status.
     */
    fun beginIntent(intent: SetupIntent) {
        scope.launch {
            mutex.withLock {
                val current = _state.value[intent]?.status ?: SetupIntentStatus.NOT_STARTED
                if (current != SetupIntentStatus.NOT_STARTED) return@withLock
                upsert(
                    intent = intent,
                    status = SetupIntentStatus.IN_PROGRESS,
                    message = null,
                    nextAction = null,
                )
            }
        }
    }

    /**
     * Run the validator for [intent] and persist the result.
     *
     * Returns the [Job] so callers can `join` (used by the test suite and
     * by UI code that needs to await the spinner ending). Throws in
     * neither case — a missing validator and a validator that itself
     * throws both produce an INVALID entry with an actionable message.
     */
    fun validate(intent: SetupIntent): Job = scope.launch {
        mutex.withLock {
            upsert(intent, SetupIntentStatus.VALIDATING, message = null, nextAction = null)
        }
        val validator = validators[intent]
        val result = if (validator == null) {
            SetupIntentValidation.invalid(
                message = "No validator registered for ${intent.name}.",
                nextAction = "Contact support",
            )
        } else {
            runCatching { validator.validate() }.getOrElse { t ->
                SetupIntentValidation.invalid(
                    message = "Validation failed: ${t.message ?: t::class.simpleName}",
                    nextAction = "Retry",
                )
            }
        }
        mutex.withLock {
            upsert(
                intent = intent,
                status = result.status,
                message = result.message,
                nextAction = result.nextAction,
            )
        }
    }

    /** Reset a single intent to NOT_STARTED. */
    fun reset(intent: SetupIntent) {
        scope.launch {
            mutex.withLock {
                val next = _state.value.toMutableMap()
                next.remove(intent)
                _state.value = next
                persist(next)
            }
        }
    }

    /** Wipe per-intent progress. Used on sign-out. */
    suspend fun resetAll() {
        mutex.withLock {
            _state.value = emptyMap()
            runCatching { prefs.remove(KEY_PER_INTENT_STATE) }
        }
    }

    /** Snapshot helper for callers that don't want to collect a flow. */
    fun snapshot(intent: SetupIntent): SetupIntentState =
        _state.value[intent] ?: SetupIntentState(intent = intent)

    /** Must be called inside [mutex]. */
    private suspend fun upsert(
        intent: SetupIntent,
        status: SetupIntentStatus,
        message: String?,
        nextAction: String?,
    ) {
        val updated = SetupIntentState(
            intent = intent,
            status = status,
            message = message,
            nextAction = nextAction,
            updatedAtMs = nowMs(),
        )
        val next = _state.value.toMutableMap().apply { this[intent] = updated }
        _state.value = next
        persist(next)
    }

    /** Must be called inside [mutex]. */
    private suspend fun persist(next: Map<SetupIntent, SetupIntentState>) {
        val shape = PersistedShape(entries = next.values.toList())
        val encoded = runCatching { json.encodeToString(PersistedShape.serializer(), shape) }
            .getOrNull() ?: return
        runCatching { prefs.setString(KEY_PER_INTENT_STATE, encoded) }
    }

    @Serializable
    private data class PersistedShape(
        val entries: List<SetupIntentState> = emptyList(),
    )

    companion object {
        const val KEY_PER_INTENT_STATE = "setup_per_intent_state_v1"
    }
}
