package com.torve.presentation.panda

import com.torve.data.panda.NzbIndexerRow
import com.torve.data.panda.PandaConfigPatch
import com.torve.data.panda.PandaIndexerSecret
import kotlinx.serialization.json.Json
import kotlin.test.*

class PandaUsenetDraftTest {
    private val saved = listOf(NzbIndexerRow("scenenzbs", "", "saved-key"))
    private val custom = NzbIndexerRow("custom", "https://indexer.example/api", "custom-key")
    private val server = PandaSetupUiState(nzbIndexers = saved)

    @Test fun backgroundRefreshKeepsAddedRowAndTyping() {
        val draft = PandaUsenetDraft()
        val read = draft.read("alice")
        draft.edited("alice")
        val editing = server.copy(nzbIndexers = saved + custom, downloadClientApiKey = "new-client-key")
        val merged = draft.merge(editing, server.copy(maxQuality = "2160p"), read, "alice")
        assertEquals(saved + custom, merged.nzbIndexers)
        assertEquals("new-client-key", merged.downloadClientApiKey)
        assertEquals("2160p", merged.maxQuality)
    }

    @Test fun slowReadCannotUndoCompletedSave() {
        val draft = PandaUsenetDraft()
        val oldRead = draft.read("alice")
        draft.edited("alice")
        val editing = server.copy(nzbIndexers = saved + custom)
        draft.saved(draft.read("alice"), "alice")
        assertFalse(draft.hasEdits("alice"))
        assertEquals(editing.nzbIndexers, draft.merge(editing, server, oldRead, "alice").nzbIndexers)
        val fresh = draft.read("alice")
        assertEquals(server, draft.merge(editing, server, fresh, "alice"))
    }

    @Test fun typingDuringSaveStaysUnsavedAndFailedSaveKeepsDraft() {
        val draft = PandaUsenetDraft()
        draft.edited("alice")
        val submitted = draft.read("alice")
        draft.edited("alice")
        draft.saved(submitted, "alice")
        assertTrue(draft.hasEdits("alice"))
        val editing = server.copy(nzbIndexers = saved + custom)
        assertEquals(editing, draft.merge(editing, server, draft.read("alice"), "alice"))
    }

    @Test fun switchingAccountDoesNotKeepPreviousAccountDraft() {
        val draft = PandaUsenetDraft()
        draft.edited("alice")
        val alice = draft.read("alice")
        val bob = draft.read("bob")
        assertFalse(draft.hasEdits("bob"))
        val empty = PandaSetupUiState()
        assertEquals(empty, draft.merge(empty, server, alice, "bob"))
        assertEquals(empty, draft.merge(server, empty, bob, "bob"))
    }

    @Test fun deletingConfigurationClearsItsDraft() {
        val draft = PandaUsenetDraft()
        draft.edited("alice")
        draft.discarded("alice")
        assertFalse(draft.hasEdits("alice"))
        assertEquals(PandaSetupUiState(), draft.merge(server, PandaSetupUiState(), draft.read("alice"), "alice"))
    }

    @Test fun changedIndexerListIsIncludedInSerializedPatch() {
        val rows = pandaIndexersForUpdate(saved + custom, saved, emptyList())
        val patch = PandaConfigPatch(nzbIndexers = rows)
        val roundTrip = Json.decodeFromString<PandaConfigPatch>(Json.encodeToString(PandaConfigPatch.serializer(), patch))
        assertEquals(saved + custom, roundTrip.nzbIndexers)
    }

    @Test fun serverOnlyKeyIsRecoveredByIdentityBeforeAddingAnotherIndexer() {
        val redacted = listOf(saved.single().copy(apiKey = ""))
        val recovered = pandaIndexersForUpdate(redacted + custom, redacted,
            listOf(PandaIndexerSecret("scenenzbs", "", "saved-key")))
        assertEquals(saved + custom, recovered)
        assertNull(pandaIndexersForUpdate(redacted, redacted, emptyList()))
    }

    @Test fun missingExistingKeyCannotSilentlyDiscardNewIndexer() {
        val redacted = listOf(saved.single().copy(apiKey = ""))
        val error = assertFailsWith<IllegalArgumentException> {
            pandaIndexersForUpdate(redacted + custom, redacted, emptyList())
        }
        assertTrue(error.message.orEmpty().contains("#1"))
        assertFalse(error.message.orEmpty().contains(custom.apiKey))
    }

    @Test fun deletingFirstRowDoesNotCopyItsKeyToAnotherCustomEndpoint() {
        assertFailsWith<IllegalArgumentException> {
            pandaIndexersForUpdate(listOf(custom.copy(apiKey = "")), saved,
                listOf(PandaIndexerSecret("custom", "https://different.example/api", "different-key")))
        }
        assertEquals(listOf(custom), pandaIndexersForUpdate(listOf(custom), saved + custom, emptyList()))
    }

    @Test fun incompleteCustomEntryFailsVisiblyWhileBlankPlaceholderIsIgnored() {
        assertEquals(saved, pandaIndexersForSave(saved + NzbIndexerRow()))
        assertFailsWith<IllegalArgumentException> { pandaIndexersForSave(listOf(custom.copy(url = ""))) }
        assertFailsWith<IllegalArgumentException> { pandaIndexersForSave(listOf(custom.copy(apiKey = ""))) }
        assertFailsWith<IllegalArgumentException> { pandaIndexersForSave(listOf(custom.copy(url = "not a URL"))) }
        assertEquals(listOf(custom), pandaIndexersForSave(listOf(custom.copy(url = " ${custom.url} ", apiKey = " ${custom.apiKey} "))))
    }
}
