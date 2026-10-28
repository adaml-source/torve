package com.torve.data.progress

import com.torve.db.TorveQueries

/** SQLite slices avoid Android's per-row CursorWindow limit, including existing saved rows. */
internal fun TorveQueries.readPreferenceSafely(userId: String, key: String): String? {
    val size = 131_072L // At most 512 KiB of UTF-8 even for four-byte code points.
    val first = getPreferenceSlice(start = "1", count = size.toString(), userId = userId, key = key).executeAsOneOrNull() ?: return null
    if ((first.characters ?: 0L) <= size) return first.part
    // Keep all slices in one snapshot if another coroutine updates this preference.
    return transactionWithResult {
        val head = getPreferenceSlice(start = "1", count = size.toString(), userId = userId, key = key).executeAsOneOrNull()
            ?: return@transactionWithResult null
        val characters = head.characters ?: 0L
        buildString(minOf(characters, 4_194_304L).toInt()) {
            append(head.part.orEmpty())
            var offset = size + 1L
            while (offset <= characters) {
                append(getPreferenceSlice(start = offset.toString(), count = size.toString(), userId = userId, key = key)
                    .executeAsOneOrNull()?.part.orEmpty())
                offset += size
            }
        }
    }
}
