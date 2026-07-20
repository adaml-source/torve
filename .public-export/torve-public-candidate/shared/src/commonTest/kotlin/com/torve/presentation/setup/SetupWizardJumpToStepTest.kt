package com.torve.presentation.setup

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the new 7B-level contracts that aren't covered by the Prompt 7
 * tests:
 *
 *   1. The coordinator, when validators are registered for ALL four
 *      intents, terminates with each intent in its validator's verdict
 *      — proves the four-intent path coverage the user asked for in
 *      acceptance ("Desktop user can complete debrid-only, IPTV-only,
 *      Plex/Jellyfin-only, Usenet-only").
 *   2. `resetAll()` wipes state AND prefs so a sign-out leaves no
 *      crumbs.
 *   3. Persistence key matches the documented constant — guards
 *      against a refactor accidentally changing the key and silently
 *      dropping every user's saved progress.
 */
class SetupWizardJumpToStepTest {

    @Test
    fun `validating all four intents lands each one in its validator's verdict`() = runTest {
        val verdicts = mapOf(
            SetupIntent.DEBRID to SetupIntentValidation.ready("rd ok"),
            SetupIntent.IPTV to SetupIntentValidation.needsAttention("epg low", "Improve mapping"),
            SetupIntent.PLEX_JELLYFIN to SetupIntentValidation.invalid("rejected", "Re-enter token"),
            SetupIntent.USENET to SetupIntentValidation.ready("3 indexers ready"),
        )
        val coord = newCoordinator(
            validators = verdicts.mapValues { (intent, v) ->
                fakeValidator(intent, v)
            },
        )
        for (intent in SetupIntent.entries) {
            coord.validate(intent).join()
        }
        val state = coord.state.value
        assertEquals(SetupIntentStatus.READY, state[SetupIntent.DEBRID]?.status)
        assertEquals(SetupIntentStatus.NEEDS_ATTENTION, state[SetupIntent.IPTV]?.status)
        assertEquals(SetupIntentStatus.INVALID, state[SetupIntent.PLEX_JELLYFIN]?.status)
        assertEquals(SetupIntentStatus.READY, state[SetupIntent.USENET]?.status)

        val sum = coord.summary.first { it.ready.size == 2 }
        assertTrue(SetupIntent.DEBRID in sum.ready)
        assertTrue(SetupIntent.USENET in sum.ready)
        assertTrue(SetupIntent.IPTV in sum.warnings)
        assertTrue(SetupIntent.PLEX_JELLYFIN in sum.invalid)
    }

    @Test
    fun `resetAll clears state and removes the persisted prefs entry`() = runTest {
        val prefs = FakePrefs()
        val coord = newCoordinator(
            prefs = prefs,
            validators = mapOf(
                SetupIntent.DEBRID to fakeValidator(SetupIntent.DEBRID, SetupIntentValidation.ready("ok")),
            ),
        )
        coord.validate(SetupIntent.DEBRID).join()

        // Sanity: persisted state is non-empty before reset.
        val keyAfterValidate = prefs.store[SetupWizardCoordinator.KEY_PER_INTENT_STATE]
        assertNotNull(keyAfterValidate)
        assertTrue(keyAfterValidate.contains("DEBRID"))

        coord.resetAll()
        assertEquals(emptyMap<SetupIntent, SetupIntentState>(), coord.state.value)
        assertEquals(null, prefs.store[SetupWizardCoordinator.KEY_PER_INTENT_STATE])
    }

    @Test
    fun `persistence key constant is stable`() {
        // Pinning the literal so an accidental rename surfaces in code
        // review — bumping the version implies a migration plan.
        assertEquals(
            "setup_per_intent_state_v1",
            SetupWizardCoordinator.KEY_PER_INTENT_STATE,
        )
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun fakeValidator(
        intent: SetupIntent,
        result: SetupIntentValidation,
    ): IntentValidator = object : IntentValidator {
        override val intent: SetupIntent = intent
        override suspend fun validate(): SetupIntentValidation = result
    }

    private fun newCoordinator(
        prefs: PreferencesRepository = FakePrefs(),
        validators: Map<SetupIntent, IntentValidator>,
        healthRows: kotlinx.coroutines.flow.StateFlow<List<ProviderHealthEntry>> =
            MutableStateFlow(emptyList<ProviderHealthEntry>()).asStateFlow(),
    ): SetupWizardCoordinator {
        val dispatcher = UnconfinedTestDispatcher()
        return SetupWizardCoordinator(
            prefs = prefs,
            validators = validators,
            healthRows = healthRows,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            nowMs = { 1_700_000_000L },
        )
    }

    private class FakePrefs : PreferencesRepository {
        val store = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = store[key]
        override suspend fun setString(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
    }
}
