package com.streamvault.data.channels

import com.streamvault.domain.model.EpgData
import com.streamvault.db.StreamVaultDatabase
import io.ktor.utils.io.ByteReadChannel

internal data class EpgStreamIngestResult(
    val stats: EpgDbParseStats,
    val isGzipDetected: Boolean,
    val usedTempFile: Boolean,
    val bytesDownloaded: Long,
    val bytesParsed: Long,
)

internal class EpgStreamLimitException(message: String) : IllegalStateException(message)

internal expect object GzipSupport {
    suspend fun parseXmlTvAutoStreamingToDbOrNull(
        responseChannel: ByteReadChannel,
        parser: EpgParser,
        db: StreamVaultDatabase,
        playlistId: String,
        generationId: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        contentLength: Long?,
        contentEncoding: String?,
        maxCompressedBytes: Long,
        maxUncompressedBytes: Long,
        spoolToFileThresholdBytes: Long,
        channelFilter: Set<String>?,
        resolveEpgChannelKey: ((xmltvChannelId: String, xmltvDisplayName: String?) -> String?)?,
        batchSize: Int,
        onProgress: ((EpgBatchProgress) -> Unit)?,
    ): EpgStreamIngestResult?

    suspend fun parseXmlTvStreamingToDbOrNull(
        xmlChannel: ByteReadChannel,
        parser: EpgParser,
        db: StreamVaultDatabase,
        playlistId: String,
        generationId: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        channelFilter: Set<String>?,
        resolveEpgChannelKey: ((xmltvChannelId: String, xmltvDisplayName: String?) -> String?)?,
        batchSize: Int,
        onProgress: ((EpgBatchProgress) -> Unit)?,
    ): EpgDbParseStats?

    suspend fun parseXmlTvGzipStreamingOrNull(
        compressedChannel: ByteReadChannel,
        parser: EpgParser,
        windowStartMs: Long,
        windowEndMs: Long,
        maxChannels: Int,
        maxProgrammes: Int,
    ): EpgData?

    suspend fun parseXmlTvGzipStreamingToDbOrNull(
        compressedChannel: ByteReadChannel,
        parser: EpgParser,
        db: StreamVaultDatabase,
        playlistId: String,
        generationId: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        channelFilter: Set<String>?,
        resolveEpgChannelKey: ((xmltvChannelId: String, xmltvDisplayName: String?) -> String?)?,
        batchSize: Int,
        onProgress: ((EpgBatchProgress) -> Unit)?,
    ): EpgDbParseStats?
}
