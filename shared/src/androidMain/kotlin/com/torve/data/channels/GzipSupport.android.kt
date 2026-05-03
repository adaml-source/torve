package com.torve.data.channels

import com.torve.db.TorveDatabase
import com.torve.domain.model.EpgData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

private const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
private const val PARSE_BUFFER_BYTES = 64 * 1024
private const val DOWNLOAD_PROGRESS_LOG_STEP_BYTES = 5L * 1024L * 1024L
private const val LEGACY_CHANNEL_SPOOL_MAX_BYTES = 180L * 1024L * 1024L
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

    private fun onRead(bytes: Long) {
        totalBytesRead += bytes
        if (totalBytesRead > maxBytes) {
            throw EpgStreamLimitException(limitMessage)
        }
    }
}

internal actual object GzipSupport {
    actual suspend fun downloadToTempFile(
        response: HttpResponse,
        maxCompressedBytes: Long,
    ): EpgDownloadResult? {
        val tempFile = createTempFile()
        val contentEncoding = response.headers["Content-Encoding"].orEmpty()
        val contentLength = response.headers["Content-Length"]?.toLongOrNull()
        println(
            "ChannelsEPG: downloadToTempFile ENTER maxCompressedBytes=$maxCompressedBytes contentLength=${contentLength ?: -1} contentEncoding=$contentEncoding",
        )
        if (contentLength != null && contentLength > maxCompressedBytes) {
            runCatching { tempFile.delete() }
            throw EpgStreamLimitException(
                "EPG too large (${contentLength.toMegabytes()}MB compressed). Reduce provider EPG days.",
            )
        }

        val channel: ByteReadChannel = response.bodyAsChannel()
        var nextProgressLogAt = DOWNLOAD_PROGRESS_LOG_STEP_BYTES

        try {
            val bytesDownloaded = writeChannelToFile(
                channel = channel,
                outputFile = tempFile,
                maxBytes = maxCompressedBytes,
                limitMessage = { bytes ->
                    "EPG too large (${bytes.toMegabytes()}MB compressed). Reduce provider EPG days."
                },
                onBytesWritten = { bytesWritten ->
                    if (bytesWritten >= nextProgressLogAt) {
                        println("ChannelsEPG: downloadToTempFile PROGRESS bytesDownloaded=$bytesWritten")
                        while (bytesWritten >= nextProgressLogAt) {
                            nextProgressLogAt += DOWNLOAD_PROGRESS_LOG_STEP_BYTES
                        }
                    }
                },
            )
            println(
                "ChannelsEPG: downloadToTempFile DONE bytesDownloaded=$bytesDownloaded path=${tempFile.absolutePath}",
            )
            return EpgDownloadResult(
                tempFilePath = tempFile.absolutePath,
                bytesDownloaded = bytesDownloaded,
                contentEncoding = contentEncoding,
                contentLength = contentLength,
            )
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }
    }

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
    ): EpgStreamIngestResult? {
        val file = File(tempFilePath)
        if (!file.exists() || !file.isFile) return null
        val uncompressedLimitMessage =
            "EPG XML exceeded ${maxUncompressedBytes.toMegabytes()}MB parse limit. Reduce provider EPG days."

        var stats: EpgDbParseStats? = null
        var bytesParsed = 0L
        var gzipDetected = false

        FileInputStream(file).use { fileInput ->
            BufferedInputStream(fileInput, PARSE_BUFFER_BYTES).use { buffered ->
                val preparedInput = prepareXmlInput(
                    rawInput = buffered,
                    contentEncoding = contentEncoding,
                    contentLength = contentLength,
                )
                gzipDetected = preparedInput.isGzipDetected
                preparedInput.input.use { xmlInput ->
                    CountingLimitInputStream(
                        delegate = xmlInput,
                        maxBytes = maxUncompressedBytes,
                        limitMessage = uncompressedLimitMessage,
                    ).use { counted ->
                        stats = parser.parseXmlTvStreamingToDb(
                            input = counted,
                            db = db,
                            userId = userId,
                            playlistId = playlistId,
                            generationId = generationId,
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs,
                            channelFilter = channelFilter,
                            resolveEpgChannelKey = resolveEpgChannelKey,
                            batchSize = batchSize,
                            maxProgrammesPerChannel = maxProgrammesPerChannel,
                            maxProgrammesTotal = maxProgrammesTotal,
                            onProgress = onProgress,
                        )
                        bytesParsed = counted.totalBytesRead
                    }
                }
            }
        }

        return stats?.let {
            EpgStreamIngestResult(
                stats = it,
                isGzipDetected = gzipDetected,
                usedTempFile = true,
                bytesDownloaded = file.length(),
                bytesParsed = bytesParsed,
            )
        }
    }

    actual fun deleteTempFile(tempFilePath: String) {
        runCatching {
            File(tempFilePath).delete()
        }
    }

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
    ): EpgDbParseStats? {
        val tempFile = createTempFile()
        return runCatching {
            writeChannelToFile(
                channel = xmlChannel,
                outputFile = tempFile,
                maxBytes = LEGACY_CHANNEL_SPOOL_MAX_BYTES,
                limitMessage = { bytes ->
                    "EPG XML exceeded ${bytes.toMegabytes()}MB streaming limit."
                },
            )
            FileInputStream(tempFile).use { raw ->
                BufferedInputStream(raw, PARSE_BUFFER_BYTES).use { buffered ->
                    parser.parseXmlTvStreamingToDb(
                        input = buffered,
                        db = db,
                        userId = userId,
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
        }.onFailure {
            runCatching { tempFile.delete() }
        }.onSuccess {
            runCatching { tempFile.delete() }
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
        val tempFile = createTempFile()
        return runCatching {
            writeChannelToFile(
                channel = compressedChannel,
                outputFile = tempFile,
                maxBytes = LEGACY_CHANNEL_SPOOL_MAX_BYTES,
                limitMessage = { bytes ->
                    "EPG XML exceeded ${bytes.toMegabytes()}MB streaming limit."
                },
            )
            FileInputStream(tempFile).use { rawCompressed ->
                BufferedInputStream(rawCompressed, PARSE_BUFFER_BYTES).use { buffered ->
                    GZIPInputStream(buffered, PARSE_BUFFER_BYTES).use { gzipStream ->
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
        }.onFailure {
            runCatching { tempFile.delete() }
        }.onSuccess {
            runCatching { tempFile.delete() }
        }.getOrNull()
    }

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
    ): EpgDbParseStats? {
        val tempFile = createTempFile()
        return runCatching {
            writeChannelToFile(
                channel = compressedChannel,
                outputFile = tempFile,
                maxBytes = LEGACY_CHANNEL_SPOOL_MAX_BYTES,
                limitMessage = { bytes ->
                    "EPG XML exceeded ${bytes.toMegabytes()}MB streaming limit."
                },
            )
            FileInputStream(tempFile).use { rawCompressed ->
                BufferedInputStream(rawCompressed, PARSE_BUFFER_BYTES).use { buffered ->
                    GZIPInputStream(buffered, PARSE_BUFFER_BYTES).use { gzipStream ->
                        parser.parseXmlTvStreamingToDb(
                            input = gzipStream,
                            db = db,
                            userId = userId,
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
        }.onFailure {
            runCatching { tempFile.delete() }
        }.onSuccess {
            runCatching { tempFile.delete() }
        }.getOrNull()
    }
}

private suspend fun writeChannelToFile(
    channel: ByteReadChannel,
    outputFile: File,
    maxBytes: Long,
    limitMessage: (Long) -> String,
    onBytesWritten: ((Long) -> Unit)? = null,
): Long {
    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
    var bytesWritten = 0L
    try {
        FileOutputStream(outputFile).use { output ->
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                bytesWritten += read.toLong()
                if (bytesWritten > maxBytes) {
                    throw EpgStreamLimitException(limitMessage(bytesWritten))
                }
                output.write(buffer, 0, read)
                onBytesWritten?.invoke(bytesWritten)
            }
            output.flush()
        }
        return bytesWritten
    } catch (t: Throwable) {
        runCatching { channel.cancel(t) }
        throw t
    } finally {
        runCatching { channel.cancel(null) }
    }
}

private fun prepareXmlInput(
    rawInput: InputStream,
    contentEncoding: String?,
    contentLength: Long?,
): PreparedXmlInput {
    val pushback = PushbackInputStream(rawInput, 2)
    val first = pushback.read()
    val second = pushback.read()
    if (second >= 0) pushback.unread(second)
    if (first >= 0) pushback.unread(first)

    val magicGzip = first == GZIP_MAGIC_BYTE_1 && second == GZIP_MAGIC_BYTE_2
    val encodingGzip = contentEncoding?.contains("gzip", ignoreCase = true) == true

    if (magicGzip) {
        return PreparedXmlInput(
            input = GZIPInputStream(pushback, PARSE_BUFFER_BYTES),
            isGzipDetected = true,
        )
    }

    if (encodingGzip) {
        return try {
            PreparedXmlInput(
                input = GZIPInputStream(pushback, PARSE_BUFFER_BYTES),
                isGzipDetected = true,
            )
        } catch (_: ZipException) {
            println(
                "ChannelsEPG: gzip header mismatch contentEncoding=$contentEncoding contentLength=${contentLength ?: -1}, parsing as raw XML",
            )
            PreparedXmlInput(
                input = pushback,
                isGzipDetected = false,
            )
        }
    }

    return PreparedXmlInput(
        input = pushback,
        isGzipDetected = false,
    )
}

private fun createTempFile(): File {
    val tempDirPath = System.getProperty("java.io.tmpdir").orEmpty().ifBlank { "." }
    val tempDir = File(tempDirPath)
    if (!tempDir.exists()) {
        tempDir.mkdirs()
    }
    return File.createTempFile("torve_epg_", ".tmp", tempDir)
}

private fun Long.toMegabytes(): Long = this / (1024L * 1024L)
