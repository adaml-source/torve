package com.torve.data.ratings

import com.torve.data.network.HttpClientFactory
import com.torve.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicitly opted-in release probe; ordinary unit tests remain independent of the network. */
class CinemetaLiveProbeTest {
    @Test fun productionTransportParsesTheReportedTitle() = runBlocking {
        assumeTrue("Set TORVE_LIVE_RATINGS_PROBE=1 for the live release probe",
            System.getenv("TORVE_LIVE_RATINGS_PROBE") == "1")
        val client = HttpClientFactory.create()
        try {
            val rating = assertNotNull(CinemetaImdbRatingsApi(client).fetch("tt45368240", MediaType.MOVIE))
            assertTrue(rating.score in 0.1f..10f)
            println("TORVE_LIVE_IMDB id=tt45368240 score=${rating.score} votes=${rating.votes}")
        } finally { client.close() }
    }
}
