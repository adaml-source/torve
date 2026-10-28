package com.torve.android.player

import com.torve.data.addon.PandaPreparationFailure
import org.junit.Assert.*
import org.junit.Test

class PandaPreparationFailureTest {
    @Test fun refusedDownloadsAndFailedCloudJobsAreNotDescribedAsStillPreparing() {
        assertTrue(PandaPreparationFailure.message("nzb_access_denied")!!.contains("indexer refused"))
        assertTrue(PandaPreparationFailure.message("nzb_download_failed")!!.contains("could not complete"))
        assertTrue(PandaPreparationFailure.message("nzb_episode_missing")!!.contains("does not contain this episode"))
    }
    @Test fun untrustedErrorTextIsNotEchoedAndPendingHasNoFailureMessage() {
        assertNull(PandaPreparationFailure.message("nzb_not_ready"))
        assertNull(PandaPreparationFailure.message("https://indexer.example/api?apikey=secret"))
    }
}
