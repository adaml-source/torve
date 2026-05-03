package com.torve.data.channels

import com.torve.domain.model.EpgData
import com.torve.db.TorveDatabase
import io.ktor.client.statement.HttpResponse
import io.ktor.utils.io.ByteReadChannel

internal data class EpgDownloadResult(
    val tempFilePath: String,
    val bytesDownloaded: Long,
    val contentEncoding: String,
    val contentLength: Long?,
)

internal data class EpgStreamIngestResult(
    val stats: EpgDbParseStats,
    val isGzipDetected: Boolean,
    val usedTempFile: Boolean,
    val bytesDownloaded: Long,
    val bytesParsed: Long,
)

internal class EpgStreamLimitException(message: String) : IllegalStateException(message)

internal expect object GzipSupport {
    suspend fun downloadToTempFile(
        response: HttpResponse,
        maxCompressedBytes: Long,
    ): EpgDownloadResult?

    suspend fun parseXmlTvAutoFromFileToDbOrNull(
        tempFilePath: String,
        parser: EpgParser,
        db: TorveDatabase,
        userId: String,
        playlistId: String,
        generationId: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        contentEncoding: String?,
        contentLength: Long?,
        maxUncompressedBytes: Long,
        channelFilter: Set<String>?,
        resolveEpgChannelKey: ((xmltvChannelId: String, xmltvDisplayName: String?) -> String?)?,
        batchSize: Int,
        maxProgrammesPerChannel: Int,
        maxProgrammesTotal: Int,
        onProgress: ((EpgBatchProgress) -> Unit)?,
    ): EpgStreamIngestResult?

    fun deleteTempFile(tempFilePath: String)

    suspend fun parseXmlTvStreamingToDbOrNull(
        xmlChannel: ByteReadChannel,
        parser: EpgParser,
        db: TorveDatabase,
        userId: String,
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
        db: TorveDatabase,
        userId: String,
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
