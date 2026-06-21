package com.torve.domain.model

data class NowNextEpg(
    val now: EpgProgramme?,
    val next: EpgProgramme?,
)

object LiveTvEpgResolver {
    fun lookupKeys(
        playlistId: String,
        channel: Channel,
    ): List<String> = epgChannelLookupKeys(playlistId, channel)

    fun resolveProgrammes(
        channel: Channel,
        playlistId: String,
        programmesByChannelKey: Map<String, List<EpgProgramme>>,
    ): List<EpgProgramme> {
        if (programmesByChannelKey.isEmpty()) return emptyList()
        return lookupKeys(playlistId, channel)
            .firstNotNullOfOrNull { key ->
                programmesByChannelKey[key]?.takeIf { it.isNotEmpty() }
            }
            .orEmpty()
            .sortedBy { it.startTime }
    }

    fun resolveProgrammesForRange(
        channel: Channel,
        playlistId: String,
        programmesByChannelKey: Map<String, List<EpgProgramme>>,
        rangeStartMs: Long,
        rangeEndMs: Long,
    ): List<EpgProgramme> {
        if (rangeEndMs <= rangeStartMs) return emptyList()
        return resolveProgrammes(channel, playlistId, programmesByChannelKey)
            .filter { overlapsRange(it, rangeStartMs, rangeEndMs) }
    }

    fun resolveNowNext(
        channel: Channel,
        playlistId: String,
        programmesByChannelKey: Map<String, List<EpgProgramme>>,
        nowMs: Long,
    ): NowNextEpg {
        val programmes = resolveProgrammes(channel, playlistId, programmesByChannelKey)
        val current = programmes.firstOrNull { it.startTime <= nowMs && it.endTime > nowMs }
        val next = programmes.firstOrNull { it.startTime > nowMs }
            ?: programmes.firstOrNull { it.startTime >= current?.endTime ?: Long.MAX_VALUE }
        return NowNextEpg(now = current, next = next)
    }

    fun overlapsRange(
        programme: EpgProgramme,
        rangeStartMs: Long,
        rangeEndMs: Long,
    ): Boolean {
        return programme.endTime > rangeStartMs && programme.startTime < rangeEndMs
    }
}
