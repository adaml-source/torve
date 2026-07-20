package com.torve.domain.transfer

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretsTransferApplierTest {

    // ── Fakes ──────────────────────────────────────────────────────

    private class FakePrefs : PreferencesRepository {
        val store = mutableMapOf<String, String>()
        var failSetOn: ((String) -> Boolean)? = null
        var failGetOn: ((String) -> Boolean)? = null
        var setCount: Int = 0
        var removeCount: Int = 0
        override suspend fun getString(key: String): String? {
            if (failGetOn?.invoke(key) == true) throw RuntimeException("simulated prefs read fail: $key")
            return store[key]
        }
        override suspend fun setString(key: String, value: String) {
            setCount += 1
            if (failSetOn?.invoke(key) == true) throw RuntimeException("simulated prefs write fail: $key")
            store[key] = value
        }
        override suspend fun remove(key: String) {
            removeCount += 1
            store.remove(key)
        }
    }

    /**
     * Programmable secret store. `failPutOn` and `failRemoveOn` let a
     * test point at a specific address and trip a failure there.
     */
    private class FakeSecretStore : IntegrationSecretStore {
        private val backing = mutableMapOf<String, String>()
        var putCount: Int = 0
        var removeCount: Int = 0
        var failPutOn: ((IntegrationSecretKey, String?) -> Boolean)? = null
        var failRemoveOn: ((IntegrationSecretKey, String?) -> Boolean)? = null
        var failGetOn: ((IntegrationSecretKey, String?) -> Boolean)? = null

        private fun addr(key: IntegrationSecretKey, subKey: String?): String =
            "${key.name}|${subKey.orEmpty()}"

        fun seed(key: IntegrationSecretKey, value: String, subKey: String? = null) {
            backing[addr(key, subKey)] = value
        }

        fun snapshot(): Map<String, String> = backing.toMap()

        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
            putCount += 1
            if (failPutOn?.invoke(key, subKey) == true) throw RuntimeException("simulated put failure for $key")
            backing[addr(key, subKey)] = value
        }

        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? {
            if (failGetOn?.invoke(key, subKey) == true) throw RuntimeException("simulated get failure for $key")
            return backing[addr(key, subKey)]
        }

        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
            removeCount += 1
            if (failRemoveOn?.invoke(key, subKey) == true) throw RuntimeException("simulated remove failure for $key")
            backing.remove(addr(key, subKey))
        }

        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) {}
        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
            IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() { backing.clear() }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun nonceStore(now: () -> Long = { 1L }): ConsumedNonceStore =
        ConsumedNonceStore(prefs = FakePrefs(), nowMs = now)

    private fun payload(
        nonce: String,
        secrets: List<SecretRecord>,
    ): SecretsTransferPayload = SecretsTransferPayload(
        senderDeviceName = "test",
        createdAtEpochMs = 1L,
        expiresAtEpochMs = 100L,
        transferNonce = nonce,
        categories = secrets.map { it.category }.distinct(),
        secrets = secrets,
    )

    private val rdSecret = SecretRecord(
        SecretCategory.DEBRID, "DEBRID_API_KEY_REAL_DEBRID", "rd_real",
    )
    private val plexSecret = SecretRecord(
        SecretCategory.PLEX_JELLYFIN, "PLEX_ACCESS_TOKEN", "plex_token",
    )
    private val pandaIndexerSecret = SecretRecord(
        SecretCategory.PANDA, "PANDA_INDEXER_API_KEY", "scenenzbs_key",
        subKey = "scenenzbs|https://scenenzbs.com",
    )

    // ── Tests ─────────────────────────────────────────────────────

    @Test
    fun `success writes every record and marks nonce consumed`() = runTest {
        val secrets = FakeSecretStore()
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, FakePrefs())
        val out = applier.apply(payload("n-1", listOf(rdSecret, plexSecret, pandaIndexerSecret)))
        val ok = assertIs<TransferApplyResult.Success>(out)
        assertEquals(3, ok.applied)
        assertTrue(ok.skippedKeyNames.isEmpty())
        assertEquals("rd_real", secrets.get(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID))
        assertEquals("plex_token", secrets.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN))
        assertEquals(
            "scenenzbs_key",
            secrets.get(IntegrationSecretKey.PANDA_INDEXER_API_KEY, "scenenzbs|https://scenenzbs.com"),
        )
        assertTrue(nonces.isConsumed("n-1"))
    }

    @Test
    fun `unknown key names are skipped without writing or marking the nonce consumed`() = runTest {
        val secrets = FakeSecretStore()
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, FakePrefs())
        val out = applier.apply(
            payload(
                "n-skip",
                listOf(
                    SecretRecord(SecretCategory.AI_KEYS, "TOTALLY_NOT_A_REAL_KEY", "x"),
                    SecretRecord(SecretCategory.AI_KEYS, "ANOTHER_INVALID_KEY", "y"),
                ),
            ),
        )
        val nothing = assertIs<TransferApplyResult.NothingApplied>(out)
        assertContentEquals(listOf("TOTALLY_NOT_A_REAL_KEY", "ANOTHER_INVALID_KEY"), nothing.skippedKeyNames)
        assertTrue(secrets.snapshot().isEmpty())
        // Nonce NOT marked — a future build with the right enum can retry.
        assertFalse(nonces.isConsumed("n-skip"))
    }

    @Test
    fun `success with mix of valid and invalid keys writes only the valid ones`() = runTest {
        val secrets = FakeSecretStore()
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, FakePrefs())
        val out = applier.apply(
            payload(
                "n-mix",
                listOf(
                    rdSecret,
                    SecretRecord(SecretCategory.AI_KEYS, "BOGUS_KEY", "x"),
                ),
            ),
        )
        val ok = assertIs<TransferApplyResult.Success>(out)
        assertEquals(1, ok.applied)
        assertContentEquals(listOf("BOGUS_KEY"), ok.skippedKeyNames)
    }

    @Test
    fun `duplicate nonce is rejected and writes nothing`() = runTest {
        val secrets = FakeSecretStore()
        val nonces = nonceStore()
        nonces.markConsumed("n-dup")
        val applier = SecretsTransferApplier(secrets, nonces, FakePrefs())
        val out = applier.apply(payload("n-dup", listOf(rdSecret)))
        assertEquals(TransferApplyResult.DuplicateNonce, out)
        assertTrue(secrets.snapshot().isEmpty())
    }

    @Test
    fun `mid-write failure rolls back previously-written secrets to pre-images`() = runTest {
        val secrets = FakeSecretStore()
        // Pre-existing values: pre-image of RD is "old_rd", PLEX is null
        // (no pre-image), PANDA is "old_panda".
        secrets.seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "old_rd")
        secrets.seed(IntegrationSecretKey.PANDA_INDEXER_API_KEY, "old_panda", "scenenzbs|https://scenenzbs.com")
        // Trip a failure on the third put (PANDA_INDEXER_API_KEY).
        secrets.failPutOn = { key, _ -> key == IntegrationSecretKey.PANDA_INDEXER_API_KEY }
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, FakePrefs())
        val out = applier.apply(payload("n-rollback", listOf(rdSecret, plexSecret, pandaIndexerSecret)))
        val fail = assertIs<TransferApplyResult.StoreFailure>(out)
        assertTrue(fail.rollbackAttempted)
        assertTrue(fail.rollbackSucceeded)
        assertEquals(0, fail.applied)
        // RD restored to its pre-image.
        assertEquals("old_rd", secrets.get(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID))
        // PLEX never had a pre-image → must be removed.
        assertNull(secrets.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN))
        // PANDA was the failing one — its pre-image is preserved (no
        // successful write happened to overwrite it).
        assertEquals(
            "old_panda",
            secrets.get(IntegrationSecretKey.PANDA_INDEXER_API_KEY, "scenenzbs|https://scenenzbs.com"),
        )
        // Nonce NOT marked — caller can retry once the failure clears.
        assertFalse(nonces.isConsumed("n-rollback"))
    }

    @Test
    fun `restore failure during rollback reports rollbackSucceeded = false`() = runTest {
        val secrets = FakeSecretStore()
        // Set up the same pattern as above…
        secrets.seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "old_rd")
        secrets.failPutOn = { key, _ ->
            // Fail on the second top-level write (PLEX), AND fail on the
            // rollback put for RD. The applier should report
            // rollbackAttempted=true / rollbackSucceeded=false.
            key == IntegrationSecretKey.PLEX_ACCESS_TOKEN ||
                (key == IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID && secrets.putCount > 2)
        }
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, FakePrefs())
        val out = applier.apply(payload("n-rollback-fail", listOf(rdSecret, plexSecret)))
        val fail = assertIs<TransferApplyResult.StoreFailure>(out)
        assertTrue(fail.rollbackAttempted)
        assertFalse(fail.rollbackSucceeded)
        assertFalse(nonces.isConsumed("n-rollback-fail"))
    }

    @Test
    fun `pre-image read failure aborts before any write`() = runTest {
        val secrets = FakeSecretStore()
        secrets.failGetOn = { key, _ -> key == IntegrationSecretKey.PLEX_ACCESS_TOKEN }
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, FakePrefs())
        val out = applier.apply(payload("n-snapshot-fail", listOf(rdSecret, plexSecret)))
        val fail = assertIs<TransferApplyResult.StoreFailure>(out)
        assertFalse(fail.rollbackAttempted)
        assertEquals(0, secrets.putCount, "no write should have happened")
        assertFalse(nonces.isConsumed("n-snapshot-fail"))
    }

    // ── ConsumedNonceStore retention/pruning ─────────────────────

    @Test
    fun `nonce store rejects within retention window and prunes after`() = runTest {
        val prefs = FakePrefs()
        val now = arrayOf(1_000_000L)
        val retentionMs = 1_000L
        val store = ConsumedNonceStore(prefs = prefs, nowMs = { now[0] }, retentionMs = retentionMs)
        store.markConsumed("n-keep")
        // Within the window → still consumed.
        now[0] += retentionMs - 1
        assertTrue(store.isConsumed("n-keep"))
        // Past the window → pruned out.
        now[0] += 2
        assertFalse(store.isConsumed("n-keep"))
    }

    @Test
    fun `nonce store survives restart via prefs round-trip`() = runTest {
        val prefs = FakePrefs()
        val a = ConsumedNonceStore(prefs = prefs, nowMs = { 1L })
        a.markConsumed("n-persist")
        val b = ConsumedNonceStore(prefs = prefs, nowMs = { 1L })
        assertTrue(b.isConsumed("n-persist"))
    }

    @Test
    fun `nonce store deduplicates double mark-consumed`() = runTest {
        val prefs = FakePrefs()
        val store = ConsumedNonceStore(prefs = prefs, nowMs = { 1L })
        store.markConsumed("n-x")
        store.markConsumed("n-x")
        store.markConsumed("n-x")
        // No assertion on internal count beyond "still works"; persisted JSON
        // should contain exactly one entry. We probe by isConsumed and
        // by re-loading.
        val raw = prefs.store[ConsumedNonceStore.KEY]
        assertTrue(raw != null && raw.indexOf("n-x") == raw.lastIndexOf("n-x"))
    }

    // ── Companion config (Phase 3 — sub-pass beyond receiver UI) ─

    private fun payloadWithConfig(
        nonce: String,
        secrets: List<SecretRecord>,
        configEntries: List<ConfigEntry>,
    ): SecretsTransferPayload = SecretsTransferPayload(
        senderDeviceName = "test",
        createdAtEpochMs = 1L,
        expiresAtEpochMs = 100L,
        transferNonce = nonce,
        categories = (secrets.map { it.category } + configEntries.map { it.category }).distinct(),
        secrets = secrets,
        configEntries = configEntries,
    )

    private val plexUrlEntry = ConfigEntry(
        SecretCategory.PLEX_JELLYFIN,
        DefaultConfigKeyAllowlist.PLEX_SERVER_URL,
        "https://plex.example",
    )
    private val jellyfinUrlEntry = ConfigEntry(
        SecretCategory.PLEX_JELLYFIN,
        DefaultConfigKeyAllowlist.JELLYFIN_SERVER_URL,
        "https://jelly.example",
    )

    @Test
    fun `success applies tokens and companion URLs together`() = runTest {
        val secrets = FakeSecretStore()
        val prefs = FakePrefs()
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, prefs)
        val out = applier.apply(
            payloadWithConfig(
                nonce = "n-pj",
                secrets = listOf(plexSecret),
                configEntries = listOf(plexUrlEntry, jellyfinUrlEntry),
            ),
        )
        val ok = assertIs<TransferApplyResult.Success>(out)
        assertEquals(1, ok.applied)
        assertEquals(2, ok.configApplied)
        assertEquals("plex_token", secrets.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN))
        assertEquals("https://plex.example", prefs.store[DefaultConfigKeyAllowlist.PLEX_SERVER_URL])
        assertEquals("https://jelly.example", prefs.store[DefaultConfigKeyAllowlist.JELLYFIN_SERVER_URL])
        assertTrue(ok.categoriesMissingCompanionConfig.isEmpty())
        assertTrue(nonces.isConsumed("n-pj"))
    }

    @Test
    fun `success with token but no companion config flags missing-companion`() = runTest {
        val secrets = FakeSecretStore()
        val prefs = FakePrefs()
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, prefs)
        val out = applier.apply(
            payloadWithConfig(
                nonce = "n-no-url",
                secrets = listOf(plexSecret),
                configEntries = emptyList(),
            ),
        )
        val ok = assertIs<TransferApplyResult.Success>(out)
        assertEquals(1, ok.applied)
        assertEquals(0, ok.configApplied)
        assertEquals(listOf(SecretCategory.PLEX_JELLYFIN), ok.categoriesMissingCompanionConfig)
    }

    @Test
    fun `unknown config keys are skipped via allowlist`() = runTest {
        val secrets = FakeSecretStore()
        val prefs = FakePrefs()
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, prefs)
        val rogue = ConfigEntry(SecretCategory.PLEX_JELLYFIN, "torve.dangerous_pref", "evil")
        val out = applier.apply(
            payloadWithConfig(
                nonce = "n-rogue",
                secrets = listOf(plexSecret),
                configEntries = listOf(plexUrlEntry, rogue),
            ),
        )
        val ok = assertIs<TransferApplyResult.Success>(out)
        assertEquals(1, ok.applied)
        assertEquals(1, ok.configApplied)
        assertEquals(listOf("torve.dangerous_pref"), ok.skippedConfigKeys)
        assertNull(prefs.store["torve.dangerous_pref"])
    }

    @Test
    fun `config write failure rolls back already-written secrets`() = runTest {
        val secrets = FakeSecretStore()
        secrets.seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "old_plex")
        val prefs = FakePrefs()
        prefs.store[DefaultConfigKeyAllowlist.PLEX_SERVER_URL] = "https://old-plex.example"
        prefs.failSetOn = { key -> key == DefaultConfigKeyAllowlist.PLEX_SERVER_URL }
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, prefs)
        val out = applier.apply(
            payloadWithConfig(
                nonce = "n-cfg-fail",
                secrets = listOf(plexSecret),
                configEntries = listOf(plexUrlEntry),
            ),
        )
        val fail = assertIs<TransferApplyResult.StoreFailure>(out)
        assertTrue(fail.rollbackAttempted)
        assertTrue(fail.rollbackSucceeded)
        // Secret restored to its pre-image
        assertEquals("old_plex", secrets.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN))
        // Config file untouched (write failed before mutating, so pre-image stays)
        assertEquals("https://old-plex.example", prefs.store[DefaultConfigKeyAllowlist.PLEX_SERVER_URL])
        assertFalse(nonces.isConsumed("n-cfg-fail"))
    }

    @Test
    fun `secret write failure rolls back without touching config`() = runTest {
        val secrets = FakeSecretStore()
        secrets.failPutOn = { key, _ -> key == IntegrationSecretKey.PLEX_ACCESS_TOKEN }
        val prefs = FakePrefs()
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, prefs)
        val out = applier.apply(
            payloadWithConfig(
                nonce = "n-sec-fail",
                secrets = listOf(plexSecret),
                configEntries = listOf(plexUrlEntry),
            ),
        )
        val fail = assertIs<TransferApplyResult.StoreFailure>(out)
        assertTrue(fail.rollbackAttempted)
        assertTrue(fail.rollbackSucceeded)
        // Config never written — the secret put failed first (secrets-first ordering).
        assertEquals(0, prefs.setCount)
        assertNull(prefs.store[DefaultConfigKeyAllowlist.PLEX_SERVER_URL])
        // Nonce not consumed.
        assertFalse(nonces.isConsumed("n-sec-fail"))
    }

    @Test
    fun `config snapshot read failure aborts before any write`() = runTest {
        val secrets = FakeSecretStore()
        val prefs = FakePrefs()
        prefs.failGetOn = { key -> key == DefaultConfigKeyAllowlist.PLEX_SERVER_URL }
        val nonces = nonceStore()
        val applier = SecretsTransferApplier(secrets, nonces, prefs)
        val out = applier.apply(
            payloadWithConfig(
                nonce = "n-snap-fail",
                secrets = listOf(plexSecret),
                configEntries = listOf(plexUrlEntry),
            ),
        )
        val fail = assertIs<TransferApplyResult.StoreFailure>(out)
        assertFalse(fail.rollbackAttempted)
        // Secrets-side put never happened either, even though it would
        // have succeeded — atomicity wins.
        assertEquals(0, secrets.putCount, "no secret writes when config snapshot fails")
        assertEquals(0, prefs.setCount, "no config writes either")
        assertFalse(nonces.isConsumed("n-snap-fail"))
    }
}
