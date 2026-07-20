package com.torve.data.channels

import com.torve.domain.model.EpgData
import com.torve.db.TorveDatabase
import io.ktor.client.statement.HttpResponse
import io.ktor.utils.io.ByteReadChannel

internal actual object GzipSupport {
    actual suspend fun downloadToTempFile(
        response: HttpResponse,
        maxCompressedBytes: Long,
    ): EpgDownloadResult? = null

    actual suspend fun parseXmlTvAutoFromFileToDbOrNull(
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
    ): EpgStreamIngestResult? = null

    actual fun deleteTempFile(tempFilePath: String) = Unit

    actual suspend fun parseXmlTvStreamingToDbOrNull(
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
    ): EpgDbParseStats? = null

    actual suspend fun parseXmlTvGzipStreamingOrNull(
        compressedChannel: ByteReadChannel,
        parser: EpgParser,
        windowStartMs: Long,
        windowEndMs: Long,
        maxChannels: Int,
        maxProgrammes: Int,
    ): EpgData? = null

    actual suspend fun parseXmlTvGzipStreamingToDbOrNull(
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
    ): EpgDbParseStats? = null
}
