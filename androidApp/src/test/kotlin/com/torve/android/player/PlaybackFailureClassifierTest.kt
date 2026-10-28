package com.torve.android.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureClassifierTest {
    @Test
    fun `recognizes matroska varint failure even when media3 reports unspecified`() {
        assertTrue(
            isRecoverableContainerFailure(
                errorCode = PlaybackException.ERROR_CODE_UNSPECIFIED,
                causeChain = "UnexpectedLoaderException <- IllegalStateException:" +
                    "No valid varint length mask found",
            ),
        )
    }

    @Test
    fun `recognizes media3 malformed container code`() {
        assertTrue(
            isRecoverableContainerFailure(
                errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                causeChain = "ParserException",
            ),
        )
    }

    @Test
    fun `does not turn ordinary network failures into container fallback`() {
        assertFalse(
            isRecoverableContainerFailure(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                causeChain = "HttpDataSourceException: connection reset",
            ),
        )
    }
}
