package com.torve.android.player

import androidx.media3.common.PlaybackException

/**
 * Returns true only for failures where retrying a different representation or
 * source is safer than retrying the same bytes. Media3 can wrap extractor
 * runtime exceptions as ERROR_CODE_UNSPECIFIED, which is why the cause-chain
 * signature is part of the decision.
 */
internal fun isRecoverableContainerFailure(errorCode: Int, causeChain: String): Boolean {
    if (
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    ) {
        return true
    }

    val normalized = causeChain.lowercase()
    return normalized.contains("no valid varint length mask") ||
        (
            normalized.contains("unexpectedloaderexception") &&
                normalized.contains("matroska")
            )
}
