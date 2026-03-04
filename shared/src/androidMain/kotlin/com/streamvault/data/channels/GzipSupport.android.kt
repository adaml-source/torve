package com.streamvault.data.channels

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.EpgData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

private const val IO_BUFFER_BYTES = 64 * 1024
private const val GZIP_MAGIC_BYTE_1 = 0x1F
private const val GZIP_MAGIC_BYTE_2 = 0x8B

private data class PreparedXmlInput(
    val input: InputStream,
    val isGzipDetected: Boolean,
)

private class CountingLimitInputStream(
    delegate: InputStream,
    private val maxBytes: Long,
    private val limitMessage: String,
) : FilterInputStream(delegate) {
    var totalBytesRead: Long = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            onRead(1L)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) {
            onRead(read.toLong())
        }
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0L) {
            onRead(skipped)
        }
        return skipped
    }

    private fun onRead(bytes: Long) {
        totalBytesRead += bytes
        if (totalBytesRead > maxBytes) {
            throw EpgStreamLimitException(limitMessage)
        }
    }
}

internal actual object GzipSupport {
    actual suspend fun parseXmlTvAutoStreamingToDbOrNull(
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
    ): EpgStreamIngestResult? {
        val shouldSpoolToFile = contentLength == null ||
            contentLength < 0L ||
            contentLength >= spoolToFileThresholdBytes
        return if (shouldSpoolToFile) {
            parseWithTempFile(
                responseChannel = responseChannel,
                parser = parser,
                db = db,
                playlistId = playlistId,
                generationId = generationId,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                contentEncoding = contentEncoding,
                maxCompressedBytes = maxCompressedBytes,
                maxUncompressedBytes = maxUncompressedBytes,
                channelFilter = channelFilter,
                resolveEpgChannelKey = resolveEpgChannelKey,
                batchSize = batchSize,
                onProgress = onProgress,
            )
        } else {
            parseDirectStream(
                responseChannel = responseChannel,
                parser = parser,
                db = db,
                playlistId = playlistId,
                generationId = generationId,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                contentEncoding = contentEncoding,
                maxCompressedBytes = maxCompressedBytes,
                maxUncompressedBytes = maxUncompressedBytes,
                channelFilter = channelFilter,
                resolveEpgChannelKey = resolveEpgChannelKey,
                batchSize = batchSize,
                onProgress = onProgress,
            )
        }
    }

    actual suspend fun parseXmlTvStreamingToDbOrNull(
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
    ): EpgDbParseStats? {
        return runCatching {
            xmlChannel.toInputStream().use { raw ->
                BufferedInputStream(raw, IO_BUFFER_BYTES).use { buffered ->
                    parser.parseXmlTvStreamingToDb(
                        input = buffered,
                        db = db,
                        playlistId = playlistId,
                        generationId = generationId,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        channelFilter = channelFilter,
                        resolveEpgChannelKey = resolveEpgChannelKey,
                        batchSize = batchSize,
                        onProgress = onProgress,
                    )
                }
            }
        }.getOrNull()
    }

    actual suspend fun parseXmlTvGzipStreamingOrNull(
        compressedChannel: ByteReadChannel,
        parser: EpgParser,
        windowStartMs: Long,
        windowEndMs: Long,
        maxChannels: Int,
        maxProgrammes: Int,
    ): EpgData? {
        return runCatching {
            compressedChannel.toInputStream().use { rawCompressed ->
                BufferedInputStream(rawCompressed, IO_BUFFER_BYTES).use { buffered ->
                    GZIPInputStream(buffered, IO_BUFFER_BYTES).use { gzipStream ->
                        parser.parseXmlTvStreaming(
                            readChunk = { target ->
                                gzipStream.read(target)
                            },
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs,
                            maxChannels = maxChannels,
                            maxProgrammes = maxProgrammes,
                        )
                    }
                }
            }
        }.getOrNull()
    }

    actual suspend fun parseXmlTvGzipStreamingToDbOrNull(
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
    ): EpgDbParseStats? {
        return runCatching {
            compressedChannel.toInputStream().use { rawCompressed ->
                BufferedInputStream(rawCompressed, IO_BUFFER_BYTES).use { buffered ->
                    GZIPInputStream(buffered, IO_BUFFER_BYTES).use { gzipStream ->
                        parser.parseXmlTvStreamingToDb(
                            input = gzipStream,
                            db = db,
                            playlistId = playlistId,
                            generationId = generationId,
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs,
                            channelFilter = channelFilter,
                            resolveEpgChannelKey = resolveEpgChannelKey,
                            batchSize = batchSize,
                            onProgress = onProgress,
                        )
                    }
                }
            }
        }.getOrNull()
    }
}

private suspend fun parseDirectStream(
    responseChannel: ByteReadChannel,
    parser: EpgParser,
    db: StreamVaultDatabase,
    playlistId: String,
    generationId: Long,
    windowStartMs: Long,
    windowEndMs: Long,
    contentEncoding: String?,
    maxCompressedBytes: Long,
    maxUncompressedBytes: Long,
    channelFilter: Set<String>?,
    resolveEpgChannelKey: ((xmltvChannelId: String, xmltvDisplayName: String?) -> String?)?,
    batchSize: Int,
    onProgress: ((EpgBatchProgress) -> Unit)?,
): EpgStreamIngestResult {
    val compressedLimitMessage =
        "EPG download exceeded ${maxCompressedBytes.toMegabytes()}MB compressed limit. Reduce provider EPG days."
    val uncompressedLimitMessage =
        "EPG XML exceeded ${maxUncompressedBytes.toMegabytes()}MB parse limit. Reduce provider EPG days."

    var stats: EpgDbParseStats? = null
    var bytesDownloaded = 0L
    var bytesParsed = 0L
    var isGzipDetected = false

    responseChannel.toInputStream().use { rawResponse ->
        val countedNetwork = CountingLimitInputStream(
            delegate = rawResponse,
            maxBytes = maxCompressedBytes,
            limitMessage = compressedLimitMessage,
        )
        countedNetwork.use { networkStream ->
            val preparedInput = prepareXmlInput(
                rawInput = BufferedInputStream(networkStream, IO_BUFFER_BYTES),
                contentEncoding = contentEncoding,
            )
            isGzipDetected = preparedInput.isGzipDetected
            preparedInput.input.use { xmlInput ->
                CountingLimitInputStream(
                    delegate = xmlInput,
                    maxBytes = maxUncompressedBytes,
                    limitMessage = uncompressedLimitMessage,
                ).use { countedParsed ->
                    stats = parser.parseXmlTvStreamingToDb(
                        input = countedParsed,
                        db = db,
                        playlistId = playlistId,
                        generationId = generationId,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        channelFilter = channelFilter,
                        resolveEpgChannelKey = resolveEpgChannelKey,
                        batchSize = batchSize,
                        onProgress = onProgress,
                    )
                    bytesParsed = countedParsed.totalBytesRead
                }
            }
            bytesDownloaded = networkStream.totalBytesRead
        }
    }

    return EpgStreamIngestResult(
        stats = requireNotNull(stats),
        isGzipDetected = isGzipDetected,
        usedTempFile = false,
        bytesDownloaded = bytesDownloaded,
        bytesParsed = bytesParsed,
    )
}

private suspend fun parseWithTempFile(
    responseChannel: ByteReadChannel,
    parser: EpgParser,
    db: StreamVaultDatabase,
    playlistId: String,
    generationId: Long,
    windowStartMs: Long,
    windowEndMs: Long,
    contentEncoding: String?,
    maxCompressedBytes: Long,
    maxUncompressedBytes: Long,
    channelFilter: Set<String>?,
    resolveEpgChannelKey: ((xmltvChannelId: String, xmltvDisplayName: String?) -> String?)?,
    batchSize: Int,
    onProgress: ((EpgBatchProgress) -> Unit)?,
): EpgStreamIngestResult {
    val tempDir = resolveTempDir()
    val tempFile = File.createTempFile("streamvault_epg_", ".tmp", tempDir)
    val compressedLimitMessage =
        "EPG download exceeded ${maxCompressedBytes.toMegabytes()}MB compressed limit. Reduce provider EPG days."
    val uncompressedLimitMessage =
        "EPG XML exceeded ${maxUncompressedBytes.toMegabytes()}MB parse limit. Reduce provider EPG days."

    var bytesDownloaded = 0L
    var bytesParsed = 0L
    var stats: EpgDbParseStats? = null
    var isGzipDetected = false
    try {
        responseChannel.toInputStream().use { rawResponse ->
            CountingLimitInputStream(
                delegate = BufferedInputStream(rawResponse, IO_BUFFER_BYTES),
                maxBytes = maxCompressedBytes,
                limitMessage = compressedLimitMessage,
            ).use { countedNetwork ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER_BYTES)
                    while (true) {
                        val read = countedNetwork.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
                bytesDownloaded = countedNetwork.totalBytesRead
            }
        }

        FileInputStream(tempFile).use { rawFile ->
            val preparedInput = prepareXmlInput(
                rawInput = BufferedInputStream(rawFile, IO_BUFFER_BYTES),
                contentEncoding = contentEncoding,
            )
            isGzipDetected = preparedInput.isGzipDetected
            preparedInput.input.use { xmlInput ->
                CountingLimitInputStream(
                    delegate = xmlInput,
                    maxBytes = maxUncompressedBytes,
                    limitMessage = uncompressedLimitMessage,
                ).use { countedParsed ->
                    stats = parser.parseXmlTvStreamingToDb(
                        input = countedParsed,
                        db = db,
                        playlistId = playlistId,
                        generationId = generationId,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        channelFilter = channelFilter,
                        resolveEpgChannelKey = resolveEpgChannelKey,
                        batchSize = batchSize,
                        onProgress = onProgress,
                    )
                    bytesParsed = countedParsed.totalBytesRead
                }
            }
        }
    } finally {
        runCatching { tempFile.delete() }
    }

    return EpgStreamIngestResult(
        stats = requireNotNull(stats),
        isGzipDetected = isGzipDetected,
        usedTempFile = true,
        bytesDownloaded = bytesDownloaded,
        bytesParsed = bytesParsed,
    )
}

private fun prepareXmlInput(
    rawInput: InputStream,
    contentEncoding: String?,
): PreparedXmlInput {
    val pushbackInput = PushbackInputStream(rawInput, 2)
    val first = pushbackInput.read()
    val second = pushbackInput.read()
    if (second >= 0) pushbackInput.unread(second)
    if (first >= 0) pushbackInput.unread(first)

    val magicGzip = first == GZIP_MAGIC_BYTE_1 && second == GZIP_MAGIC_BYTE_2
    val encodingGzip = contentEncoding?.contains("gzip", ignoreCase = true) == true

    if (encodingGzip && !magicGzip) {
        println("ChannelsEPG: gzip hint mismatch contentEncoding=$contentEncoding magic=false")
    }

    return if (magicGzip) {
        PreparedXmlInput(
            input = GZIPInputStream(pushbackInput, IO_BUFFER_BYTES),
            isGzipDetected = true,
        )
    } else {
        PreparedXmlInput(
            input = pushbackInput,
            isGzipDetected = false,
        )
    }
}

private fun resolveTempDir(): File {
    val tmpDirPath = System.getProperty("java.io.tmpdir").orEmpty().ifBlank { "." }
    val dir = File(tmpDirPath)
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

private fun Long.toMegabytes(): Long = this / (1024L * 1024L)
