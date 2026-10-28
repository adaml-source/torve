package com.torve.data.playback

import com.torve.db.TorveDatabase
import com.torve.domain.player.PlaybackSegment
import com.torve.domain.repository.CachedSegmentAnalysis
import com.torve.domain.repository.PlaybackSegmentRepository
import com.torve.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PlaybackSegmentRepositoryImpl(
    private val database: TorveDatabase,
    private val json: Json,
) : PlaybackSegmentRepository {
    override suspend fun get(
        canonicalEpisodeId: String,
        mediaFingerprint: String,
        analysisVersion: Int,
    ): CachedSegmentAnalysis? = withContext(ioDispatcher) {
        runCatching {
            database.torveQueries.getPlaybackSegmentAnalysis(
                canonicalEpisodeId,
                mediaFingerprint,
                analysisVersion.toLong(),
            ).executeAsOneOrNull()?.toDomain()
        }.getOrNull()
    }

    override suspend fun put(analysis: CachedSegmentAnalysis) = withContext(ioDispatcher) {
        database.torveQueries.upsertPlaybackSegmentAnalysis(
            canonical_episode_id = analysis.canonicalEpisodeId,
            media_fingerprint = analysis.mediaFingerprint,
            runtime_ms = analysis.runtimeMs,
            analysis_version = analysis.analysisVersion.toLong(),
            detector_versions = analysis.detectorVersions,
            validation_count = analysis.validationCount.toLong(),
            segments_json = json.encodeToString(analysis.segments),
            analyzed_at = analysis.analyzedAtEpochMs,
            updated_at = analysis.updatedAtEpochMs,
        )
    }

    override suspend fun getEpisodeAnalyses(
        canonicalEpisodeId: String,
        analysisVersion: Int,
    ): List<CachedSegmentAnalysis> = withContext(ioDispatcher) {
        runCatching {
            database.torveQueries.getPlaybackSegmentAnalysesForEpisode(
                canonicalEpisodeId,
                analysisVersion.toLong(),
            ).executeAsList().mapNotNull { row -> runCatching { row.toDomain() }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun com.torve.db.Playback_segment_analysis.toDomain(): CachedSegmentAnalysis =
        CachedSegmentAnalysis(
            canonicalEpisodeId = canonical_episode_id,
            mediaFingerprint = media_fingerprint,
            runtimeMs = runtime_ms,
            analysisVersion = analysis_version.toInt(),
            detectorVersions = detector_versions,
            validationCount = validation_count.toInt(),
            segments = json.decodeFromString<List<PlaybackSegment>>(segments_json),
            analyzedAtEpochMs = analyzed_at,
            updatedAtEpochMs = updated_at,
        )
}
