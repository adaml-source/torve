package com.torve.data.usenet

import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.data.usenet.model.UsenetCandidatePayload

/**
 * Single projection point between the app-side domain
 * [UsenetCandidatePayload] (carried on `ParsedStream`) and the wire-side
 * [UsenetCandidateDto] (consumed by `UsenetApi.warm` + `UsenetApi.resolve`).
 *
 * The two shapes are intentionally identical at the data level. They are
 * kept in separate types so the row layer (`ParsedStream`) doesn't pull
 * in `kotlinx.serialization` and the wire layer doesn't accidentally
 * leak into the renderer. Any contract change to the wire shape stays
 * contained inside this file plus `UsenetDtos.kt`.
 */
object UsenetCandidateMapping {

    /** Domain payload → wire DTO. One-liner. */
    fun UsenetCandidatePayload.toDto(): UsenetCandidateDto = UsenetCandidateDto(
        candidateId = candidateId,
        hashKey = hashKey,
        nzbUrl = nzbUrl,
    )

    /** Wire DTO → domain payload. Inverse one-liner. */
    fun UsenetCandidateDto.toPayload(): UsenetCandidatePayload = UsenetCandidatePayload(
        candidateId = candidateId,
        hashKey = hashKey,
        nzbUrl = nzbUrl,
    )
}
