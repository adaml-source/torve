package com.torve.data.addon

enum class StreamSortMode { DEFAULT, SIZE_DESC, SIZE_ASC }

/** Keep recommended ordering intact; explicit size sorting spans every readiness group. */
fun sortStreamResults(streams: List<ParsedStream>, mode: StreamSortMode): List<ParsedStream> {
    if (mode == StreamSortMode.DEFAULT) return streams
    val sized = streams.map { it to parseStreamSizeBytes(it.size) }
    val known = sized.filter { it.second != null }
    val sorted = when (mode) {
        StreamSortMode.SIZE_DESC -> known.sortedByDescending { it.second }
        StreamSortMode.SIZE_ASC -> known.sortedBy { it.second }
        StreamSortMode.DEFAULT -> known
    }
    return sorted.map { it.first } + sized.filter { it.second == null }.map { it.first }
}
