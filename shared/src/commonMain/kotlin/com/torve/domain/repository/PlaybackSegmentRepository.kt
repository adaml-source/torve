package com.torve.domain.repository

import com.torve.domain.player.PlaybackSegment

data class CachedSegmentAnalysis(
    val canonicalEpisodeId: String,
    val mediaFingerprint: String,
    val runtimeMs: Long,
    val analysisVersion: Int,
    val detectorVersions: String,
    val validationCount: Int,
    val segments: List<PlaybackSegment>,
    val analyzedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

interface PlaybackSegmentRepository {
    suspend fun get(
        canonicalEpisodeId: String,
        mediaFingerprint: String,
        analysisVersion: Int,
    ): CachedSegmentAnalysis?

    suspend fun put(analysis: CachedSegmentAnalysis)

    suspend fun getEpisodeAnalyses(
        canonicalEpisodeId: String,
        analysisVersion: Int,
    ): List<CachedSegmentAnalysis>
}
