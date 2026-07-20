package com.torve.presentation.setup

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the contract that drives the credential-first wizard:
 *   1. Per-intent state is independent — validating DEBRID never touches
 *      IPTV's progress.
 *   2. Validators that return INVALID DO NOT silently land as READY.
 *   3. Persistence: state survives a coordinator restart, with VALIDATING
 *      downgraded to IN_PROGRESS so the spinner never sticks.
 *   4. Ready-to-watch summary respects worse-status provider-health rows
 *      (so the wizard and the health UI never disagree on what's ready).
 */
class SetupWizardCoordinatorTest {

    @Test
    fun validatingOneIntentLeavesTheOthersUntouchedDebridOnlyPath() = runTest {
        val coord = newCoordinator(
            validators = mapOf(
                SetupIntent.DEBRID to fakeValidator(SetupIntent.DEBRID, SetupIntentValidation.ready("ok")),
                // No validator for the other three intents — coordinator
                // shouldn't run them just because we asked DEBRID to.
            ),
        )
        coord.validate(SetupIntent.DEBRID).join()

        val states = coord.state.value
        assertEquals(SetupIntentStatus.READY, states[SetupIntent.DEBRID]?.status)
        // Untouched intents remain absent / NOT_STARTED.
        assertNull(states[SetupIntent.IPTV])
        assertNull(states[SetupIntent.PLEX_JELLYFIN])
        assertNull(states[SetupIntent.USENET])
    }

    @Test
    fun invalidValidatorResultIsPersistedAsInvalidNeverReady() = runTest {
        val coord = newCoordinator(
            validators = mapOf(
                SetupIntent.IPTV to fakeValidator(
                    SetupIntent.IPTV,
                    SetupIntentValidation.invalid("playlist parse failed", "Re-add"),
                ),
            ),
        )
        coord.validate(SetupIntent.IPTV).join()

        val s = coord.state.value[SetupIntent.IPTV]
        assertNotNull(s)
        assertEquals(SetupIntentStatus.INVALID, s.status)
        assertEquals("playlist parse failed", s.message)
        assertEquals("Re-add", s.nextAction)
    }

    @Test
    fun `missing validator surfaces INVALID with an actionable message`() = runTest {
        val coord = newCoordinator(validators = emptyMap())
        coord.validate(SetupIntent.PLEX_JELLYFIN).join()

        val s = coord.state.value[SetupIntent.PLEX_JELLYFIN]
        assertEquals(SetupIntentStatus.INVALID, s?.status)
        assertTrue(s?.message?.contains("No validator") == true)
    }

    @Test
    fun validatorThatThrowsIsWrappedAsInvalidNeverCrashesCoordinator() = runTest {
        val coord = newCoordinator(
            validators = mapOf(
                SetupIntent.USENET to object : IntentValidator {
                    override val intent = SetupIntent.USENET
                    override suspend fun validate(): SetupIntentValidation =
                        throw RuntimeException("boom")
                },
            ),
        )
        coord.validate(SetupIntent.USENET).join()

        val s = coord.state.value[SetupIntent.USENET]
        assertEquals(SetupIntentStatus.INVALID, s?.status)
        assertTrue(s?.message?.contains("boom") == true, "got: ${s?.message}")
    }

    @Test
    fun `beginIntent marks IN_PROGRESS but does not overwrite an already-validated intent`() = runTest {
        val coord = newCoordinator(
            validators = mapOf(
                SetupIntent.DEBRID to fakeValidator(SetupIntent.DEBRID, SetupIntentValidation.ready("ready")),
            ),
        )
        coord.beginIntent(SetupIntent.IPTV)
        // Yield so the launched coroutine completes.
        coord.validate(SetupIntent.DEBRID).join()

        assertEquals(SetupIntentStatus.IN_PROGRESS, coord.state.value[SetupIntent.IPTV]?.status)

        // Now mark DEBRID via beginIntent — should be a no-op since it's READY.
        coord.beginIntent(SetupIntent.DEBRID)
        // Need a moment for the launch to run; query through the flow.
        val finalDebrid = coord.state.first { it[SetupIntent.DEBRID]?.status == SetupIntentStatus.READY }
        assertEquals(SetupIntentStatus.READY, finalDebrid[SetupIntent.DEBRID]?.status)
    }

    @Test
    fun `state persists across a fresh coordinator instance`() = runTest {
        val prefs = FakePrefs()
        val first = newCoordinator(
            prefs = prefs,
            validators = mapOf(
                SetupIntent.DEBRID to fakeValidator(SetupIntent.DEBRID, SetupIntentValidation.ready("rd ok")),
                SetupIntent.IPTV to fakeValidator(
                    SetupIntent.IPTV,
                    SetupIntentValidation.invalid("parse failed", "Re-add"),
                ),
            ),
        )
        first.validate(SetupIntent.DEBRID).join()
        first.validate(SetupIntent.IPTV).join()

        // Build a totally new coordinator backed by the same prefs.
        val second = newCoordinator(prefs = prefs, validators = emptyMap())
        second.load()

        val s = second.state.value
        assertEquals(SetupIntentStatus.READY, s[SetupIntent.DEBRID]?.status)
        assertEquals("rd ok", s[SetupIntent.DEBRID]?.message)
        assertEquals(SetupIntentStatus.INVALID, s[SetupIntent.IPTV]?.status)
        assertEquals("parse failed", s[SetupIntent.IPTV]?.message)
    }

    @Test
    fun `load downgrades VALIDATING to IN_PROGRESS so spinners never stick`() = runTest {
        val prefs = FakePrefs()
        // Simulate an aborted validate: persisted state has VALIDATING.
        prefs.store["setup_per_intent_state_v1"] = """
            {"entries":[
              {"intent":"PLEX_JELLYFIN","status":"VALIDATING","updatedAtMs":42}
            ]}
        """.trimIndent()
        val coord = newCoordinator(prefs = prefs, validators = emptyMap())
        coord.load()

        assertEquals(
            SetupIntentStatus.IN_PROGRESS,
            coord.state.value[SetupIntent.PLEX_JELLYFIN]?.status,
        )
    }

    @Test
    fun `reset removes the per-intent slot back to NOT_STARTED`() = runTest {
        val coord = newCoordinator(
            validators = mapOf(
                SetupIntent.DEBRID to fakeValidator(SetupIntent.DEBRID, SetupIntentValidation.ready("ok")),
            ),
        )
        coord.validate(SetupIntent.DEBRID).join()
        coord.reset(SetupIntent.DEBRID)
        // reset launches a coroutine; await it via flow.
        val cleared = coord.state.first { SetupIntent.DEBRID !in it.keys }
        assertEquals(SetupIntentStatus.NOT_STARTED, coord.snapshot(SetupIntent.DEBRID).status)
        assertTrue(SetupIntent.DEBRID !in cleared.keys)
    }

    @Test
    fun `summary shows canStartWatching once any intent is READY`() = runTest {
        val coord = newCoordinator(
            validators = mapOf(
                SetupIntent.IPTV to fakeValidator(
                    SetupIntent.IPTV,
                    SetupIntentValidation.ready("playlist ok"),
                ),
            ),
        )
        coord.validate(SetupIntent.IPTV).join()

        val sum = coord.summary.first { it.canStartWatching }
        assertEquals(listOf(SetupIntent.IPTV), sum.ready)
        assertTrue(SetupIntent.DEBRID in sum.notStarted)
        assertTrue(SetupIntent.PLEX_JELLYFIN in sum.notStarted)
        assertTrue(SetupIntent.USENET in sum.notStarted)
    }

    @Test
    fun `summary respects worse provider-health row over optimistic per-intent state`() = runTest {
        val healthRows = MutableStateFlow(
            listOf(
                ProviderHealthEntry(
                    category = ProviderHealthCategory.DEBRID,
                    providerKey = "debrid:real_debrid",
                    label = "Real-Debrid",
                    status = ProviderHealthStatus.RED,
                    message = "401 unauthorized",
                ),
            ),
        )
        val coord = newCoordinator(
            healthRows = healthRows.asStateFlow(),
            validators = mapOf(
                SetupIntent.DEBRID to fakeValidator(
                    SetupIntent.DEBRID,
                    SetupIntentValidation.ready("Real-Debrid is connected."),
                ),
            ),
        )
        coord.validate(SetupIntent.DEBRID).join()

        // Wizard says READY but health row says RED — pessimistic merge wins.
        val sum = coord.summary.first { SetupIntent.DEBRID in it.invalid }
        assertTrue(SetupIntent.DEBRID in sum.invalid)
        assertTrue(SetupIntent.DEBRID !in sum.ready)
        assertTrue(sum.repairActions[SetupIntent.DEBRID]?.isNotEmpty() == true)
    }

    @Test
    fun `summary repair actions for NOT_STARTED transferable intents include Transfer`() = runTest {
        val coord = newCoordinator(validators = emptyMap())

        val sum = coord.summary.value
        // DEBRID is transferable per ProviderRepairMapper rules.
        val debridActions = sum.repairActions[SetupIntent.DEBRID].orEmpty()
        assertTrue(debridActions.isNotEmpty(), "expected at least one repair action for unconfigured DEBRID")
    }

    // ── applyAdmissionHints ──────────────────────────────────────

    @Test
    fun `applyAdmissionHints promotes NOT_STARTED IPTV to READY when live path detected`() = runTest {
        val coord = newCoordinator(validators = emptyMap())
        coord.applyAdmissionHints(hasVodPath = false, hasLivePath = true)

        val s = coord.state.value[SetupIntent.IPTV]
        assertEquals(SetupIntentStatus.READY, s?.status)
        assertEquals("Detected from your Torve account.", s?.message)
        // Other intents remain untouched.
        assertNull(coord.state.value[SetupIntent.DEBRID])
    }

    @Test
    fun `applyAdmissionHints promotes NOT_STARTED DEBRID to READY when vod path detected`() = runTest {
        val coord = newCoordinator(validators = emptyMap())
        coord.applyAdmissionHints(hasVodPath = true, hasLivePath = false)

        assertEquals(SetupIntentStatus.READY, coord.state.value[SetupIntent.DEBRID]?.status)
        assertNull(coord.state.value[SetupIntent.IPTV])
    }

    @Test
    fun `applyAdmissionHints does not downgrade an already-validated intent`() = runTest {
        val coord = newCoordinator(
            validators = mapOf(
                SetupIntent.IPTV to fakeValidator(
                    SetupIntent.IPTV,
                    SetupIntentValidation.invalid("parse failed", "Re-add"),
                ),
            ),
        )
        coord.validate(SetupIntent.IPTV).join()
        assertEquals(SetupIntentStatus.INVALID, coord.state.value[SetupIntent.IPTV]?.status)

        // Admission says live path exists, but IPTV is INVALID — must not promote.
        coord.applyAdmissionHints(hasVodPath = false, hasLivePath = true)

        assertEquals(SetupIntentStatus.INVALID, coord.state.value[SetupIntent.IPTV]?.status)
        assertEquals("parse failed", coord.state.value[SetupIntent.IPTV]?.message)
    }

    @Test
    fun `applyAdmissionHints with no paths detected leaves all intents NOT_STARTED`() = runTest {
        val coord = newCoordinator(validators = emptyMap())
        coord.applyAdmissionHints(hasVodPath = false, hasLivePath = false)

        assertEquals(SetupIntentStatus.NOT_STARTED, coord.snapshot(SetupIntent.DEBRID).status)
        assertEquals(SetupIntentStatus.NOT_STARTED, coord.snapshot(SetupIntent.IPTV).status)
    }

    @Test
    fun `applyAdmissionHints hints are not persisted so a fresh coordinator load starts clean`() = runTest {
        val prefs = FakePrefs()
        val coord1 = newCoordinator(prefs = prefs, validators = emptyMap())
        coord1.applyAdmissionHints(hasVodPath = false, hasLivePath = true)
        assertEquals(SetupIntentStatus.READY, coord1.state.value[SetupIntent.IPTV]?.status)

        // A fresh coordinator from the same prefs should NOT have the hint.
        val coord2 = newCoordinator(prefs = prefs, validators = emptyMap())
        coord2.load()
        assertEquals(SetupIntentStatus.NOT_STARTED, coord2.snapshot(SetupIntent.IPTV).status)
    }

    @Test
    fun `hub reflects IPTV as ready and canStartWatching after hints applied`() = runTest {
        val coord = newCoordinator(validators = emptyMap())
        coord.applyAdmissionHints(hasVodPath = false, hasLivePath = true)

        val sum = coord.summary.value
        assertTrue(sum.canStartWatching, "expected canStartWatching after IPTV hint")
        assertTrue(SetupIntent.IPTV in sum.ready)
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
