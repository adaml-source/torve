package com.torve.domain.transfer

/**
 * Pure-Kotlin Base64 url-safe codec without padding. Kept tiny and
 * dependency-free so the protocol layer stays KMP-portable. Not
 * constant-time; fine for envelope serialization since the inputs are
 * already public-clear (key bytes, nonces, ciphertexts).
 */
object Base64Url {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        while (i + 3 <= bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val b0 = bytes[i].toInt() and 0xFF
                out.append(ALPHABET[b0 ushr 2])
                out.append(ALPHABET[(b0 and 0x03) shl 4])
            }
            2 -> {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                out.append(ALPHABET[b0 ushr 2])
                out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                out.append(ALPHABET[(b1 and 0x0F) shl 2])
            }
        }
        return out.toString()
    }

    fun decodeOrNull(s: String): ByteArray? {
        if (s.isEmpty()) return ByteArray(0)
        // Reject any character outside the alphabet (no padding, no `+`/`/`).
        val table = IntArray(128) { -1 }
        for (idx in ALPHABET.indices) table[ALPHABET[idx].code] = idx
        val out = ArrayList<Byte>(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in s) {
            val code = ch.code
            if (code !in 0..127) return null
            val v = table[code]
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        // Any leftover non-zero bits indicate corruption.
        if ((buffer and ((1 shl bits) - 1)) != 0) return null
        return out.toByteArray()
    }
}
