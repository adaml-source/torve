package com.torve.domain.streams

import com.torve.data.addon.ParsedStream
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.domain.model.CandidateProvenanceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Locks the row → backend-candidate round-trip in place. The live
 * Torve backend rejects warm/resolve calls that don't carry a full
 * UsenetCandidate ({candidate_id, hash_key, optional nzb_url}), so the
 * row data path must preserve every required field at construction time.
 */
class UsenetCandidatePayloadTest {

    @Test
    fun candidateIdHelperPrefersPayloadOverLegacySourceKey() {
        val row = nzbdavRow(
            sourceKey = "legacy-fallback-id",
            payload = UsenetCandidatePayload(
                candidateId = "payload-id",
                hashKey = "abc123",
            ),
        )
        // New code path takes precedence; mapper-side migration can remove
        // accelerationSourceKey duplication once all producers populate
        // the structured payload.
        assertEquals("payload-id", row.usenetCandidateIdOrNull())
    }

    @Test
    fun candidateIdHelperFallsBackToLegacySourceKeyWhenPayloadMissing() {
        val row = nzbdavRow(sourceKey = "legacy-id", payload = null)
        assertEquals("legacy-id", row.usenetCandidateIdOrNull())
    }

    @Test
    fun candidateIdReturnsNullForNonNzbdavProvenance() {
        val torrentRow = ParsedStream(
            addonName = "Stremio",
            quality = "1080p",
            title = "Some torrent",
            accelerationSourceKey = "torrent-source",
            accelerationProvenanceKind = CandidateProvenanceKind.HASH_AVAILABILITY,
            usenetCandidate = UsenetCandidatePayload(candidateId = "x", hashKey = "y"),
        )
        // Even with a payload present, non-NzbDAV rows must NOT surface
        // a candidate id — that would cause non-Usenet rows to be sent
        // through the Usenet resolver.
        assertNull(torrentRow.usenetCandidateIdOrNull())
        assertNull(torrentRow.usenetCandidatePayloadOrNull())
    }

    @Test
    fun payloadAccessorReturnsFullObjectWhenComplete() {
        val payload = UsenetCandidatePayload(
            candidateId = "c-1",
            hashKey = "deadbeef",
            nzbUrl = "https://nzbdav.example.com/nzb/c-1.nzb",
        )
        val row = nzbdavRow(sourceKey = null, payload = payload)
        val resolved = assertNotNull(row.usenetCandidatePayloadOrNull())
        assertEquals("c-1", resolved.candidateId)
        assertEquals("deadbeef", resolved.hashKey)
        assertEquals("https://nzbdav.example.com/nzb/c-1.nzb", resolved.nzbUrl)
    }

    @Test
    fun payloadAccessorAcceptsMissingNzbUrlButRequiresHashKey() {
        // nzb_url is optional per the backend contract.
        val ok = nzbdavRow(
            sourceKey = null,
            payload = UsenetCandidatePayload(candidateId = "c", hashKey = "h", nzbUrl = null),
        )
        assertNotNull(ok.usenetCandidatePayloadOrNull())

        // hash_key is mandatory — a row without it cannot be sent to
        // the backend, so the helper must return null and the call
        // site must fall through to the next candidate.
        val missingHash = nzbdavRow(
            sourceKey = null,
            payload = UsenetCandidatePayload(candidateId = "c", hashKey = "  "),
        )
        assertNull(missingHash.usenetCandidatePayloadOrNull())
    }

    @Test
    fun payloadAccessorRejectsBlankCandidateId() {
        val blank = nzbdavRow(
            sourceKey = "legacy",
            payload = UsenetCandidatePayload(candidateId = "  ", hashKey = "h"),
        )
        // Even though the legacy field has a value, returning the
        // payload would surface a row with a blank candidate_id —
        // wrong shape. Caller falls through.
        assertNull(blank.usenetCandidatePayloadOrNull())
    }

    @Test
    fun payloadAccessorReturnsNullForNzbdavRowsWithoutPayload() {
        // Pre-contract-fix rows that only carry accelerationSourceKey
        // can no longer be sent to the live backend (it requires
        // hash_key). The id helper still works for legacy display, but
        // the payload accessor returns null so coordinators skip them.
        val legacyOnly = nzbdavRow(sourceKey = "legacy", payload = null)
        assertNull(legacyOnly.usenetCandidatePayloadOrNull())
        assertEquals("legacy", legacyOnly.usenetCandidateIdOrNull())
    }

    @Test
    fun nonNzbdavRowsAreUnaffectedByThePayloadField() {
        // Regression guard: adding a nullable field to ParsedStream must
        // not change the construction shape of every other source.
        val pandaRow = ParsedStream(
            addonName = "Panda",
            quality = "1080p",
            title = "Panda source",
            infoHash = "abc",
            // No accelerationProvenanceKind, no usenetCandidate.
        )
        assertNull(pandaRow.usenetCandidateIdOrNull())
        assertNull(pandaRow.usenetCandidatePayloadOrNull())
        assertNull(pandaRow.usenetCandidate)
    }

    private fun nzbdavRow(
        sourceKey: String?,
        payload: UsenetCandidatePayload?,
    ) = ParsedStream(
        addonName = "Torve",
        quality = "1080p",
        title = "Source",
        accelerationSourceKey = sourceKey,
        accelerationProvenanceKind = CandidateProvenanceKind.USENET_NZBDAV,
        usenetCandidate = payload,
    )
}
