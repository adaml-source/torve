package com.torve.util

/**
 * Decodes provider text before filename cleanup or display. Existing Unicode and
 * literal '+' characters stay intact. This is deliberately separate from URL
 * and identifier handling, where percent escapes must retain their meaning.
 */
object DisplayTextDecoder {
    private val percentBytes = Regex("(?:%[0-9a-fA-F]{2})+")
    private val characterReference = Regex("&(#(?:[xX][0-9a-fA-F]{1,8}|[0-9]{1,10})|[A-Za-z][A-Za-z0-9]{0,31});")
    private const val RELEASE_BYTE_SEPARATOR = "[._ ]"
    private const val RELEASE_UTF8_CHARACTER =
        "(?:(?:C[2-9A-F]|D[0-9A-F])$RELEASE_BYTE_SEPARATOR[89AB][0-9A-F]|" +
            "E[0-9A-F](?:$RELEASE_BYTE_SEPARATOR[89AB][0-9A-F]){2}|" +
            "F[0-4](?:$RELEASE_BYTE_SEPARATOR[89AB][0-9A-F]){3})"
    private val releaseBytes = Regex(
        "(^|$RELEASE_BYTE_SEPARATOR+)($RELEASE_UTF8_CHARACTER(?:$RELEASE_BYTE_SEPARATOR$RELEASE_UTF8_CHARACTER)*)(?=\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    private val wordEnding = Regex("[\\p{L}\\p{M}]+$")
    private val wordBeginning = Regex("^[\\p{L}\\p{M}]+")

    fun decode(value: String): String {
        if ('%' !in value && '&' !in value) return value
        var decoded = value
        // Feeds can escape a title twice, or percent-encode an HTML reference.
        // Bound the work even for malformed or repeatedly escaped provider text.
        repeat(4) {
            val next = decodePercentUtf8(decodeCharacterReferences(decoded))
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    /**
     * Some release filenames replace percent markers with separators, leaving
     * names such as M.C3.BCnchen. Recover complete UTF-8 sequences inside words
     * before filename cleanup removes those separators. Restrict this recovery
     * to titles: ordinary display labels, URLs and IDs must not use it.
     */
    fun decodeReleaseTitle(value: String): String {
        val decoded = decode(value)
        return releaseBytes.replace(decoded) { match ->
            val leading = match.groupValues[1]
            val escaped = match.groupValues[2]
            val leftWord = wordEnding.find(decoded.substring(0, match.range.first))?.value.orEmpty()
            val rightWord = wordBeginning.find(decoded.substring(match.range.last + 1))?.value.orEmpty()
            // Preserve hexadecimal identifiers such as AB.C3.BCDEF.
            if ((leftWord + rightWord).none { it.isLetter() && it.uppercaseChar() !in 'A'..'F' }) {
                return@replace match.value
            }
            val bytes = escaped.split('.', '_', ' ').map { it.toInt(16).toByte() }.toByteArray()
            val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                ?: return@replace match.value
            if (text.any { it.code in 0..31 || it.code in 127..159 }) return@replace match.value
            val separator = escaped[2]
            // In M.C3.BCnchen the first dot is an escape marker; in
            // Bayern..C3.9Cber or Bayern C3.9Cber retain the word separator.
            val prefix = if (leftWord.isNotEmpty() && leading.lastOrNull() == separator) leading.dropLast(1)
                else leading
            prefix + text
        }
    }

    private fun decodePercentUtf8(value: String): String = percentBytes.replace(value) { match ->
        val escaped = match.value
        val bytes = ByteArray(escaped.length / 3) { index ->
            escaped.substring(index * 3 + 1, index * 3 + 3).toInt(16).toByte()
        }
        // An invalid byte sequence must not silently replace part of a name
        // with a replacement glyph. Preserve its original spelling instead.
        runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrDefault(escaped)
    }

    private fun decodeCharacterReferences(value: String): String = characterReference.replace(value) { match ->
        val reference = match.groupValues[1]
        val replacement = if (reference.startsWith('#')) {
            val hexadecimal = reference.getOrNull(1)?.let { it == 'x' || it == 'X' } == true
            val codePoint = reference.drop(if (hexadecimal) 2 else 1).toIntOrNull(if (hexadecimal) 16 else 10)
            codePoint?.let(::unicodeScalarText)
        } else {
            HtmlCharacterReferences.values[reference]
        }
        replacement ?: match.value
    }
}

internal fun unicodeScalarText(codePoint: Int): String? = when {
    codePoint !in 1..0x10FFFF || codePoint in 0xD800..0xDFFF -> null
    codePoint < 0x10000 -> codePoint.toChar().toString()
    else -> {
        val supplementary = codePoint - 0x10000
        "${(0xD800 + (supplementary shr 10)).toChar()}${(0xDC00 + (supplementary and 0x3FF)).toChar()}"
    }
}
